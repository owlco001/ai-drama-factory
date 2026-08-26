package com.dramafactory.core.assemble

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MovieAssembler 三级策略 JVM 单测（T014 §2.4）。
 *
 * 注入桩 [StubExecutor] 绕过真实 ffmpeg，只测试策略选择与降级链。
 * 8 个用例覆盖：
 *   1) concat-copy 成功 → Success(CONCAT_COPY)
 *   2) concat 失败 → 归一化成功 → Success(NORMALIZE)
 *   3) 三级降级 → 分段导出（每 8 镜一段）
 *   4) 三级全失败 → Failure(SEGMENTED)
 *   5) clips 为空 → IllegalArgumentException
 *   6) clips 中存在空文件 → IllegalArgumentException
 *   7) Strategy 枚举与 FfmpegAssembler 对齐
 *   8) ColorGradePreset 枚举与 FfmpegAssembler 对齐
 */
class MovieAssemblerTest {

    private class StubExecutor(
        private val concatReturnCode: Int = 0,
        private val normalizeReturnCode: Int = 0,
        private val segmentReturnCode: Int = 0,
    ) : MovieAssemblerExecutor {
        override fun execute(args: List<String>): Pair<Int, String> {
            val joined = args.joinToString(" ")
            val outputFile = if (args.isNotEmpty()) File(args.last()) else null
            if (joined.contains("-f concat") && !joined.contains("_part")) {
                if (concatReturnCode == 0) outputFile?.writeBytes("ok".toByteArray())
                return concatReturnCode to ""
            }
            if (joined.contains("_norm.mp4") || joined.contains("h264_mediacodec")) {
                if (normalizeReturnCode == 0) outputFile?.writeBytes("ok".toByteArray())
                return normalizeReturnCode to ""
            }
            if (joined.contains("_part")) {
                if (segmentReturnCode == 0) outputFile?.writeBytes("ok".toByteArray())
                return segmentReturnCode to ""
            }
            if (joined.contains("-c:v libx264") || joined.contains("-vf")) {
                outputFile?.writeBytes("ok".toByteArray())
                return 0 to ""
            }
            return 0 to ""
        }
    }

    private fun tmpDir(): File = Files.createTempDirectory("movieasm").toFile().also { it.deleteOnExit() }

    private fun makeClips(dir: File, n: Int, size: Int = 64): List<File> =
        List(n) { i ->
            File(dir, "clip_$i.mp4").apply { writeBytes("x".repeat(size).toByteArray()) }
        }

    private fun runSuspending(block: suspend () -> Unit): Unit {
        val thread = Thread {
            runBlocking { block() }
        }
        thread.start()
        thread.join(10_000)
    }

    @Test
    fun `策略一 concat-copy 成功`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 3)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 0))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            assertEquals(MovieAssembler.Strategy.CONCAT_COPY, (result as MovieAssembler.AssembleResult.Success).strategy)
        }
    }

    @Test
    fun `策略二 concat 失败_归一化成功`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 3)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 1, normalizeReturnCode = 0))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Success)
            assertEquals(MovieAssembler.Strategy.NORMALIZE, (result as MovieAssembler.AssembleResult.Success).strategy)
            assertTrue(result.output.name.endsWith("_norm.mp4"))
        }
    }

    @Test
    fun `策略三 三段导出_17镜拆成3段`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 17)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 1, normalizeReturnCode = 1, segmentReturnCode = 0))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Segmented)
            assertEquals(3, (result as MovieAssembler.AssembleResult.Segmented).parts.size)
        }
    }

    @Test
    fun `三级全失败_Failure_SEGMENTED`() {
        runSuspending {
            val dir = tmpDir()
            val clips = makeClips(dir, 2)
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor(concatReturnCode = 1, normalizeReturnCode = 1, segmentReturnCode = 1))
            val result = assembler.assemble(clips, output)
            assertTrue(result is MovieAssembler.AssembleResult.Failure)
            assertEquals(MovieAssembler.Strategy.SEGMENTED, (result as MovieAssembler.AssembleResult.Failure).strategy)
        }
    }

    @Test
    fun `empty_clips_throws_IllegalArgumentException`() {
        runSuspending {
            val dir = tmpDir()
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor())
            assertFailsWith<IllegalArgumentException> {
                assembler.assemble(emptyList(), output)
            }
        }
    }

    @Test
    fun `clip_with_zero_bytes_throws_IllegalArgumentException`() {
        runSuspending {
            val dir = tmpDir()
            val emptyClip = File(dir, "empty.mp4") // 0 字节
            val output = File(dir, "out.mp4")
            val assembler = MovieAssemblerImpl(executor = StubExecutor())
            assertFailsWith<IllegalArgumentException> {
                assembler.assemble(listOf(emptyClip), output)
            }
        }
    }

    @Test
    fun `Strategy 枚举标签与 FfmpegAssembler 对齐`() {
        assertEquals(MovieAssembler.Strategy.CONCAT_COPY.label, FfmpegAssembler.Strategy.CONCAT_COPY.label)
        assertEquals(MovieAssembler.Strategy.NORMALIZE.label, FfmpegAssembler.Strategy.NORMALIZE.label)
        assertEquals(MovieAssembler.Strategy.SEGMENTED.label, FfmpegAssembler.Strategy.SEGMENTED.label)
    }

    @Test
    fun `ColorGradePreset 枚举与 FfmpegAssembler 对齐`() {
        assertEquals(FfmpegAssembler.ColorGradePreset.CINEMA.filter, MovieAssembler.ColorGradePreset.CINEMA.filter)
        assertEquals(FfmpegAssembler.ColorGradePreset.COOL.filter, MovieAssembler.ColorGradePreset.COOL.filter)
        assertEquals(FfmpegAssembler.ColorGradePreset.WARM.filter, MovieAssembler.ColorGradePreset.WARM.filter)
        assertEquals(FfmpegAssembler.ColorGradePreset.NEUTRAL.filter, MovieAssembler.ColorGradePreset.NEUTRAL.filter)
        assertEquals(4, MovieAssembler.ColorGradePreset.values().size)
    }
}
