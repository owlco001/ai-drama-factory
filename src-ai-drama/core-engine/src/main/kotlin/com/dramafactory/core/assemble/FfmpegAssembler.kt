package com.dramafactory.core.assemble

import java.io.File

/**
 * FFmpeg拼接管理器 —— 三级策略（架构§1.3 / 决议Q5+S2冒烟）：
 *   1. 快路径：所有片段编码参数一致 → concat -c copy，秒级完成；
 *   2. 归一化路径：混合分辨率/帧率 → scale+pad 到448x832 + 硬编(h264_mediacodec，端侧)/libx264(JVM)；
 *   3. 降级路径：耗时>10分钟或OOM → 分段导出（每8镜一段），云端组装接口预留。
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
            val pf = File.createTempFile("p$idx", ".txt").apply {
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
