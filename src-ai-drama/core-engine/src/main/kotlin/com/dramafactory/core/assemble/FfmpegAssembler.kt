package com.dramafactory.core.assemble

import java.io.File

/**
 * FFmpeg拼接管理器 —— 三级策略（架构§1.3 / 决议Q5+S2冒烟）：
 *   1. 快路径：所有片段编码参数一致 → concat -c copy，秒级完成；
 *   2. 归一化路径：混合分辨率/帧率 → scale+pad 到448x832 + 硬编(h264_mediacodec，端侧)/libx264(JVM)；
 *   3. 降级路径：耗时>10分钟或OOM → 分段导出（每8镜一段），云端组装接口预留。
 *
 * **连续剧禁去头（pavo head_trim 教训，v1.8.0 固化）**：拼接阶段绝不对任意镜（含首镜）
 * 开头做 `-ss` 裁剪——首句台词常落开场 0.5s，裁头必切对白。本管理器只做整体 concat/
 * 归一化，不带任何裁头逻辑；如需去黑场/静音，由上游在每镜内部（尾部 pad）处理，不在此裁。
 *
 * 执行器抽象：端侧为FFmpegKit调用器；JVM测试用命令行ffmpeg桩。
 */
class FfmpegAssembler(
    /** ffmpeg可执行器；JVM默认命令行ffmpeg。返回(exitCode, stderr尾部) */
    private val executor: (args: List<String>) -> Pair<Int, String> = ::execFfmpeg,
    private val isAndroid: Boolean = false,
    private val timeoutWarnMs: Long = 10 * 60_000L,
) {

    enum class Strategy(val label: String) {
        CONCAT_COPY("concat-copy"),
        NORMALIZE("mediacodec归一化"),
        SEGMENTED("分段导出");
    }

    sealed class AssembleResult {
        data class Success(val output: File, val strategy: Strategy, val elapsedMs: Long) : AssembleResult()
        data class Segmented(val parts: List<File>, val elapsedMs: Long) : AssembleResult()
        data class Failure(val strategy: Strategy, val message: String) : AssembleResult()
    }

    /**
     * 主入口：自动选级。segments全部存在且非空才尝试。
     */
    fun assemble(clips: List<File>, output: File): AssembleResult {
        require(clips.isNotEmpty()) { "无镜片段可拼接" }
        val t0 = System.currentTimeMillis()

        // 一级快路径：同规格 → concat copy
        if (allSameSpec(clips)) {
            val listFile = File.createTempFile("concat", ".txt").apply { writeText(
                clips.joinToString("\n") { "file '${it.absolutePath}'" }) }
            val r = executor(listOf("-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                "-c", "copy", output.absolutePath))
            if (r.first == 0 && output.length() > 0)
                return AssembleResult.Success(output, Strategy.CONCAT_COPY, System.currentTimeMillis() - t0)
        }

        // 二级归一化路径：scale+pad 到448x832 + 硬编（端侧h264_mediacodec / JVM libx264）
        // 先拼成一段临时归一化中间产物再concat（简化实现）
        val vcodec = if (isAndroid) "h264_mediacodec" else "libx264"
        val normOut = File(output.parentFile, "${output.nameWithoutExtension}_norm.mp4")
        val filter = clips.indices.joinToString(";") { i ->
            "[$i:v]scale=448:832:force_original_aspect_ratio=decrease,pad=448:832:(ow-iw)/2:(oh-ih)/2,setsar=1[v$i]"
        } + ";" + clips.indices.joinToString("") { "[v$it]" } + "concat=n=${clips.size}:v=1:a=0[vout]"
        val args = mutableListOf("-y")
        clips.forEach { args += listOf("-i", it.absolutePath) }
        args += listOf("-filter_complex", filter, "-map", "[vout]", "-c:v", vcodec,
            "-b:v", "2M", "-r", "24", normOut.absolutePath)
        val r2 = executor(args)
        if (r2.first == 0 && normOut.length() > 0 && System.currentTimeMillis() - t0 < timeoutWarnMs)
            return AssembleResult.Success(normOut, Strategy.NORMALIZE, System.currentTimeMillis() - t0)

        // 三级降级路径：分段导出（每8镜一段）——云端组装预留接口 Assembler.cloud()
        val parts = clips.chunked(8).mapIndexedNotNull { idx, chunk ->
            val part = File(output.parentFile, "${output.nameWithoutExtension}_part$idx.mp4")
            val pf = File.createTempFile("part$idx", ".txt").apply {
                writeText(chunk.joinToString("\n") { "file '${it.absolutePath}'" })
            }
            val pr = executor(listOf("-y", "-f", "concat", "-safe", "0", "-i", pf.absolutePath,
                "-c", "copy", part.absolutePath))
            if (pr.first == 0 && part.length() > 0) part else null
        }
        return if (parts.isNotEmpty()) AssembleResult.Segmented(parts, System.currentTimeMillis() - t0)
        else AssembleResult.Failure(Strategy.SEGMENTED, "三级策略均失败：${r2.second.take(200)}")
    }

    /** 同规格判定桩：真实探测需ffprobe；MVP按文件尺寸近似+显式声明。生产接入FFmpegKit probe */
    private fun allSameSpec(clips: List<File>): Boolean = clips.all { it.length() > 0 }

    // ============ 成片色彩统一（对齐 pavo color_grade：统一色彩分级）============
    // G 子模块：对所有镜应用统一色彩分级，保证全片光照/色调一致。
    // 复用已有拼接管理器：assemble 前先对每镜 grade，再走 assemble 三级策略。

    /**
     * 对单镜应用统一色彩分级（纯滤镜，快速）。
     * @param input 镜片段
     * @param output 分级后输出
     * @param grade 分级配方（见 [ColorGradePreset]）
     * @return (exitCode, stderr尾部)；exitCode==0 且 output>0 视为成功。
     */
    fun gradeClip(input: File, output: File, grade: ColorGradePreset = ColorGradePreset.CINEMA): Pair<Int, String> {
        if (!input.exists() || input.length() == 0L) return 1 to "input missing/empty"
        val filter = grade.filter
        val args = listOf("-y", "-i", input.absolutePath, "-vf", filter, "-c:v", "libx264", "-crf", "18", "-preset", "veryfast", "-c:a", "copy", output.absolutePath)
        val r = executor(args)
        return if (r.first == 0 && output.length() > 0) 0 to r.second else r
    }

    /**
     * 对整集镜片段批量分级（每镜落新文件，返回有序分级后列表）。
     * 任一镜失败则整体失败（整集色彩不统一不如不做）。
     */
    fun gradeBatch(clips: List<File>, outDir: File, grade: ColorGradePreset = ColorGradePreset.CINEMA): AssembleResult {
        if (clips.isEmpty()) return AssembleResult.Failure(Strategy.NORMALIZE, "无镜片段可分级")
        outDir.mkdirs()
        val graded = clips.mapIndexedNotNull { idx, clip ->
            val out = File(outDir, "graded_%03d.mp4".format(idx))
            val r = gradeClip(clip, out, grade)
            if (r.first == 0 && out.length() > 0) out else null
        }
        return if (graded.size == clips.size) {
            // 复用主入口将这些分级后片段拼接成片
            assemble(graded, File(outDir, "graded_assembled.mp4"))
        } else {
            AssembleResult.Failure(Strategy.NORMALIZE, "色彩分级有 ${clips.size - graded.size} 镜失败")
        }
    }

    /** 统一色彩分级配方（对齐 pavo color_grade：cinematic 统一调色）。 */
    enum class ColorGradePreset(val filter: String) {
        /** 电影感：轻微提对比+暖调+色彩统一（pavo 默认） */
        CINEMA("eq=contrast=1.08:brightness=-0.02:saturation=1.06,colortemperature=warm=0.06,format=yuv420p"),
        /** 冷调（夜戏/冷兵器） */
        COOL("eq=contrast=1.06:saturation=1.04,colortemperature=warm=-0.08,format=yuv420p"),
        /** 暖调（日戏/火烛） */
        WARM("eq=contrast=1.06:saturation=1.08,colortemperature=warm=0.12,format=yuv420p"),
        /** 中性（仅统一色彩空间，不改观感） */
        NEUTRAL("format=yuv420p"),
    }

    companion object {
        /** JVM命令行ffmpeg执行器（测试桩/服务器调试用） */
        fun execFfmpeg(args: List<String>): Pair<Int, String> {
            val proc = ProcessBuilder(listOf("ffmpeg") + args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            return proc.exitValue() to out.takeLast(2000)
        }
    }
}
