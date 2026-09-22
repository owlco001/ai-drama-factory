package com.dramafactory.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MiMoProvider 纯逻辑单测（不发网络）。
 * 覆盖：base_url 按 Key 前缀选站（tp- = Token Plan，sk- = 按量付费）、token 估算。
 */
class MiMoProviderTest {

    @Test
    fun baseUrlFor_tp前缀走TokenPlan站点() {
        assertEquals(
            MiMoProvider.BASE_URL_TOKEN_PLAN,
            MiMoProvider.baseUrlFor("tp-abc123"),
        )
        assertEquals("https://token-plan-cn.xiaomimimo.com/v1", MiMoProvider.baseUrlFor("tp-x"))
    }

    @Test
    fun baseUrlFor_sk前缀走按量付费站点() {
        assertEquals(
            MiMoProvider.BASE_URL_PAYG,
            MiMoProvider.baseUrlFor("sk-abc123"),
        )
        assertEquals("https://api.xiaomimimo.com/v1", MiMoProvider.baseUrlFor("sk-x"))
    }

    @Test
    fun baseUrlFor_未知前缀按按量付费处理() {
        assertEquals(MiMoProvider.BASE_URL_PAYG, MiMoProvider.baseUrlFor("anything"))
    }

    @Test
    fun estimateTokens_中文按1token估_ASCII按4字符1token() {
        assertEquals(2L, MiMoProvider.estimateTokens("你好"))
        // 4 个 ASCII 字符 ≈ 1 token
        assertEquals(1L, MiMoProvider.estimateTokens("abcd"))
    }

    @Test
    fun providerId_and_model_default() {
        val p = MiMoProvider()
        assertEquals("mimo", p.id)
        assertEquals("mimo-v2.6-pro", MiMoProvider.MODEL)
    }
}
