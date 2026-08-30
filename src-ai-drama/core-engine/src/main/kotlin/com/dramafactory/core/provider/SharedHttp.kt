package com.dramafactory.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/**
 * 进程级共享 HttpClient。
 *
 * 背景（实测问题）：DeepSeekProvider / AgnesProvider 的 `client` 默认参数是 `HttpClient { }`，
 * 也就是**每次构造都新建一个 OkHttp 引擎**——独占 dispatcher 线程池 + 连接池，且无人 close。
 * 而 Provider 在生产路径上是「按次调用」创建的：
 *   - AppGraph.textProviderFor()（每次资产提取 / 分镜生成 / 每轮 AI 对话各一次）
 *   - TextModelRouter.validateKey()（每次点「测试连通」）
 *   - TextModelRouter.resolve()（每次解析激活模型）
 * 于是每问一句 AI 就泄漏一个永不回收的连接池，长时间会话下线程与句柄持续增长。
 *
 * 这里提供共享实例作为新的默认值：Provider 仍支持注入 client（测试用 MockEngine），
 * 生产路径不再逐个泄漏。全仓无 `client.close()` 调用，共享实例不会被误关。
 */
object SharedHttp {
    val client: HttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HttpClient {
            install(HttpTimeout) {
                // 视频/图像生成是长任务，超时给宽；连接阶段保持严格
                connectTimeoutMillis = 30_000
                requestTimeoutMillis = 180_000
                socketTimeoutMillis = 180_000
            }
        }
    }
}
