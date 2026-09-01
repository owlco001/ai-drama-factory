package com.dramafactory.core

import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.AgnesRegion
import com.dramafactory.core.provider.DefaultTextModelRouter
import com.dramafactory.core.provider.InMemoryTextModelStore
import com.dramafactory.core.provider.PREF_AGNES_REGION
import com.dramafactory.core.provider.TextProvider
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
}
