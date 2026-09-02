package com.dramafactory.app.ui

import com.dramafactory.app.update.UpdateResult
import com.dramafactory.core.provider.VideoProvider
import com.dramafactory.core.storage.InMemoryKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SettingsLogic 更新通道状态机 JVM 单测（纯逻辑，不触网、不依赖 Android）。
 * 通过注入式 checker 验证：检查中 → 有更新/已最新/失败 的 StateFlow 转换。
 */
class SettingsLogicUpdateTest {

    private fun newLogic() = SettingsLogic(
        videoProviderFor = { object : VideoProvider {
            override val id = it
            override suspend fun validateKey(key: String) =
                Result.success(com.dramafactory.core.model.ConnectionInfo(ok = true, latencyMs = 1, detail = ""))
            override fun listModels() = emptyList<com.dramafactory.core.model.ModelSpec>()
            override suspend fun submitVideo(req: com.dramafactory.core.model.VideoSubmitRequest) = "t_${req.shotId}"
            override suspend fun pollResult(providerTaskId: String) =
                com.dramafactory.core.model.PollResult.Completed("u")
        } },
        configIdFor = { "cfg-$it" },
        keyVault = InMemoryKeyVault(),
        configId = "cfg-agnes",
        activate = {},
        io = Dispatchers.IO,
    )

    @Test
    fun `checkUpdate 发现新版本 置 updateAvailable 且持续提示`() = runTest {
        val logic = newLogic()
        logic.checkUpdate { UpdateResult.UpdateAvailable("1.9.4", 94, "https://x.apk", "升级说明") }
        val s = logic.state.value
        assertEquals("1.9.4", s.updateAvailable?.versionName)
        assertEquals(94, s.updateAvailable?.versionCode)
        assertEquals("https://x.apk", s.updateAvailable?.downloadUrl)
        assertEquals("升级说明", s.updateAvailable?.notes)
        assertEquals(false, s.updateLatest)
        assertNull(s.updateError)
    }

    @Test
    fun `checkUpdate 已是最新 置 updateLatest`() = runTest {
        val logic = newLogic()
        logic.checkUpdate { UpdateResult.Latest("1.9.3") }
        val s = logic.state.value
        assertTrue(s.updateLatest)
        assertNull(s.updateAvailable)
    }

    @Test
    fun `checkUpdate 失败 置 updateError`() = runTest {
        val logic = newLogic()
        logic.checkUpdate { UpdateResult.Error("网络超时") }
        val s = logic.state.value
        assertEquals("网络超时", s.updateError)
        assertNull(s.updateAvailable)
    }

    @Test
    fun `checkUpdate 开始时清空旧状态`() = runTest {
        val logic = newLogic()
        logic.checkUpdate { UpdateResult.UpdateAvailable("1.9.4", 94, "https://x.apk") }
        // 再次检查（假设失败），确认旧 updateAvailable 被清空
        logic.checkUpdate { UpdateResult.Error("x") }
        assertNull(logic.state.value.updateAvailable)
        assertEquals("x", logic.state.value.updateError)
    }

    @Test
    fun `dismissUpdate 清除提示`() = runTest {
        val logic = newLogic()
        logic.checkUpdate { UpdateResult.UpdateAvailable("1.9.4", 94, "https://x.apk") }
        logic.dismissUpdate()
        val s = logic.state.value
        assertNull(s.updateAvailable)
        assertEquals(false, s.updateLatest)
        assertNull(s.updateError)
    }
}
