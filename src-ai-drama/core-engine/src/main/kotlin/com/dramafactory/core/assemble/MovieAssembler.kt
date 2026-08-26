package com.dramafactory.core.assemble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 端上成片合成器（T014 §2.4，架构对齐 FfmpegAssembler 三级策略）。
 *
 * 与 FfmpegAssembler 关系：
 * - 共享 [MovieAssembler.AssembleResult] / [MovieAssembler.Strategy] / [MovieAssembler.ColorGradePreset]
 *   契约（全部复用自 FfmpegAssembler 的语义，此处重定义枚举以脱离 FfmpegAssembler 的
 *   "内部类"耦合，标签字符串与 FfmpegAssembler 一致，供断言对齐）。
 * - 端侧执行器从 JVM 命令行 ffmpeg 换成 ffmpeg-kit 5.1 的 Java API
 *   (`com.arthenica.ffmpegkit.FFmpeg.executeAsyncFFmpeg` + `FFmpegSession` 回调)。
 *   因 ffmpeg-kit 是 Android AAR，本 core-engine 模块不直接依赖；具体执行器注入。
 *
 * 三级策略（与 FfmpegAssembler.assemble 严格对齐）：
 *   1. concat -c copy（同规格，秒级）
 *   2. scale+pad 到 448x832 + h264_mediacodec（端侧硬编）
 *   3. 分段导出（每 8 镜一段），返回 Segmented，落 multi_parts_uris
 *
 * JVM 单测注入 `executor = stub` 绕过真实 ffmpeg 调用，只测试策略选择与降级链。
 */
interface MovieAssembler {

    /** 合成进度回调（UI 可见） */
    val progress: StateFlow<MovieAssembleProgress>

    /**
     * 合成整集。
     * @param clips 已按 shot_no 升序的本地单镜 mp4 文件列表（必须全部存在且 >0 字节）
     * @param output 目标 mp4 文件，位于 Context.getFilesDir()/movies/
     * @param grade 统一色彩分级配方
     * @return AssembleResult
     *
     * @throws IllegalArgumentException 若 clips 为空或存在空文件
     * @throws MovieAssembler.NotAvailableException 若 ffmpeg-kit 未初始化/不可用
     */
    suspend fun assemble(
        clips: List<File>,
        output: File,
        grade: ColorGradePreset = ColorGradePreset.CINEMA,
    ): AssembleResult

    /** 与 FfmpegAssembler 对齐的产物结果。 */
    sealed class AssembleResult {
        data class Success(
            val output: File,
            val strategy: Strategy,
            val elapsedMs: Long,
            val durationSeconds: Double = 0.0,
        ) : AssembleResult()
        data class Segmented(
            val parts: List<File>,
            val elapsedMs: Long,
        ) : AssembleResult()
        data class Failure(val strategy: Strategy, val message: String) : AssembleResult()
    }

    /** 三级策略枚举，与 FfmpegAssembler.Strategy 语义一致。 */
    enum class Strategy(val label: String) {
        CONCAT_COPY("concat-copy"),
        NORMALIZE("mediacodec归一化"),
        SEGMENTED("分段导出");
    }

    /** 统一色彩分级配方（与 FfmpegAssembler.ColorGradePreset 完全对齐）。 */
    enum class ColorGradePreset(val filter: String) {
        CINEMA("eq=contrast=1.08:brightness=-0.02:saturation=1.06,colortemperature=warm=0.06,format=yuv420p"),
        COOL("eq=contrast=1.06:saturation=1.04,colortemperature=warm=-0.08,format=yuv420p"),
        WARM("eq=contrast=1.06:saturation=1.08,colortemperature=warm=0.12,format=yuv420p"),
        NEUTRAL("format=yuv420p"),
    }

    data class MovieAssembleProgress(
        val stage: AssembleStage,
        val step: Int,
        val total: Int,
        val message: String,
        val elapsedMs: Long,
    )
    enum class AssembleStage { GRADE, CONCAT, NORMALIZE, PROBE, DONE }

    /** ffmpeg-kit 未加载/不可用 —— 触发云端合成回退提示（架构§六 R1 备选）。 */
    class NotAvailableException(message: String) : Exception(message)
}

/**
 * ffmpeg-kit 调用抽象。
 *
 * JVM 单测注入桩实现（返回固定 exitCode + 可选写入文件），绕过真实 ffmpeg。
 * 端侧生产实现绑定 `com.arthenica.ffmpegkit.FFmpeg`（Android AAR，不进 core-engine classpath）。
 *
 * @param args ffmpeg 命令参数列表
 * @return 返回 (exitCode, stderr 尾部)；exitCode==0 视为成功（配合输出文件存在性判定）
 */
fun interface MovieAssemblerExecutor {
    fun execute(args: List<String>): Pair<Int, String>
}

/**
 * 具体实现：三级策略 + 可注入 executor。
 *
 * 端侧实例化时传入 [androidFfmpegKitExecutor]，得到与 FfmpegAssembler 语义对齐的合成行为；
 * JVM 单测传入桩 executor，绕过 native 依赖。
 */
class MovieAssemblerImpl(
    private val executor: MovieAssemblerExecutor,
) : MovieAssembler {

    private val _progress = MutableStateFlow(
        MovieAssembler.MovieAssembleProgress(
            stage = MovieAssembler.AssembleStage.DONE,
            step = 0, total = 0, message = "ready", elapsedMs = 0,
        )
    )
    override val progress: StateFlow<MovieAssembler.MovieAssembleProgress> = _progress

    private fun emit(stage: MovieAssembler.AssembleStage, step: Int, total: Int, msg: String, t0: Long) {
        _progress.value = MovieAssembler.MovieAssembleProgress(stage, step, total, msg, System.currentTimeMillis() - t0)
    }

    override suspend fun assemble(
        clips: List<File>,
        output: File,
        grade: MovieAssembler.ColorGradePreset,
    ): MovieAssembler.AssembleResult {
        require(clips.isNotEmpty()) { "无镜片段可拼接" }
        clips.forEach { require(it.exists() && it.length() > 0) { "片段不存在或为空: ${it.absolutePath}" } }
        output.parentFile?.mkdirs()
        val t0 = System.currentTimeMillis()
        val total = clips.size
        emit(MovieAssembler.AssembleStage.DONE, 0, total, "start assemble ${total} clips", t0)

        // 统一色彩分级：对每镜走 grade filter，失败则用原文件降级（不阻断整集）
        val gradedFiles: List<File> = try {
            val gradedDir = File(output.parentFile, "${output.nameWithoutExtension}_graded")
            gradedDir.mkdirs()
            clips.mapIndexed { idx, clip ->
                emit(MovieAssembler.AssembleStage.GRADE, idx + 1, total, "grade clip ${idx + 1}/${total}", t0)
                val gOut = File(gradedDir, "g_%03d.mp4".format(idx))
                val filter = grade.filter
                val args = listOf(
                    "-y", "-i", clip.absolutePath,
                    "-vf", filter,
                    "-c:v", "libx264", "-crf", "18", "-preset", "veryfast",
                    "-c:a", "copy",
                    gOut.absolutePath,
                )
                val (rc, _) = executor.execute(args)
                if (rc == 0 && gOut.exists() && gOut.length() > 0) gOut else {
                    emit(MovieAssembler.AssembleStage.GRADE, idx + 1, total,
                        "grade failed clip ${idx + 1}, use raw", t0)
                    clip
                }
            }
        } catch (e: MovieAssembler.NotAvailableException) {
            throw e
        }

        // 一级快路径：concat -c copy
        val concatListFile = File.createTempFile("movie_concat", ".txt").apply { deleteOnExit() }
        concatListFile.writeText(gradedFiles.joinToString("\n") { "file '${it.absolutePath}'" })
        emit(MovieAssembler.AssembleStage.CONCAT, 0, total, "concat-copy attempt", t0)
        val (rcConcat, _) = executor.execute(listOf(
            "-y", "-f", "concat", "-safe", "0",
            "-i", concatListFile.absolutePath,
            "-c", "copy", output.absolutePath,
        ))
        if (rcConcat == 0 && output.exists() && output.length() > 0) {
            emit(MovieAssembler.AssembleStage.DONE, total, total, "concat-copy done", t0)
            return MovieAssembler.AssembleResult.Success(
                output = output,
                strategy = MovieAssembler.Strategy.CONCAT_COPY,
                elapsedMs = System.currentTimeMillis() - t0,
                durationSeconds = 0.0,
            )
        }

        // 二级归一化路径：scale+pad 到 448x832 + h264_mediacodec（端侧硬编）
        val normOut = File(output.parentFile, "${output.nameWithoutExtension}_norm.mp4")
        normOut.parentFile?.mkdirs()
        val filter = gradedFiles.indices.joinToString(";") { i ->
            "[$i:v]scale=448:832:force_original_aspect_ratio=decrease," +
                "pad=448:832:(ow-iw)/2:(oh-ih)/2,setsar=1[v$i]"
        } + ";" + gradedFiles.indices.joinToString("") { "[v$it]" } +
            "concat=n=${gradedFiles.size}:v=1:a=0[vout]"
        val normArgs = buildList {
            add("-y")
            gradedFiles.forEach { addAll(listOf("-i", it.absolutePath)) }
            addAll(listOf(
                "-filter_complex", filter,
                "-map", "[vout]",
                "-c:v", "h264_mediacodec",
                "-b:v", "2M", "-r", "24",
                normOut.absolutePath,
            ))
        }
        emit(MovieAssembler.AssembleStage.NORMALIZE, 0, total, "normalize + mediacodec", t0)
        val (rcNorm, _) = executor.execute(normArgs)
        if (rcNorm == 0 && normOut.exists() && normOut.length() > 0) {
            emit(MovieAssembler.AssembleStage.DONE, total, total, "normalize done", t0)
            return MovieAssembler.AssembleResult.Success(
                output = normOut,
                strategy = MovieAssembler.Strategy.NORMALIZE,
                elapsedMs = System.currentTimeMillis() - t0,
                durationSeconds = 0.0,
            )
        }

        // 三级降级路径：分段导出（每 8 镜一段），保留 concat-copy 语义
        val parts = gradedFiles.chunked(8).mapIndexedNotNull { idx, chunk ->
            val part = File(output.parentFile, "${output.nameWithoutExtension}_part${idx + 1}.mp4")
            val partListFile = File.createTempFile("movie_part${idx + 1}", ".txt").apply { deleteOnExit() }
            partListFile.writeText(chunk.joinToString("\n") { "file '${it.absolutePath}'" })
            val (prc, _) = executor.execute(listOf(
                "-y", "-f", "concat", "-safe", "0",
                "-i", partListFile.absolutePath,
                "-c", "copy", part.absolutePath,
            ))
            if (prc == 0 && part.exists() && part.length() > 0) part else null
        }
        return if (parts.isNotEmpty()) {
            emit(MovieAssembler.AssembleStage.DONE, total, total, "segmented ${parts.size} parts", t0)
            MovieAssembler.AssembleResult.Segmented(parts = parts, elapsedMs = System.currentTimeMillis() - t0)
        } else {
            MovieAssembler.AssembleResult.Failure(
                strategy = MovieAssembler.Strategy.SEGMENTED,
                message = "三级策略均失败：concat/rc=$rcConcat norm/rc=$rcNorm",
            )
        }
    }
}

/**
 * 端侧 executor：用 Java 反射调用 ffmpeg-kit 5.1 (com.arthenica.ffmpegkit.FFmpeg)。
 *
 * 说明：core-engine 是 JVM 纯 Kotlin 模块，不能直接依赖 Android AAR；生产端通过
 * 此 factory 在 app 模块初始化 [MovieAssemblerImpl] 时注入。反射兜底兼容未来 ffmpeg-kit 版本。
 *
 * @throws MovieAssembler.NotAvailableException 当 ffmpeg-kit 类/native 不可用
 */
fun androidFfmpegKitExecutor(): MovieAssemblerExecutor {
    return MovieAssemblerExecutor { args ->
        try {
            val fc = Class.forName("com.arthenica.ffmpegkit.FFmpeg")
            val sc = Class.forName("com.arthenica.ffmpegkit.FFmpegSession")
            val rc = Class.forName("com.arthenica.ffmpegkit.ReturnCode")
            val lv = Class.forName("com.arthenica.ffmpegkit.Level")
            @Suppress("UNCHECKED_CAST")
            val lvlQuiet = lv.getField("QUIET").get(null)
            // 优先同步 API：FFmpeg.executeFFmpeg(args) -> FFmpegSession
            val sync = runCatching { fc.getMethod("executeFFmpeg", String::class.java) }.getOrNull()
            val session = if (sync != null) {
                sync.invoke(null, *args.toTypedArray())
            } else {
                @Suppress("UNCHECKED_CAST")
                val callback = Class.forName("com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback")
                @Suppress("UNCHECKED_CAST")
                val async = fc.getMethod("executeAsyncFFmpeg", String::class.java, callback)
                val latch = java.util.concurrent.CountDownLatch(1)
                var result: Any? = null
                val cb = java.lang.reflect.Proxy.newProxyInstance(
                    callback.classLoader, arrayOf(callback),
                    { _, m, a ->
                        if (m.name == "apply") { result = a?.first(); latch.countDown() }
                        null
                    })
                async.invoke(null, *args.toTypedArray(), cb)
                latch.await(30, java.util.concurrent.TimeUnit.MINUTES)
                result
            }
            val getRc = sc.getMethod("getReturnCode")
            val getVal = rc.getMethod("getValue")
            (getVal.invoke(getRc.invoke(session)) as Int) to ""
        } catch (e: UnsatisfiedLinkError) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit native 未加载: ${e.message}. 需要升级到 v1.5 远程合成方案")
        } catch (e: NoClassDefFoundError) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit 类缺失: ${e.message}. 需要升级到 v1.5 远程合成方案")
        } catch (e: ClassNotFoundException) {
            throw MovieAssembler.NotAvailableException("ffmpeg-kit 未找到: ${e.message}. 需要升级到 v1.5 远程合成方案")
        }
    }
}
