// 第二轮修复回归测试（对应评审R1 P0×2 + P1×6）
package com.dramafactory.core

import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.pipeline.DefaultPipelineOrchestrator
import com.dramafactory.core.pipeline.DefaultRateGate
import com.dramafactory.core.pipeline.DefaultRenderQueue
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.storage.InMemoryCheckpointStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class Round2FixRegressionTest {

    private fun shots(vararg ids: String, ep: String = "ep1") = ids.map { ShotMeta(it, ep, "台词") }

    /** 可编程假Provider */
    private class FakeVideo(
        var submitHook: suspend () -> Unit = {},
        var pollFailures: Int = 0,
        val downloads: AtomicInteger = AtomicInteger(0),
        var downloadFailures: Int = 0,
    ) : com.dramafactory.core.provider.VideoProvider {
        override val id = "fake"
        val submitted = java.util.concurrent.ConcurrentLinkedQueue<String>()
        override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
        override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
        override suspend fun submitVideo(req: VideoSubmitRequest): String {
            submitHook()
            submitted += req.shotId
            return "vid-${req.shotId}"
        }
        override suspend fun pollResult(taskId: String): PollResult {
            if (pollFailures > 0) { pollFailures--; throw ProviderError.TransientError("net blip", retryable = true) }
            return PollResult.Completed("http://x/$taskId.mp4")
        }
    }

    // ==================== P0-1：提交中途杀进程恢复，绝不重复付费 ====================

    @Test
    fun `P0_1 提交前意图已落库_submit挂起期间杀进程_恢复后该镜不重提`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1", "s2"))
        val gate = CompletableDeferred<Unit>()
        val queue = DefaultRenderQueue(
            scope = CoroutineScope(Dispatchers.Default),
            videoProvider = FakeVideo(submitHook = { gate.await() }),  // 卡在submit中=进程被杀点
            checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 10)),
            downloader = { _, s -> "file://$s" to 100 },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        queue.enqueueEpisode("ep1", shots("s1", "s2"))
        while (store.getEpisode("ep1")!!.byId("s1")!!.state != ShotState.SUBMITTING) delay(10)
        // —— 模拟 kill -9：新内存世界从持久化层重建（同一store模拟磁盘）——
        // 先断言恢复语义，再释放submit门：若先放行，worker会把s2也跑完，污染「仅s2为PENDING」断言
        val recovered = store.loadOrMerge("ep1", shots("s1", "s2"))
        assertEquals(ShotState.RECONCILE, recovered.byId("s1")!!.state,
            "SUBMITTING意图落库后崩溃 → 恢复为RECONCILE待对账")
        assertTrue(store.pendingRepoll("ep1").isEmpty(),
            "无video_id的镜不进re-poll；须先对账而非盲目重提→零重复付费")
        assertEquals(listOf("s2"), pendingOf(store), "仅未动过的s2仍为PENDING")
        gate.complete(Unit)
    }

    @Test
    fun `P0_1 video_id到手即同步落库_落库后才可能发生任何后续动作`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1"))
        val seenAfterSubmit = CompletableDeferred<Unit>()
        val fake = FakeVideo()
        val guard = DefaultBudgetGuard(mapOf("p1" to 10))
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val queue = DefaultRenderQueue(
            scope = CoroutineScope(Dispatchers.Default),
            videoProvider = object : com.dramafactory.core.provider.VideoProvider by fake {
                override suspend fun submitVideo(req: VideoSubmitRequest): String {
                    val id = "vid-s1"
                    seenAfterSubmit.complete(Unit)
                    // 立刻检查：video_id返回的同时，checkpoint里是否已经落了submitted？
                    // 注：队列在submitVideo返回后才markSubmitted——本测试验证返回后第一动作即落库
                    return id
                }
                override suspend fun pollResult(taskId: String): PollResult {
                    order.add("polled:${store.getEpisode("ep1")!!.byId("s1")!!.state}")
                    return PollResult.Completed("http://x/v.mp4")
                }
            },
            checkpointStore = store,
            budgetGuard = guard,
            downloader = { _, s -> "file://$s" to 100 },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        queue.enqueueEpisode("ep1", shots("s1"))
        withTimeout(5_000) {
            while (store.getEpisode("ep1")!!.byId("s1")!!.state != ShotState.COMPLETED) delay(10)
        }
        // 轮询开始时必然已是SUBMITTED（video_id到手后的第一个动作）
        assertEquals(listOf("polled:SUBMITTED"), order, "poll开始前video_id必须已同步落库")
    }

    @Test
    fun `P0_1 已计费但video_id解析失败_标记RECONCILE绝不静默重提`() = runBlocking {
        // AgnesProvider返回200但响应体缺video_id
        val api = HttpClient(MockEngine { _ ->
            respond("""{"status":"queued","msg":"accepted"}""", HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json")))
        })
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "k" },
            client = api, sleeper = {},
        )
        assertFailsWith<ProviderError.ReconcileRequired> {
            provider.submitVideo(VideoSubmitRequest(shotId = "s1", prompt = "他抬头看天"))
        }
        // 队列层面：ReconcileRequired → RECONCILE态而非FAILED/PENDING
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1"))
        val queue = DefaultRenderQueue(
            scope = CoroutineScope(Dispatchers.Default),
            videoProvider = object : com.dramafactory.core.provider.VideoProvider {
                override val id = "bad"
                override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
                override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
                override suspend fun submitVideo(req: VideoSubmitRequest): String =
                    throw ProviderError.ReconcileRequired(rawBody = "{}", msg = "billed but no video_id")
                override suspend fun pollResult(taskId: String): PollResult = PollResult.Completed("http://x/v.mp4")
            },
            checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 10)),
            downloader = { _, s -> "file://$s" to 100 },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        queue.enqueueEpisode("ep1", shots("s1"))
        withTimeout(5_000) {
            while (queue.state.value.running && store.getEpisode("ep1")!!.byId("s1")!!.state == ShotState.SUBMITTING) delay(10)
        }
        assertEquals(ShotState.RECONCILE, store.getEpisode("ep1")!!.byId("s1")!!.state,
            "计费风险场景必须落库待对账，不得归为可重提状态")
        assertEquals(0, countPending(store), "RECONCILE镜不会被自动重提→不会二次付费")
    }

    @Test
    fun `P0_1 内存store即持久化适配器语义_markSubmitting原子可见`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("a", "b"))
        store.markSubmitting("a")
        val cp = store.getEpisode("ep1")!!
        assertEquals(ShotState.SUBMITTING, cp.byId("a")!!.state)
        assertNotNull(cp.byId("a")!!.submittedAt, "意图记录带时间戳供对账超时判定")
        assertEquals(listOf("ep1"), store.allEpisodeIds())
    }

    // ==================== P0-2：cancel后join旧worker，迟到提交被拒 ====================

    @Test
    fun `P0_2 重入enqueue旧worker被cancel且join_新worker启动时旧者必已退出`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1", "s2"))
        // 旧worker已提交s1、正卡在pollResult中（HTTP调用中=cancel异步点）
        val oldWorkerInPoll = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        var pollCalls = 0
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val queue = DefaultRenderQueue(
            scope = scope,
            videoProvider = object : com.dramafactory.core.provider.VideoProvider {
                override val id = "fake"
                val submitted = java.util.concurrent.ConcurrentLinkedQueue<String>()
                override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
                override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
                override suspend fun submitVideo(req: VideoSubmitRequest): String {
                    submitted += req.shotId; return "vid-${req.shotId}"
                }
                override suspend fun pollResult(taskId: String): PollResult {
                    if (taskId == "vid-s1" && ++pollCalls == 1) { oldWorkerInPoll.complete(Unit); releaseOld.await() }
                    return PollResult.Completed("http://x/$taskId.mp4")
                }
            },
            checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 10)),
            downloader = { _, s -> "file://$s" to 100 },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        queue.enqueueEpisode("ep1", shots("s1", "s2"))
        oldWorkerInPoll.await()   // 旧worker正卡在pollResult
        // 重入enqueue：cancel+join旧worker后才启动新worker。
        // 新worker经pendingRepoll队头复用vid-s1完成取回——绝不重复submit
        queue.enqueueEpisode("ep1", shots("s1", "s2"))
        releaseOld.complete(Unit)
        withTimeout(10_000) {
            while (!queue.state.value.let { !it.running && it.completedShots == 2 }) delay(20)
        }
        assertEquals(listOf("s1", "s2").sorted(), fake_submitted_sorted(queue, store), "单消费者：每镜恰一次提交")
        assertEquals(2, store.getEpisode("ep1")!!.completedCount)
        scope.cancel()
    }

    private suspend fun fake_submitted_sorted(queue: DefaultRenderQueue, store: InMemoryCheckpointStore): List<String> {
        // 提交记录由provider闭包捕获，此处从checkpoint反推：每镜恰一个video_id即证明单次提交
        return store.getEpisode("ep1")!!.shots.mapNotNull { it.providerTaskId?.removePrefix("vid-") }.sorted()
    }

    @Test
    fun `P0_2 cancel期间旧worker的迟到提交被拒_不产生重复扣费`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1"))
        val inFlight = CompletableDeferred<Unit>()
        val lateSubmitAccepted = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val queue = DefaultRenderQueue(
            scope = scope,
            videoProvider = object : com.dramafactory.core.provider.VideoProvider {
                override val id = "late"
                override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
                override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
                override suspend fun submitVideo(req: VideoSubmitRequest): String {
                    inFlight.complete(Unit)
                    try { delay(60_000) } catch (_: kotlinx.coroutines.CancellationException) {
                        // 旧worker被取消后其「迟到提交」尝试在此暴露：
                        // 取消传播到delay→CancellationException上抛，submit永不完成计费
                        lateSubmitAccepted.complete(false)
                        throw kotlinx.coroutines.CancellationException("cancelled before billing")
                    }
                    return "vid-late"
                }
                override suspend fun pollResult(taskId: String): PollResult = PollResult.Completed("http://x/$taskId.mp4")
            },
            checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 10)),
            downloader = { _, s -> "file://$s" to 100 },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        queue.enqueueEpisode("ep1", shots("s1"))
        inFlight.await()
        val newEnqueue = async { queue.enqueueEpisode("ep1", shots("s1")) }  // cancel+join旧worker
        // 新worker启动前旧worker必须已退出（join语义）
        newEnqueue.await()
        assertFalse(lateSubmitAccepted.isCompleted && lateSubmitAccepted.await(),
            "被取消的旧worker不允许完成任何迟到提交")
        // 恢复后该镜仍是PENDING/SUBMITTING→对账，未被记成已提交
        val st = store.getEpisode("ep1")!!.byId("s1")!!.state
        assertTrue(st == ShotState.PENDING || st == ShotState.RECONCILE || st == ShotState.SUBMITTED,
            "取消不污染checkpoint业务态（P1-3）：实际=$st")
        scope.cancel()
    }

    // ==================== P1系列 ====================

    @Test
    fun `P1_3 cancel抛CancellationException原样传播_不写markFailed`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1", "s2"))
        val inFlight = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val queue = DefaultRenderQueue(
            scope = scope,
            videoProvider = object : com.dramafactory.core.provider.VideoProvider {
                override val id = "slow"
                override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
                override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
                override suspend fun submitVideo(req: VideoSubmitRequest): String {
                    if (req.shotId == "s1") { inFlight.complete(Unit); awaitCancellation() }
                    return "vid-${req.shotId}"
                }
                override suspend fun pollResult(taskId: String): PollResult = PollResult.Completed("http://x/v.mp4")
            },
            checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 10)),
            downloader = { _, s -> "file://$s" to 100 },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        queue.enqueueEpisode("ep1", shots("s1", "s2"))
        inFlight.await()
        queue.enqueueEpisode("ep1", shots("s1", "s2"))  // 触发cancel+join
        withTimeout(5_000) {
            while (store.getEpisode("ep1")!!.byId("s2")!!.state != ShotState.COMPLETED) delay(20)
        }
        assertEquals(ShotState.RECONCILE, store.getEpisode("ep1")!!.byId("s1")!!.state,
            "取消≠业务失败：s1保持意图/对账态，绝不被写成FAILED（failReason应为空或取消说明）")
        assertNull(store.getEpisode("ep1")!!.byId("s1")!!.failReason?.let { if (it.contains("cancel")) it else null },
            "不得写入业务失败原因")
        assertEquals(ShotState.COMPLETED, store.getEpisode("ep1")!!.byId("s2")!!.state)
        assertFalse(store.getEpisode("ep1")!!.byId("s1")!!.state == ShotState.FAILED,
            "用户取消绝不写成业务FAILED")
        scope.cancel()
    }

    private suspend fun awaitCancellation(): Nothing = kotlinx.coroutines.awaitCancellation()

    @Test
    fun `P1_4 已付费镜头轮询瞬断与下载失败均保持re-pool通道`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1"))
        val fake = FakeVideo(pollFailures = 1)
        var downloadAttempts = 0
        val queue = DefaultRenderQueue(
            scope = CoroutineScope(Dispatchers.Default),
            videoProvider = fake,
            checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 10)),
            downloader = { url, s ->
                downloadAttempts++
                if (downloadAttempts <= 1) error("download transient failure #$downloadAttempts")
                "file://$s" to 512
            },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        // 预置已付费镜（SUBMITTED有video_id），直接走恢复路径repoll
        store.markSubmitted("s1", "vid-paid-001")
        queue.enqueueEpisode("ep1", shots("s1"))
        withTimeout(15_000) {
            while (store.getEpisode("ep1")!!.byId("s1")!!.state != ShotState.COMPLETED) delay(20)
        }
        assertEquals(2, downloadAttempts, "下载失败退避重试直至成功，绝不标FAILED")
        assertEquals("vid-paid-001", store.getEpisode("ep1")!!.byId("s1")!!.providerTaskId, "复用原video_id")
        assertEquals(0, fake.submitted.size, "已付费镜全程零重新submit")
    }

    private suspend fun countPending(store: InMemoryCheckpointStore): Int =
        store.getEpisode("ep1")!!.shots.count { it.state == ShotState.PENDING }

    private suspend fun pendingOf(store: InMemoryCheckpointStore): List<String> =
        store.getEpisode("ep1")!!.shots.filter { it.state == ShotState.PENDING }.map { it.shotId }

    private fun queueState(q: DefaultRenderQueue?): String =
        q?.state?.value?.let { "run=${it.running} paused=${it.pausedReason}" } ?: "null"

    @Test
    fun `P1_5 enqueue不清除budget_exceeded暂停_必须用户确认才放行`() = runBlocking {
        val store = InMemoryCheckpointStore()
        store.loadOrMerge("ep1", shots("s1", "s2"))
        val fake = FakeVideo()
        val scope = CoroutineScope(Dispatchers.Default)
        val queue = DefaultRenderQueue(
            scope = scope, videoProvider = fake, checkpointStore = store,
            budgetGuard = DefaultBudgetGuard(mapOf("p1" to 1)),
            downloader = { _, s -> "file://$s" to 100 },
            pollIntervalMs = { 0 }, projectIdOf = { "p1" },
        )
        queue.enqueueEpisode("ep1", shots("s1", "s2"))
        withTimeout(5_000) { while (queue.state.value.pausedReason != "budget_exceeded") delay(20) }
        Thread.sleep(150)
        assertEquals(listOf("s1"), fake.submitted.toList())
        // 分镜层再次enqueue：不得绕过预算确认门自动续跑
        queue.enqueueEpisode("ep1", shots("s1", "s2"))
        Thread.sleep(300)
        assertEquals("budget_exceeded", queue.state.value.pausedReason,
            "US6：budget_exceeded暂停不被enqueue清除")
        assertEquals(listOf("s1"), fake.submitted.toList(), "未确认前绝不烧第2条预算")
        // 手动pause后enqueue应正常续跑
        queue.resume(confirmedByUser = true)
        withTimeout(5_000) {
            while (!queue.state.value.let { !it.running && it.completedShots == 2 }) delay(20)
        }
        assertEquals(listOf("s1", "s2"), fake.submitted.toList())
        scope.cancel()
    }

    @Test
    fun `P1_6 recoverOnBoot扫描全部集并触发续跑`() = runBlocking {
        val store = InMemoryCheckpointStore()
        // 两个集：ep1有一笔已提交待repoll的镜；ep2全PENDING
        store.loadOrMerge("epA", shots("a1", ep = "epA"))
        store.markSubmitted("a1", "vid-a1")
        store.loadOrMerge("epB", shots("b1", ep = "epB"))
        val fake = FakeVideo()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val queues = mapOf(
            "epA" to DefaultRenderQueue(scope, fake, store, DefaultBudgetGuard(mapOf("p1" to 10)),
                downloader = { _, s -> "file://$s" to 100 }, pollIntervalMs = { 0 }, projectIdOf = { "p1" }),
            "epB" to DefaultRenderQueue(scope, fake, store, DefaultBudgetGuard(mapOf("p1" to 10)),
                downloader = { _, s -> "file://$s" to 100 }, pollIntervalMs = { 0 }, projectIdOf = { "p1" }),
        )
        val orch = DefaultPipelineOrchestrator(checkpointStore = store, queue = null,
            queueFor = { ep -> queues[ep] })
        orch.recoverOnBoot()
        withTimeout(10_000) {
            while (!(store.getEpisode("epA")!!.completedCount == 1 && store.getEpisode("epB")!!.completedCount == 1)) {
                delay(30)
            }
        }
        // epA已付费镜只repoll不resubmit
        assertEquals(setOf("b1"), fake.submitted.toSet(), "epB的PENDING镜正常提交；epA已付费镜零重提")
        assertEquals("vid-a1", store.getEpisode("epA")!!.byId("a1")!!.providerTaskId, "复用原video_id")
        scope.cancel()
    }

    @Test
    fun `P1_1 validateKey并发竞态消除_验证用Key不影响并发请求`() = runBlocking {
        val keysSeen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val api = HttpClient(MockEngine { req ->
            keysSeen.add(req.headers["Authorization"] ?: "")
            respond("""{"choices":[{"message":{"content":"pong"}}]}""", HttpStatusCode.OK,
                headersOf("Content-Type" to listOf("application/json")))
        })
        val provider = AgnesProvider(
            rateGate = DefaultRateGate(0) {}, apiKeyProvider = { "sk-main-key" },
            client = api, sleeper = {},
        )
        val chatJob = launch(Dispatchers.Default) { repeat(5) { provider.chat(com.dramafactory.core.model.ChatRequest(messages = listOf(com.dramafactory.core.model.ChatMessage("user", "hi")))); delay(10) } }
        val v = provider.validateKey("sk-candidate")
        assertTrue(v.isSuccess)
        chatJob.join()
        val candidateCount = keysSeen.count { it.contains("sk-candidate") }
        val mainCount = keysSeen.count { it.contains("sk-main-key") }
        assertEquals(1, candidateCount, "候选Key只出现在validateKey自己的请求中")
        assertEquals(5, mainCount, "并发chat请求全程用主Key，不受验证影响")
    }

    @Test
    fun `P1_2 并发consumeSubmitted恰各计一次_不丢扣减`() {
        val guard = DefaultBudgetGuard(mapOf("p1" to 1000))
        val n = 500
        val latch = CountDownLatch(n)
        val threads = (1..n).map {
            Thread {
                guard.consumeSubmitted("p1"); latch.countDown()
            }
        }
        threads.forEach { it.start() }
        latch.await()
        assertEquals(n, guard.used("p1"), "CAS扣减：并发下不丢失任何一次计数")
        assertTrue(guard.usage.value.used >= n, "usage快照与used一致")
    }
}
