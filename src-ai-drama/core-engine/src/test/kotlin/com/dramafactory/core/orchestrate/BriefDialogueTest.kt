package com.dramafactory.core.orchestrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T014 任务2 回归单测：AI 模式流式对话 Brief 状态机。
 * 验证「问询→回答→确认→确认」全流程，以及中途自由补充、取消、轮数限制。
 */
class BriefDialogueTest {

    private fun fresh() = BriefDialogue(maxRounds = 6, nowMs = { 0L })

    @Test fun `start 进入问询并问出第一个问题`() {
        val d = fresh()
        d.start("东汉末年，群雄并起……（长文本）")
        assertEquals(BriefState.QUESTIONING, d.state.value)
        assertNotNull(d.nextQuestion.value)
        assertTrue(d.nextQuestion.value!!.contains("时代"))
        assertTrue(d.history.value.any { it.side == DialogueTurn.Side.AI })
    }

    @Test fun `完整5问流程推进到 ANSWERED`() {
        val d = fresh()
        d.start("长剧本")
        d.onAnswer(BriefField.ERA, "东汉")
        d.onAnswer(BriefField.STYLE, "cinematic")
        d.onAnswer(BriefField.CHARACTER_COUNT, "4")
        d.onAnswer(BriefField.MOOD, "史诗")
        assertEquals(BriefState.ANSWERED, d.state.value)
        assertNull(d.nextQuestion.value)
        val b = d.brief.value
        assertEquals("东汉", b.era)
        assertEquals("cinematic", b.style)
        assertEquals(4, b.characterCount)
        assertEquals("史诗", b.mood)
        assertTrue(b.isComplete())
    }

    @Test fun `用户自由补充进入 customNotes`() {
        val d = fresh()
        d.start("长剧本")
        d.onUserNote("男主是哑巴，关键情节用手语")
        assertTrue(d.brief.value.customNotes.contains("手语"))
        // 历史里能看到用户补充 turn
        assertTrue(d.history.value.any { it.side == DialogueTurn.Side.USER && it.content.contains("手语") })
    }

    @Test fun `回答后不再重复问已填字段`() {
        val d = fresh()
        d.start("长剧本")
        d.onAnswer(BriefField.ERA, "唐")
        // 下一个问题不应再含"时代"
        assertFalse(d.nextQuestion.value!!.contains("时代"))
        assertTrue(d.nextQuestion.value!!.contains("风格"))
    }

    @Test fun `confirm 进入 CONFIRMED`() {
        val d = fresh()
        d.start("长剧本")
        d.onAnswer(BriefField.ERA, "明")
        d.onAnswer(BriefField.STYLE, "noir")
        d.onAnswer(BriefField.CHARACTER_COUNT, "3")
        d.onAnswer(BriefField.MOOD, "悬疑")
        d.requestConfirm()
        assertEquals(BriefState.CONFIRMING, d.state.value)
        d.confirm()
        assertEquals(BriefState.CONFIRMED, d.state.value)
        assertTrue(d.brief.value.confirmed)
        assertTrue(d.brief.value.isFullyConfirmed())
    }

    @Test fun `cancel 随时可中断`() {
        val d = fresh()
        d.start("长剧本")
        d.onAnswer(BriefField.ERA, "现代")
        d.cancel()
        assertEquals(BriefState.CANCELLED, d.state.value)
        assertNull(d.nextQuestion.value)
    }

    @Test fun `超过 maxRounds 自动结束问询`() {
        val d = BriefDialogue(maxRounds = 2, nowMs = { 0L })
        d.start("长剧本")
        d.onAnswer(BriefField.ERA, "架空")
        // 第二轮问风格，回答后 round>=2 → 直接 ANSWERED
        d.onAnswer(BriefField.STYLE, "anime")
        assertEquals(BriefState.ANSWERED, d.state.value)
        assertNull(d.nextQuestion.value)
    }

    @Test fun `renderSummary 含所有字段`() {
        val b = Brief(era = "西汉", style = "cinematic", characterCount = 5, mood = "热血", withAudio = true, customNotes = "突出权谋")
        val s = b.renderSummary()
        assertTrue(s.contains("西汉"))
        assertTrue(s.contains("cinematic"))
        assertTrue(s.contains("5"))
        assertTrue(s.contains("热血"))
        assertTrue(s.contains("权谋"))
    }

    @Test fun `toPromptFragment 折叠格式正确`() {
        val b = Brief(era = "唐", style = "水墨", characterCount = 2, mood = "治愈", withAudio = false)
        val f = b.toPromptFragment()
        assertTrue(f.contains("时代=唐"))
        assertTrue(f.contains("风格=水墨"))
        assertTrue(f.contains("主要角色数=2"))
        assertTrue(f.contains("配音=否"))
    }

    @Test fun `editField 在确认阶段可改`() {
        val d = fresh()
        d.start("长剧本")
        d.onAnswer(BriefField.ERA, "宋")
        d.onAnswer(BriefField.STYLE, "real")
        d.onAnswer(BriefField.CHARACTER_COUNT, "3")
        d.onAnswer(BriefField.MOOD, "日常")
        d.requestConfirm()
        d.editField(BriefField.ERA, "南宋")
        assertEquals("南宋", d.brief.value.era)
    }

    @Test fun `editField 在非确认阶段无效`() {
        val d = fresh()
        d.start("长剧本")
        d.onAnswer(BriefField.ERA, "宋")
        d.editField(BriefField.ERA, "南宋")  // 此时还在 QUESTIONING，应被忽略
        assertEquals("宋", d.brief.value.era)
    }
}
