// 限速门时序测试：首发免等；第二发起点距上次<120s须阻塞至满间隔（可注入sleeper断言）
package com.dramafactory.core

import com.dramafactory.core.model.ChannelKind
import com.dramafactory.core.pipeline.DefaultRateGate
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RateGateTest {

    @Test
    fun `首发提交免等待`() = runBlocking {
        val waits = mutableListOf<Long>()
        val gate = DefaultRateGate(120_000) { waits += it }
        gate.awaitSlot(ChannelKind.VIDEO)
        assertTrue(waves_empty(waits), "首次提交不应sleep")
    }

    private fun waves_empty(l: List<Long>) = l.isEmpty()

    @Test
    fun `第二次提交阻塞至满120秒间隔`() = runBlocking {
        var sleptFor = 0L
        val gate = DefaultRateGate(120_000) { sleptFor = it }
        gate.awaitSlot(ChannelKind.VIDEO)
        // 模拟仅过了30s就再次请求slot
        gate.awaitSlot(ChannelKind.VIDEO)
        assertEquals(120_000 - 0, sleptFor, "门应补足剩余等待时间")
    }

    @Test
    fun `并发调用串行占坑不穿透`() = runBlocking {
        val sleeps = mutableListOf<Long>()
        val gate = DefaultRateGate(1_000) { sleeps += it }
        val a = async { gate.awaitSlot(ChannelKind.VIDEO) }
        val b = async { gate.awaitSlot(ChannelKind.VIDEO) }
        a.await(); b.await()
        // 两个调用中恰有一个产生等待（第二个被门拦住）
        assertEquals(1, sleeps.size, "Mutex保证并发只有一个等待者，且时间戳占坑防穿透")
    }

    @Test
    fun `非法间隔配置兜底回默认120s`() {
        assertEquals(DefaultRateGate.DEFAULT_INTERVAL_MS, DefaultRateGate.sanitizeInterval(-5))
        assertEquals(DefaultRateGate.DEFAULT_INTERVAL_MS, DefaultRateGate.sanitizeInterval(0))
        assertEquals(DefaultRateGate.DEFAULT_INTERVAL_MS, DefaultRateGate.sanitizeInterval(Long.MAX_VALUE))
        assertEquals(60_000L, DefaultRateGate.sanitizeInterval(60_000L))
    }

    @Test
    fun `TEXT通道不受视频限速门约束`() = runBlocking {
        val gate = DefaultRateGate(120_000) { fail("非VIDEO通道不应等待") }
        gate.awaitSlot(ChannelKind.TEXT)
        gate.awaitSlot(ChannelKind.IMAGE)
    }
}
