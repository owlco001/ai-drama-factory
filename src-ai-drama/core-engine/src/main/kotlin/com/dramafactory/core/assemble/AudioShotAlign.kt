package com.dramafactory.core.assemble

import java.io.File

/**
 * 逐镜音频对齐（对齐 pavo tts.py：逐镜合成 + 按镜头时长 pad/trim + 按序拼接 = 天然对齐）。
 *
 * **连续剧禁去头（pavo head_trim 教训）**：配音段绝不裁掉开头——首句台词常落开场 0.5s，
 * 裁头必切对白。对齐只做「不足补静音 / 超出截断」（均在尾部），首镜开头零裁剪。
 *
 * 本工具产出每镜的 ffmpeg 对齐命令（纯函数，便于单测与端侧执行器注入）；不耦合 FFmpegKit，
 * 执行交由 [MovieAssembler] 的 executor。v1.8.0 为独立 TTS 音频管线预留（当前配音走
 * 视频模型原生声轨，本工具待 v1.9 接 TTS 时启用）。
 */
object AudioShotAlign {

    /**
     * 为每镜生成「对齐到目标时长」的 ffmpeg 命令。
     * @param segments 按 shot_no 升序的音频段文件
     * @param targets  每镜目标时长(秒)，与 segments 同序同长
     * @param outDir   对齐后文件输出目录
     * @return 每镜一条 ffmpeg 参数列表（绝对路径，按序即对接白时序）
     */
    fun buildAlignCommands(
        segments: List<File>,
        targets: List<Double>,
        outDir: File,
    ): List<List<String>> {
        require(segments.size == targets.size) { "音频段与目标时长数量必须一致" }
        require(segments.isNotEmpty()) { "无音频段可对齐" }
        outDir.mkdirs()
        return segments.mapIndexed { i, f ->
            val target = targets[i].coerceAtLeast(0.1)
            val out = File(outDir, "aligned_%03d.m4a".format(i))
            listOf(
                "-y", "-i", f.absolutePath,
                // apad：尾部补静音(不足目标时长)；-t target：超出则截断(尾部，绝不裁头)
                "-af", "apad", "-t", "%.3f".format(target),
                out.absolutePath,
            )
        }
    }

    /** 把对齐后的音频段按序拼成 concat 列表文件内容（直接喂 ffmpeg `concat -f concat`）。 */
    fun concatList(aligned: List<File>): String =
        aligned.joinToString("\n") { "file '${it.absolutePath}'" }
}
