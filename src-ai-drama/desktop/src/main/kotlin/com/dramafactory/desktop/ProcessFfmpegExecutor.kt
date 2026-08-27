package com.dramafactory.desktop

import com.dramafactory.core.assemble.MovieAssemblerExecutor
import java.io.File

/**
 * 桌面端 ffmpeg 执行器：调系统 ffmpeg 二进制（Process）。
 * 实现 MovieAssemblerExecutor：收 ffmpeg 参数列表，返回 (exitCode, outputText)。
 * 本机已确认 /usr/local/bin/ffmpeg 7.0.2 存在。
 */
class ProcessFfmpegExecutor : MovieAssemblerExecutor {
    private val bin = detectFfmpeg()

    private fun detectFfmpeg(): String {
        for (c in listOf("ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")) {
            runCatching {
                if (ProcessBuilder(c, "-version").start().waitFor() == 0) return c
            }
        }
        return "ffmpeg"
    }

    override fun execute(args: List<String>): Pair<Int, String> {
        val cmd = listOf(bin) + args
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return code to out
    }
}
