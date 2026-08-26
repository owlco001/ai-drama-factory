package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/** 第十轮：上下文熔断器单测（ContextWindowExceededError 修复回归） */
class TokenGuardTest {

    @Test
    fun 正常文本不熔断() {
        assertFalse(AssetAuditor.inputOverloaded("蒲奴·匈奴战士，中箭落马，眼神坚毅"))
    }

    @Test
    fun 巨量base64熔断() {
        // ~1MB base64 ≈ 26万token（ascii/4）；两张即超40万上限
        val big = "QUJD".repeat(256 * 1024)   // 1MB
        assertTrue(AssetAuditor.estimateTokens(big) > 200_000)
        assertTrue(AssetAuditor.inputOverloaded(big, big))
    }

    @Test
    fun 中文token估算() {
        assertEquals(10, AssetAuditor.estimateTokens("一二三四五六七八九十"))
    }
}
