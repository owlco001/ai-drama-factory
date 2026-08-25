package com.dramafactory.app

import android.content.Context
import com.dramafactory.app.data.DramaDatabase
import com.dramafactory.app.data.RoomCheckpointStore
import com.dramafactory.app.security.AndroidKeyVault
import com.dramafactory.core.pipeline.DefaultBudgetGuard
import com.dramafactory.core.provider.AgnesProvider
import com.dramafactory.core.provider.BudgetGuard
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.KeyVault
import com.dramafactory.core.provider.TextProvider
import com.dramafactory.core.provider.ImageProvider

/**
 * App级依赖图 —— UI/ViewModel/Service共享的引擎单例。
 *
 * 三通道MVP均由AgnesProvider承担（Video+Text+Image三实现合一，Q6通道独立但供应商同一）。
 * CheckpointStore为Room持久化版（P0-1生死线语义见RoomCheckpointStore）。
 */
object AppGraph {

    const val CONFIG_VIDEO = "agnes-video"    // configId：视频通道Key
    const val CONFIG_TEXT = "agnes-text"
    const val CONFIG_IMAGE = "agnes-image"

    lateinit var keyVault: KeyVault; private set
    lateinit var checkpointStore: CheckpointStore; private set
    lateinit var agnes: AgnesProvider; private set
    val video get() = agnes            // VideoProvider
    val text: TextProvider get() = agnes
    val image: ImageProvider get() = agnes
    lateinit var budgetGuard: DefaultBudgetGuard; private set
    lateinit var dao: com.dramafactory.app.data.DramaDao; private set

    @Volatile private var initialized = false

    /** Application.onCreate调用一次；重复调用幂等（ContentProvider/测试环境容错） */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            keyVault = AndroidKeyVault(context.applicationContext)
            val db = DramaDatabase.get(context.applicationContext)
            dao = db.dao()
            checkpointStore = RoomCheckpointStore(dao)
            // 明文Key仅经apiKeyProvider进Authorization header，永不回显UI
            agnes = AgnesProvider(apiKeyProvider = { keyVault.load(CONFIG_VIDEO) })
            budgetGuard = DefaultBudgetGuard()
            initialized = true
        }
    }
}
