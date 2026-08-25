// checkpoint防重复付费 + 预算超限拦截 + Key加解密往返 测试
package com.dramafactory.core

import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.storage.InMemoryCheckpointStore
import com.dramafactory.core.storage.InMemoryKeyVault
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CheckpointBudgetKeyTest {

    private fun shots(vararg ids: String) = ids.map { ShotMeta(it, "ep1", "prompt") }

    @Test
    fun `提交成功但未落库后恢复_不产生重复提交`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1", "s2", "s3"))
        // 模拟：镜1已真实提交（video_id到手）但进程在落库后、完成前被杀
        store.markSubmitted("s1", "vid-001")
        // 进程重启 → loadOrMerge恢复
        val cp = store.loadOrMerge("ep1", shots("s1", "s2", "s3"))
        assertEquals(ShotState.SUBMITTED, cp.byId("s1")!!.state, "submitted权威态保留")
        val repoll = store.pendingRepoll("ep1")
        assertEquals(listOf("s1"), repoll.map { it.shotId }, "仅镜1需re-poll")
        assertEquals("vid-001", repoll[0].providerTaskId, "复用原video_id，绝不重新submit→零重复付费(US5)")
    }

    @Test
    fun `loadOrMerge补缺失镜且completed文件缺失重置pending`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1", "s2"))
        store.markCompleted("s1", "file://clip_s1.mp4", 1024)
        store.diskFiles.remove("file://clip_s1.mp4")  // 模拟磁盘文件被清理
        val cp = store.loadOrMerge("ep1", shots("s1", "s2", "s3"))
        assertEquals(ShotState.PENDING, cp.byId("s1")!!.state, "COMPLETED但文件缺失→重置PENDING重做")
        assertNotNull(cp.byId("s3"), "缺失镜被补充")
        assertEquals(ShotState.PENDING, cp.byId("s2")!!.state)
        assertEquals(3, cp.shots.size)
    }

    @Test
    fun `markCompleted要求size大于0`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1"))
        assertFailsWith<IllegalArgumentException> { store.markCompleted("s1", "f", 0) }
        store.markCompleted("s1", "f", 512)
        assertEquals(ShotState.COMPLETED, store.pendingRepoll("ep1").let { store.getEpisode("ep1")!!.byId("s1")!!.state })
    }

    @Test
    fun `failed与blocked权威态不因恢复翻转`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1", "s2"))
        store.markFailed("s1", "video task failed")
        val cp = store.loadOrMerge("ep1", shots("s1", "s2"))
        assertEquals(ShotState.FAILED, cp.byId("s1")!!.state, "FAILED保持权威态")
        assertTrue(store.pendingRepoll("ep1").isEmpty())
    }

    // ---------------- 预算闸门（US6/决议Q2条数型） ----------------

    @Test
    fun `预算超限拦截_确认后才放行`() {
        val guard = DefaultBudgetGuard(mutableMapOf("p1" to 2))
        assertTrue(guard.canSubmit("p1"))
        guard.consumeSubmitted("p1"); guard.consumeSubmitted("p1")
        assertFalse(guard.canSubmit("p1"), "达到上限即拦截")
        assertEquals(BudgetUsage(2, 2), guard.usage.value)
    }

    // ---------------- KeyVault往返 ----------------

    @Test
    fun `Key保存读取掩码删除往返`() = runBlocking {
        val vault = InMemoryKeyVault()
        vault.save("cfg-1", "agnes", "sk-abcdefghijklmnop-xyz")
        assertEquals("sk-abcdefghijklmnop-xyz", vault.load("cfg-1"), "Provider层可取明文")
        assertEquals("sk-***xyz", vault.masked("cfg-1"), "UI只见前3后3掩码")
        vault.delete("cfg-1")
        assertFailsWith<NoSuchElementException> { vault.load("cfg-1") }
        assertEquals("<empty>", vault.masked("cfg-1"))
    }
}

private typealias BudgetUsage = com.dramafactory.core.model.BudgetUsage
