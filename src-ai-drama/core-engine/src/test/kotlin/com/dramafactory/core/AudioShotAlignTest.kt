package com.dramafactory.core

import com.dramafactory.core.assemble.AudioShotAlign
import kotlin.test.*

/**
 * v1.8.0 音频对齐工具（对齐 pavo tts.py：逐镜合成 + 按镜头时长 pad/trim + 按序拼接 = 天然对齐）。
 * 核心约束：连续剧禁去头——对齐只在尾部 pad/trim，绝不对首镜开头做 -ss 裁剪。
 */
class AudioShotAlignTest {

    private fun tmp() = java.io.File(System.getProperty("java.io.tmpdir"), "asa-" + System.nanoTime()).apply { mkdirs() }

    @Test
    fun `逐镜对齐命令数量等于镜数且含apad与-t目标时长且不含裁头-ss`() {
        val dir = tmp()
        val segs = (1..3).map { java.io.File(dir, "s$it.wav").apply { writeBytes(ByteArray(4)) } }
        val targets = listOf(5.0, 6.0, 4.5)
        val cmds = AudioShotAlign.buildAlignCommands(segs, targets, java.io.File(dir, "out"))
        assertEquals(3, cmds.size)
        cmds.forEachIndexed { i, c ->
            assertTrue(c.contains("apad"), "镜${i + 1} 含 apad（尾部补静音，不足目标时长）")
            val t = c.indexOf("-t")
            assertTrue(t > 0, "镜${i + 1} 含 -t 截断参数")
            assertEquals("%.3f".format(targets[i]), c[t + 1], "镜${i + 1} 目标时长正确")
            assertFalse(c.any { it == "-ss" }, "镜${i + 1} 绝不对开头裁剪(-ss)——连续剧禁去头")
        }
    }

    @Test
    fun `concatList按镜序输出`() {
        val files = (1..2).map { java.io.File("/tmp/a$it.m4a") }
        val s = AudioShotAlign.concatList(files)
        assertTrue(s.contains("file '/tmp/a1.m4a'"))
        assertTrue(s.contains("file '/tmp/a2.m4a'"))
        assertTrue(s.indexOf("a1.m4a") < s.indexOf("a2.m4a"), "按镜序拼接 = 对口白时序")
    }

    @Test
    fun `段数与目标时长不一致抛异常`() {
        val dir = tmp()
        val segs = listOf(java.io.File(dir, "s1.wav"))
        assertFailsWith<IllegalArgumentException> {
            AudioShotAlign.buildAlignCommands(segs, listOf(1.0, 2.0), java.io.File(dir, "o"))
        }
    }
}
