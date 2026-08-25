// 推理服务层：InferenceEngine 接口 + llama.cpp JNI桥桩 —— 架构§1.2/§3
// 真实native库(libllama-android.so)后续接入；当前为可编译的空实现
package com.agentteam.core.infer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 引擎接口（架构§3 object签名改为interface以便测试注入Mock） */
interface InferenceEngine {
    fun loadModel(path: String, ctxTokens: Int, threads: Int): Boolean
    /** 流式token输出 */
    fun completion(prompt: String, maxTokens: Int = 1024, temperature: Float = 0.7f): Flow<String>
    fun stop()
    fun unload()
    val isLoaded: Boolean
}

/** JNI薄封装声明：external fun 与 C++ 侧 libllama-android.so 对应（架构§1.2第2条） */
object LlamaJni {
    external fun loadModel(path: String, ctxTokens: Int, threads: Int): Long   // 返回模型句柄，<0失败
    external fun startCompletion(handle: Long, prompt: String, maxTokens: Int, temperature: Float): Int
    external fun nextToken(handle: Long): String?    // 流式逐token拉取，null=结束
    external fun stopCompletion(handle: Long)
    external fun freeModel(handle: Long)
}

/**
 * 真实JNI实现桩：所有调用转发到LlamaJni。
 * native库未接入时 loadModel 返回 false，completion 返回空Flow——上层按 MODEL_ERROR 降级。
 */
class JniLlamaEngine : InferenceEngine {
    private var handle = -1L

    override val isLoaded get() = handle >= 0

    override fun loadModel(path: String, ctxTokens: Int, threads: Int): Boolean {
        unload()
        handle = try { LlamaJni.loadModel(path, ctxTokens, threads) } catch (e: UnsatisfiedLinkError) { -1 }
        return handle >= 0
    }

    override fun completion(prompt: String, maxTokens: Int, temperature: Float): Flow<String> {
        if (!isLoaded) return emptyFlow()   // 桩：无模型时空流
        return kotlinx.coroutines.flow.flow {
            LlamaJni.startCompletion(handle, prompt, maxTokens, temperature)
            while (true) {
                val tok = try { LlamaJni.nextToken(handle) } catch (e: Exception) { null }
                if (tok == null) break
                emit(tok)
            }
        }
    }

    override fun stop() { if (isLoaded) runCatching { LlamaJni.stopCompletion(handle) } }
    override fun unload() { if (handle >= 0) runCatching { LlamaJni.freeModel(handle) }; handle = -1 }
}

/** 测试用确定性引擎：回显固定文本，供单元测试驱动全链路 */
class FakeEngine(private val canned: String = "FAKE_OUTPUT") : InferenceEngine {
    var loaded = false
    var lastPrompt: String? = null
    override val isLoaded get() = loaded
    override fun loadModel(path: String, ctxTokens: Int, threads: Int): Boolean { loaded = true; return true }
    override fun completion(prompt: String, maxTokens: Int, temperature: Float): Flow<String> {
        lastPrompt = prompt
        return kotlinx.coroutines.flow.flowOf(canned)
    }
    override fun stop() {}
    override fun unload() { loaded = false }
}
