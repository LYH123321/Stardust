package com.aicompanion.localmodel

import android.content.Context
import android.util.Log
import com.aicompanion.util.AppLogger
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GenieX (高通 qairt/llama_cpp) 本地 LLM 引擎包装。
 *
 * 调用协议照抄 quic/ai-hub-apps geniex_chat_android 官方示例：
 *  - LlmWrapper.builder().llmCreateInput(LlmCreateInput(...)).build() → Result<LlmWrapper>
 *  - applyChatTemplate(messages, tools, enableThinking) → 格式化后的 prompt
 *  - generateStreamFlow(prompt, GenerationConfig) → Flow<LlmStreamResult>
 *
 * 注意（官方源码注释）：
 *  - runtime_id="qairt" 时必须 nCtx=0 / nGpuLayers=0（QAIRT 在编译期固定，非零会被拒绝）
 *  - runtime_id="llama_cpp" 支持 CPU/GPU/NPU per-tensor 混合调度，mmap 分层加载
 */
object GenieXEngine {
    private const val TAG = "GenieXEngine"

    @Volatile
    private var wrapper: LlmWrapper? = null

    /** 当前加载的模型目录（File），null 表示未加载 */
    @Volatile
    var loadedModelDir: File? = null
        private set

    /** 生成中标志，防止并发 generate 在原生句柄上竞争 */
    @Volatile
    var isGenerating: Boolean = false
        private set

    val isLoaded: Boolean get() = wrapper != null

    /**
     * 加载模型。
     * @param modelDir 包含 *.gguf 的模型目录
     * @param computeUnit "npu" / "gpu" / "cpu" / "hybrid"
     */
    suspend fun load(
        context: Context,
        modelDir: File,
        computeUnit: String = "npu",
        enableThinking: Boolean = false,
        nCtx: Int = 1024,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            unload()
            val gguf = modelDir.listFiles { f -> f.extension == "gguf" }
                ?.maxByOrNull { it.length() }
                ?: error("目录里没有 .gguf: ${modelDir.absolutePath}")
            // tokenizer 就是 gguf 内嵌的（Qwen3 GGUF），官方 demo 对 GGUF 也传同路径
            val isQairt = false  // 星尘统一走 llama_cpp（支持 mmap 分层 + 混合算力），qairt 需要专用签名模型
            val conf = if (isQairt) {
                ModelConfig(nCtx = 0, nGpuLayers = 0, enable_thinking = enableThinking)
            } else {
                ModelConfig(nCtx = nCtx, nGpuLayers = 0, enable_thinking = enableThinking)
            }
            val input = LlmCreateInput(
                model_name = modelDir.name,
                model_path = gguf.absolutePath,
                tokenizer_path = gguf.absolutePath,
                config = conf,
                runtime_id = "llama_cpp",
                compute_unit = when (computeUnit) {
                    "gpu" -> ComputeUnitValue.GPU.value
                    "cpu" -> ComputeUnitValue.CPU.value
                    "hybrid" -> ComputeUnitValue.HYBRID.value
                    else -> ComputeUnitValue.NPU.value
                },
            )
            AppLogger.w(TAG, "GenieX load: ${gguf.name} unit=$computeUnit ctx=$nCtx")
            val w = LlmWrapper.builder().llmCreateInput(input).build().getOrThrow()
            wrapper = w
            loadedModelDir = modelDir
            AppLogger.w(TAG, "GenieX loaded OK")
        }.onFailure { e ->
            AppLogger.e(TAG, "GenieX load failed: ${e.message}", e)
            Log.e(TAG, "load failed", e)
        }
    }

    /**
     * 流式生成。返回 (完整文本, 出错信息?)。
     * history 为 (是否用户, 内容) 列表。
     */
    suspend fun chatStream(
        systemPrompt: String,
        history: List<Pair<Boolean, String>>,
        userMessage: String,
        maxTokens: Int = 512,
        onDelta: ((String) -> Unit)? = null,
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        val w = wrapper ?: return@withContext "" to "模型未加载"
        if (isGenerating) return@withContext "" to "上一轮还在生成中"
        isGenerating = true
        try {
            val msgs = buildList {
                add(ChatMessage("system", systemPrompt))
                history.takeLast(10).forEach { (isUser, text) ->
                    add(ChatMessage(if (isUser) "user" else "assistant", text))
                }
                add(ChatMessage("user", userMessage))
            }
            val template = w.applyChatTemplate(msgs.toTypedArray(), "[]", false).getOrThrow().formattedText
            val genCfg = com.geniex.sdk.bean.GenerationConfig(maxTokens = maxTokens)
            val sb = StringBuilder()
            var err: String? = null
            w.generateStreamFlow(template, genCfg).collect { r ->
                when (r) {
                    is LlmStreamResult.Token -> {
                        sb.append(r.text)
                        onDelta?.invoke(sb.toString())
                    }
                    is LlmStreamResult.Error -> {
                        err = r.throwable.message ?: "生成失败"
                        AppLogger.e(TAG, "GenieX stream error: $err")
                    }
                    is LlmStreamResult.Completed -> {
                        AppLogger.w(TAG, "GenieX stats: ttft=${r.profile.ttftMs}ms decode=${r.profile.decodingSpeed}tok/s")
                    }
                }
            }
            sb.toString() to err
        } catch (e: Throwable) {
            AppLogger.e(TAG, "GenieX chat error: ${e.message}", e)
            "" to (e.message ?: "生成异常")
        } finally {
            isGenerating = false
        }
    }

    fun unload() {
        try {
            wrapper?.close()
        } catch (_: Throwable) {}
        wrapper = null
        loadedModelDir = null
    }
}
