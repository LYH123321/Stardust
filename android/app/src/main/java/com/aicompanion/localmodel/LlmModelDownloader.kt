package com.aicompanion.localmodel

import android.content.Context
import com.aicompanion.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 本地聊天 LLM 模型条目（用户可在 JSON 里自行追加） */
data class LlmModelEntry(
    val id: String,
    val name: String,
    val repo: String,        // HF repo，如 unsloth/Qwen3-0.6B-GGUF
    val file: String,        // 文件名，如 Qwen3-0.6B-Q4_0.gguf
    val sizeBytes: Long,
    val description: String = "",
)

object LlmModelRegistry {
    // 内置目录：只放最小测试模型，后续用户自己加
    val models = listOf(
        LlmModelEntry(
            id = "qwen3-06b-q4",
            name = "Qwen3 0.6B (Q4_0, ~520MB)",
            repo = "unsloth/Qwen3-0.6B-GGUF",
            file = "Qwen3-0.6B-Q4_0.gguf",
            sizeBytes = 545_259_520L,
            description = "最小测试模型，链路验证用",
        ),
    )

    fun find(id: String): LlmModelEntry? = models.find { it.id == id }

    fun modelDir(context: Context, entry: LlmModelEntry): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "llm_models/${entry.id}")
}

/**
 * 从 hf-mirror.com 下载模型权重（国内直连，无需代理）。
 * 下载到 <ext>/llm_models/<id>/<file>.downloading，完成后原子重命名。
 */
class LlmModelDownloader(private val context: Context) {
    companion object {
        private const val TAG = "LlmModelDownloader"
        private const val BASE = "https://hf-mirror.com"
    }

    @Volatile
    private var cancelled = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun cancel() { cancelled = true }

    fun isDownloaded(entry: LlmModelEntry): Boolean =
        LlmModelRegistry.modelDir(context, entry).let { d ->
            File(d, entry.file).let { it.exists() && it.length() == entry.sizeBytes }
        }

    /**
     * 下载模型。onProgress(已下载字节, 总字节)。返回成功与否。
     */
    suspend fun download(
        entry: LlmModelEntry,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        cancelled = false
        val dir = LlmModelRegistry.modelDir(context, entry)
        dir.mkdirs()
        val finalFile = File(dir, entry.file)
        if (isDownloaded(entry)) return@withContext true
        val tmpFile = File(dir, entry.file + ".downloading")
        val url = "$BASE/${entry.repo}/resolve/main/${entry.file}"
        AppLogger.w(TAG, "download start: $url")
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLogger.e(TAG, "HTTP ${resp.code} for $url")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val total = body.contentLength().let { if (it > 0) it else entry.sizeBytes }
                var downloaded = if (tmpFile.exists()) tmpFile.length() else 0L
                val req2 = if (downloaded > 0) {
                    Request.Builder().url(url).header("Range", "bytes=$downloaded-").build()
                } else request
                val source = if (downloaded > 0) {
                    client.newCall(req2).execute().use { r2 ->
                        if (r2.code == 206) saveStream(r2.body!!, tmpFile, downloaded, total, onProgress)
                        else if (r2.code == 200) saveStream(r2.body!!, tmpFile, 0, total, onProgress)
                        else return@withContext false
                    }
                } else {
                    saveStream(body, tmpFile, 0, total, onProgress)
                }
            }
            if (cancelled) {
                AppLogger.w(TAG, "download cancelled")
                return@withContext false
            }
            // 校验大小（容差 1%）
            val okSize = tmpFile.length() >= entry.sizeBytes * 0.99
            if (!okSize) {
                AppLogger.e(TAG, "size mismatch: ${tmpFile.length()} vs ${entry.sizeBytes}")
                return@withContext false
            }
            tmpFile.renameTo(finalFile)
            AppLogger.w(TAG, "download complete: ${finalFile.length()} bytes")
            true
        } catch (e: Throwable) {
            AppLogger.e(TAG, "download failed: ${e.message}", e)
            false
        }
    }

    private fun saveStream(
        body: okhttp3.ResponseBody,
        tmpFile: File,
        startAt: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        var downloaded = startAt
        body.byteStream().use { input ->
            java.io.FileOutputStream(tmpFile, startAt > 0).use { out ->
                val buf = ByteArray(256 * 1024)
                var lastEmit = 0L
                while (true) {
                    if (cancelled) return false
                    val n = input.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 500) {
                        lastEmit = now
                        onProgress(downloaded, total)
                    }
                }
                out.flush()
            }
        }
        onProgress(downloaded, total)
        return true
    }

    fun delete(entry: LlmModelEntry): Boolean {
        val dir = LlmModelRegistry.modelDir(context, entry)
        return dir.deleteRecursively()
    }
}