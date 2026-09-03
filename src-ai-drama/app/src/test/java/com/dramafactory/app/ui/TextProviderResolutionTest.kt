package com.dramafactory.app.ui

import com.dramafactory.app.resolveTextProviderFor
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.AgnesRegion
import com.dramafactory.core.provider.DeepSeekProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * AppGraph.resolveTextProviderFor 解析一致性单测（v1.8.6 理清激活模型字段）。
 *
 * 不复活 object AppGraph（避免触发整图 init），直接驱动文件级内部纯函数，
 * 用 fake keyLoader 模拟 keyVault 各 configId 的落库情况。
 */
class TextProviderResolutionTest {

    /** keyLoader：从给定 map 取 key，缺失/空白返回 null */
    private fun loader(map: Map<String, String>): suspend (String) -> String? =
        { id -> map[id]?.takeIf { it.isNotBlank() } }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活Agnes_有AgnesKey_返回AgnesProvider带该Key() = runTest {
        val p = resolveTextProviderFor("agnes", loader(mapOf("text-agnes" to "sk-agnes-x"))) as AgnesProvider
        assertEquals("sk-agnes-x", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活DeepSeek_有DeepSeekKey_返回DeepSeekProvider带该Key() = runTest {
        val p = resolveTextProviderFor("deepseek", loader(mapOf("text-deepseek" to "sk-ds-x"))) as DeepSeekProvider
        assertEquals("sk-ds-x", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活Agnes_仅DeepSeekKey_优雅回退DeepSeek() = runTest {
        val p = resolveTextProviderFor("agnes", loader(mapOf("text-deepseek" to "sk-ds-only"))) as DeepSeekProvider
        assertEquals("sk-ds-only", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活DeepSeek_仅AgnesKey_优雅回退Agnes() = runTest {
        val p = resolveTextProviderFor("deepseek", loader(mapOf("text-agnes" to "sk-agnes-only"))) as AgnesProvider
        assertEquals("sk-agnes-only", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活Agnes_两Key都在_必须选Agnes_不被DeepSeek优先覆盖() = runTest {
        // 回归：修复前 textProviderFor 写死 DeepSeek 优先，会错选 DeepSeek。
        val p = resolveTextProviderFor(
            "agnes",
            loader(mapOf("text-agnes" to "sk-agnes-x", "text-deepseek" to "sk-ds-x")),
        ) as AgnesProvider
        assertEquals("sk-agnes-x", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活DeepSeek_两Key都在_必须选DeepSeek() = runTest {
        val p = resolveTextProviderFor(
            "deepseek",
            loader(mapOf("text-agnes" to "sk-agnes-x", "text-deepseek" to "sk-ds-x")),
        ) as DeepSeekProvider
        assertEquals("sk-ds-x", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 无Key_空跑激活Provider_引导去设置() = runTest {
        assertTrue(resolveTextProviderFor("agnes", loader(emptyMap())) is AgnesProvider)
        assertTrue(resolveTextProviderFor("deepseek", loader(emptyMap())) is DeepSeekProvider)
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活Agnes_中国站_Key仅存textAgnesCn_返回AgnesProvider带该Key() = runTest {
        val p = resolveTextProviderFor(
            "agnes",
            loader(mapOf("text-agnes-cn" to "sk-agnes-cn")),
            region = AgnesRegion.CHINA,
        ) as AgnesProvider
        assertEquals("sk-agnes-cn", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活Agnes_中国站_仅国际站Key_不跨池回退() = runTest {
        // 国际站 Key 在中国站必然 401，应空跑而非误用
        val p = resolveTextProviderFor(
            "agnes",
            loader(mapOf("text-agnes" to "sk-agnes-intl")),
            region = AgnesRegion.CHINA,
        ) as AgnesProvider
        assertEquals("", p.apiKeyProvider())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 激活DeepSeek_中国站_KeyConfigId不变() = runTest {
        // DeepSeek 不走 Agnes region 分池，configId 保持 text-deepseek
        val p = resolveTextProviderFor(
            "deepseek",
            loader(mapOf("text-deepseek" to "sk-ds-cn")),
            region = AgnesRegion.CHINA,
        ) as DeepSeekProvider
        assertEquals("sk-ds-cn", p.apiKeyProvider())
    }
}
