package com.dramafactory.core

import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.AgnesRegion
import com.dramafactory.core.provider.DefaultTextModelRouter
import com.dramafactory.core.provider.InMemoryTextModelStore
import com.dramafactory.core.provider.PREF_AGNES_REGION
import com.dramafactory.core.provider.TextProvider
import com.dramafactory.core.provider.agnesScopedConfigId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.8.8：Agnes 中国站（region）解析与持久化一致性单测。
 */
class AgnesRegionTest {

    @Test
    fun `region 常量值正确`() {
        assertEquals("https://api.agnes-ai.cn/v1", AgnesProvider.BASE_URL_CN)
        assertEquals("https://api.agnes-ai.cn/agnesapi", AgnesProvider.VIDEO_RESULT_URL_CN)
        assertEquals("agnes-region", PREF_AGNES_REGION)
    }

    @Test
    fun `默认国际站 resolvedBaseUrl 为官方国际端点`() {
        val p = AgnesProvider()
        assertEquals(AgnesProvider.BASE_URL, p.resolvedBaseUrl)
        assertFalse(p.resolvedBaseUrl.contains("agnes-ai.cn"))
    }

    @Test
    fun `中国站 resolvedBaseUrl 走中国端点且覆盖自定义 override 之外的官方端点`() {
        val p = AgnesProvider(region = AgnesRegion.CHINA)
        assertEquals(AgnesProvider.BASE_URL_CN, p.resolvedBaseUrl)
    }

    @Test
    fun `自定义 override 优先于中国站（显式自定义不被动覆盖）`() {
        val p = AgnesProvider(region = AgnesRegion.CHINA, baseUrlOverride = "https://example.com/v1")
        assertEquals("https://example.com/v1", p.resolvedBaseUrl)
    }

    @Test
    fun `store 持久化 region 往返：CHINA 可恢复，空值回退 INTERNATIONAL`() = runTest {
        val store = InMemoryTextModelStore()
        store.saveAgnesRegion(AgnesRegion.CHINA)
        assertEquals(AgnesRegion.CHINA, store.loadAgnesRegion())

        val fresh = InMemoryTextModelStore()
        // 无持久值时默认国际站
        assertEquals(AgnesRegion.INTERNATIONAL, fresh.loadAgnesRegion())
    }

    @Test
    fun `router resolve agnes 在 CHINA 下仍返回 AgnesProvider 实例`() = runTest {
        DefaultTextModelRouter.agnesRegion = AgnesRegion.CHINA
        val tp: TextProvider = DefaultTextModelRouter.resolve("agnes")
        assertTrue(tp is AgnesProvider, "resolve(agnes) 应返回 AgnesProvider")
        DefaultTextModelRouter.agnesRegion = AgnesRegion.INTERNATIONAL
    }

    // ===== v1.8.9：Agnes Key 按 region 分池（中国站与国际站不共用 API Key） =====

    @Test
    fun `agnesScopedConfigId 国际站原样返回所有 configId`() {
        val ids = listOf("agnes", "agnes-video", "agnes-image", "text-agnes", "custom-video", "deepseek")
        ids.forEach { assertEquals(it, agnesScopedConfigId(it, AgnesRegion.INTERNATIONAL), "国际站不应改写: $it") }
    }

    @Test
    fun `agnesScopedConfigId 中国站仅改写 agnes 系 configId`() {
        assertEquals("agnes-cn", agnesScopedConfigId("agnes", AgnesRegion.CHINA))
        assertEquals("agnes-cn-video", agnesScopedConfigId("agnes-video", AgnesRegion.CHINA))
        assertEquals("agnes-cn-image", agnesScopedConfigId("agnes-image", AgnesRegion.CHINA))
        assertEquals("text-agnes-cn", agnesScopedConfigId("text-agnes", AgnesRegion.CHINA))
        // 非 agnes 系不动（custom / deepseek 等保持独立池）
        assertEquals("custom-video", agnesScopedConfigId("custom-video", AgnesRegion.CHINA))
        assertEquals("deepseek", agnesScopedConfigId("deepseek", AgnesRegion.CHINA))
    }

    @Test
    fun `store 中国站与国际站 Key 不互通（无跨池回退）`() = runTest {
        val store = InMemoryTextModelStore()
        // 中国站写入 Key
        store.saveAgnesRegion(AgnesRegion.CHINA)
        store.saveKey("agnes", "CN-KEY")
        assertEquals("CN-KEY", store.loadKey("agnes"), "中国站应读到自身 Key")

        // 切回国际站：必须读不到中国站 Key（否则会拿中国站 Key 打国际站接口 → 401）
        store.saveAgnesRegion(AgnesRegion.INTERNATIONAL)
        assertTrue(store.loadKey("agnes").isEmpty(), "国际站不应回退读到中国站 Key")

        // 国际站独立写入，不影响中国站池
        store.saveKey("agnes", "INTL-KEY")
        assertEquals("INTL-KEY", store.loadKey("agnes"))
        store.saveAgnesRegion(AgnesRegion.CHINA)
        assertEquals("CN-KEY", store.loadKey("agnes"), "切回中国站应恢复原 Key，互不覆盖")
    }
}
