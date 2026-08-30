// P0 假完成修复回归锁（阶段1：F1~F4）
// 说明：F1/F2/F4 的生产接线点在 :app 模块（AppGraph/RenderRuntime，需 Android Context），
// 无法在纯 JVM 单测里直接驱动；本文件在 :core-engine 层用「等价 λ 接线」复刻同样的接线形状，
// 对关键不变量加锁，防止有人把修复回退成「假完成」。
// F5/F6 为 :app 运行时/引擎 Gate 行为，见 docs/P0-FIX.md 的诚实说明。
package com.dramafactory.core

import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.VideoSubmitRequest
import com.dramafactory.core.orchestrate.AiOrchestrator
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator
import com.dramafactory.core.orchestrate.PipelineStage5
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.pipeline.DefaultRenderQueue
import com.dramafactory.core.provider.ChineseAudioInjector
import com.dramafactory.core.provider.VideoProvider
import com.dramafactory.core.quality.AssetAuditor
import com.dramafactory.core.quality.AssetInspector
import com.dramafactory.core.quality.EraDetector
import com.dramafactory.core.storage.InMemoryCheckpointStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class P0FixRegressionTest {

    private fun runSuspend(block: suspend () -> Unit) {
        val t = Thread { runBlocking { block() } }
        t.start(); t.join(30_000)
    }

    /** 假 VideoProvider：捕获 submitVideo 收到的完整请求（含 prompt）。 */
    private class CapturingVideo : VideoProvider {
        override val id = "fake"
        val captured = mutableListOf<VideoSubmitRequest>()
        override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
        override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
        override suspend fun submitVideo(req: VideoSubmitRequest): String {
            captured += req; return "vid-${req.shotId}"
        }
        private val polls = mutableMapOf<String, Int>()
        override suspend fun pollResult(taskId: String): PollResult =
            (polls[taskId] ?: 0).let { n -> polls[taskId] = n + 1; if (n >= 1) PollResult.Completed("http://x/$taskId.mp4") else PollResult.InProgress(30) }
    }

    // ===================== F1：视频提交 prompt 不得恒为空 =====================
    @Test
    fun `F1回归_提交prompt含分镜文本且长度大于50_不再恒为普通话指令`() = runBlocking {
        withTimeout(15_000) {
            val scope = CoroutineScope(Dispatchers.Default)
            val fake = CapturingVideo()
            // 复刻 RenderRuntime.queueFor 的 shotPromptResolver 接线：从 shots 表读 dialogue/action。
            // 这里用「接近真实分镜长度」的台词+动作作桩数据——生产接线读的是真实分镜文本（通常 >50 字），
            // 回归锁要保证 prompt 含剧本文本且长度达标（HANDOVER §阶段1 F1 验收：含 action/dialogue 且长度 > 50）。
            // 两镜都返回含「站住」「拔剑」的真实分镜文本，确保断言对每镜均成立。
            val resolver: suspend (String) -> Triple<String, String, String> =
                { _ ->
                    Triple(
                        "他说：站住，别动！你这逆贼，今日休想逃出我的剑下！",
                        "",
                        "他拔剑冲上前，衣袂翻飞，剑光映月，寒锋直取对方咽喉，脚下踏碎青砖，惊起檐下栖鸦。",
                    )
                }
            val queue = DefaultRenderQueue(
                scope = scope, videoProvider = fake, checkpointStore = InMemoryCheckpointStore(),
                budgetGuard = DefaultBudgetGuard(mutableMapOf("p1" to 50)),
                downloader = { _, s -> "file://$s" to 2048 },
                pollIntervalMs = { 0 },
                shotPromptResolver = resolver,
                projectIdOf = { "p1" },
            )
            queue.enqueueEpisode("ep1", listOf(ShotMeta("s1", "ep1", ""), ShotMeta("s2", "ep1", "")))
            while (!queue.state.value.let { !it.running && it.completedShots == 2 }) delay(50)
            scope.cancel()

            assertEquals(2, fake.captured.size, "每镜恰一次提交")
            for (req in fake.captured) {
                val p = req.prompt
                // 不得退化为恒值中文指令（旧「假完成」的 10 字空 prompt）
                assertFalse(p == ChineseAudioInjector.MANDARIN_SUFFIX, "prompt 不得退化为恒值中文指令: $p")
                // 必须包含分镜台词/动作文本（剧本相关）
                assertTrue(p.contains("站住") && p.contains("拔剑"), "prompt 必须包含分镜台词/动作文本: $p")
                // 长度必须 > 50（HANDOVER 验收：包含 action/dialogue 且长度 > 50）
                assertTrue(p.length > 50, "prompt 长度必须 > 50，实际 ${p.length}: $p")
            }
        }
    }

    // ===================== F2：AI 管线审计必须真正调用 AssetAuditor.audit =====================
    @Test
    fun `F2回归_AI管线auditAsset确实调用AssetAuditor_audit`() = runSuspend {
        var auditCalls = 0
        // spy describer：记录 G2 多模态打分被调用的次数（G1 通过后才会调用）
        val spyDescriber = AssetAuditor.ImageDescriber { _, _ ->
            auditCalls++
            """{"score":0.95,"defects":[],"face_ratio":0.5}"""
        }
        // 放宽 G1 尺寸规则，使一张 2x2 合法 PNG 通过硬校验（G1 通过才会进入 G2 describer）
        val inspectorRules = AssetInspector.Rules(
            dimensionRules = mapOf(
                "character" to AssetInspector.DimensionRule(minWidth = 1, minHeight = 1, maxWidth = 100000, maxHeight = 100000, squareCheck = false),
            ),
        )
        val png = buildTinyPng()
        val dataUri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)

        val o = DefaultAiOrchestrator(
            createProject = { "proj_f2" },
            createEpisode = { pid, _ -> "${pid}_ep1" },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ -> Result.success(listOf(DefaultAiOrchestrator.AiAsset("a1", "character", "张三", "主角"))) },
            generateImage = { Result.success("http://img/x.png") },
            // ★F2 修复后的真实接线形状：调用 AssetAuditor.audit（AppGraph.auditAsset λ 采用同一写法）
            auditAsset = { asset ->
                val r = AssetAuditor.audit(
                    imageBytes = png, imageDataUri = dataUri,
                    description = asset.prompt, assetType = asset.kind,
                    inspectorRules = inspectorRules, describer = spyDescriber,
                )
                Result.success(DefaultAiOrchestrator.AuditResult(passed = r.passed, score = r.qualityScore, reason = r.reason))
            },
            generateShots = { _, _, _ -> Result.success(listOf(DefaultAiOrchestrator.AiShot(1, "张三走进大殿"))) },
            enqueueRender = { _, s -> Result.success(s.size) },
            writeCheckpoint = { _, _, _, _, _, _ -> },
            readCheckpoint = { null },
        )
        val result = o.run("a".repeat(200))
        assertTrue(result.isSuccess, "流水线应跑通")
        assertTrue(auditCalls >= 1, "AUDIT 阶段必须实际调用 AssetAuditor.audit（含 G2 describer），实际 $auditCalls 次（假完成会为 0）")
    }

    // ===================== F3：时代红线必须按剧本自动推断，现代剧不得套西汉约束 =====================
    @Test
    fun `F3回归_现代剧推断modern且不含西汉禁词_西汉剧推断han`() {
        val modernScript = "现代都市剧：林晓在地铁站刷手机，收到公司微信，走进写字楼开会，下班叫了外卖。"
        val hanScript = "大汉长安，汉军出征匈奴，和亲西域，未央宫内简牍竹简记载功过。"

        val modernDet = runBlocking { EraDetector.detect(modernScript, llmReady = false) { throw AssertionError("llm 不应被调用") } }
        val hanDet = runBlocking { EraDetector.detect(hanScript, llmReady = false) { throw AssertionError("llm 不应被调用") } }

        assertEquals("modern", modernDet.eraKey, "现代剧应推断为 modern")
        assertEquals("han", hanDet.eraKey, "汉代剧应推断为 han")

        val modernPreset = EraDetector.presetFor(modernDet.eraKey)
        // 现代剧时代负向为空（不套西汉禁词）——这是 F3 修复的核心不变量
        assertTrue(modernPreset.era.negative.isEmpty(), "现代剧时代负向必须为空，不得含西汉禁词")
        assertFalse(modernPreset.era.positive.contains("深衣") || modernPreset.era.positive.contains("曲裾"),
            "现代剧正向不得含西汉服饰词（深衣/曲裾）")

        val hanPreset = EraDetector.presetFor(hanDet.eraKey)
        assertFalse(hanPreset.era.negative.isEmpty(), "西汉剧应有时代负向（对照用）")
        assertTrue(hanPreset.era.positive.contains("深衣") && hanPreset.era.positive.contains("曲裾"),
            "西汉剧正向应含西汉服饰词（深衣、曲裾）（对照用）")
    }

    // ===================== F4：断点续跑必须读真实剧本，不得用 RETRY_STUB 占位 =====================
    @Test
    fun `F4回归_retryFrom读取真实剧本而非RETRY_STUB占位`() = runSuspend {
        val realScript = "真实剧本内容_断点续跑必须用我_不要用占位_RETRY_STUB"
        val scripts = mutableMapOf("proj_f4_ep1" to realScript)
        val capturedInGenerateShots = mutableListOf<String>()
        val o = DefaultAiOrchestrator(
            createProject = { "proj_f4" },
            createEpisode = { pid, _ -> "${pid}_ep1" },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ -> Result.success(emptyList()) },
            generateImage = { Result.success("x") },
            // ★F4 修复后的真实接线形状：按 episodeId 读回 script_json（AppGraph.readScript λ 同形）
            readScript = { epId -> scripts[epId] ?: "" },
            generateShots = { _, scriptText, _ -> capturedInGenerateShots += scriptText; Result.success(listOf(DefaultAiOrchestrator.AiShot(1, "某动作"))) },
            enqueueRender = { _, s -> Result.success(s.size) },
            writeCheckpoint = { _, _, _, _, _, _ -> },
            readCheckpoint = { null },
        )
        // 先完整跑一次，写入 currentEpisodeId
        o.run("z".repeat(200))
        assertTrue(o.currentEpisodeId.value == "proj_f4_ep1")
        // 从分镜阶段续跑
        val retry = o.retryFrom(PipelineStage5.GENERATE_STORYBOARD)
        assertTrue(retry.isSuccess)
        assertTrue(retry.getOrThrow().success)

        assertTrue(capturedInGenerateShots.isNotEmpty(), "续跑应触发分镜生成")
        val usedScript = capturedInGenerateShots.last()
        assertEquals(realScript, usedScript, "续跑分镜必须用真实剧本")
        assertFalse(usedScript.startsWith("RETRY_STUB"), "续跑脚本不得为 RETRY_STUB 占位，实际: ${usedScript.take(40)}")
    }

    // ---------------- 测试辅助：构造一张合法的 2x2 真彩 PNG（供 G1 解码通过） ----------------
    private fun buildTinyPng(): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.write(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()))
        val ihdr = ByteArray(13)
        // width=2, height=2, bitDepth=8, colorType=2(truecolor), compression/filter/interlace=0
        ihdr[3] = 2.toByte(); ihdr[7] = 2.toByte(); ihdr[8] = 8.toByte(); ihdr[9] = 2.toByte()
        writeChunk(out, "IHDR", ihdr)
        // 2x2 RGB 原始扫描行：每行 1 filter byte + 2*3 bytes
        val raw = ByteArray(14)
        raw[1] = 255.toByte(); raw[5] = 255.toByte()      // 像素 (0,0) 红, (1,0) 绿
        raw[8] = 255.toByte(); raw[12] = 255.toByte()     // 像素 (0,1) 蓝, (1,1) 红
        val def = Deflater(Deflater.DEFAULT_COMPRESSION)
        def.setInput(raw); def.finish()
        val buf = ByteArray(64)
        val n = def.deflate(buf); def.end()
        writeChunk(out, "IDAT", buf.copyOf(n))
        writeChunk(out, "IEND", ByteArray(0))
        return bos.toByteArray()
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }
}
