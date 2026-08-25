package com.dramafactory.app

import android.app.Application
import com.dramafactory.app.ui.RenderRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application：初始化AppGraph依赖图 + 绑定RenderRuntime队列作用域 + 开机恢复续跑。
 */
class DramaApplication : Application() {

    /** App级作用域：SupervisorJob保证子协程异常不互相拖垮 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // 队列协程作用域接线（DefaultRenderQueue worker在此scope内运行）
        RenderRuntime.bindScope(appScope)
        // P1-6进程重启恢复：读checkpoint → repoll已提交镜 → 续跑队列（绝不重复扣费）
        appScope.launch { runCatching { RenderRuntime.recoverOnBoot() } }
    }
}
