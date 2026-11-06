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
    val repo: String,        // HF repo，如 unsloth/Qwen3-0.6B-GGUF；本地导入的为空
    val file: String,        // 文件名，如 Qwen3-0.6B-Q4_0.gguf
    val sizeBytes: Long,
    val description: String = "",
    val localOnly: Boolean = false,  // true = 用户本地导入，无需下载
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
        LlmModelEntry(
            id = "qwen3-17b-q4",
            name = "Qwen3 1.7B (Q4_0, ~1.0GB)",
            repo = "unsloth/Qwen3-1.7B-GGUF",
            file = "Qwen3-1.7B-Q4_0.gguf",
            sizeBytes = 1_056_782_912L,
            description = "轻量日常聊天，速度飞快，内存占用最低",
        ),
        LlmModelEntry(
            id = "ministral-3b-q4",
            name = "Ministral 3B Instruct (Q4_0, ~1.9GB)",
            repo = "unsloth/Ministral-3-3B-Instruct-2512-GGUF",
            file = "Ministral-3-3B-Instruct-2512-Q4_0.gguf",
            sizeBytes = 2_046_375_200L,
            description = "日常聊天主力推荐，质量/速度/发热均衡",
        ),
        LlmModelEntry(
            id = "gemma4-e2b-qat",
            name = "Gemma 4 E2B (QAT Q4_0, ~3.1GB)",
            repo = "google/gemma-4-E2B-it-qat-q4_0-gguf",
            file = "gemma-4-E2B_q4_0-it.gguf",
            sizeBytes = 3_349_516_256L,
            description = "Google 官方 QAT 量化，NPU 亲和度最高",
        ),
        LlmModelEntry(
            id = "gemma4-e4b-qat",
            name = "Gemma 4 E4B (QAT Q4_0, ~4.8GB)",
            repo = "google/gemma-4-E4B-it-qat-q4_0-gguf",
            file = "gemma-4-E4B_q4_0-it.gguf",
            sizeBytes = 5_154_941_280L,
            description = "4B 档质量最好，QAT 精度损失最小",
        ),
        LlmModelEntry(
            id = "qwen3-14b-q4",
            name = "Qwen3 14B (Q4_0, ~8.0GB)",
            repo = "unsloth/Qwen3-14B-GGUF",
            file = "Qwen3-14B-Q4_0.gguf",
            sizeBytes = 8_543_001_984L,
            description = "本机上限档，贴物理内存运行；加载慢、发热明显，慎用",
        ),
    )

    fun find(id: String): LlmModelEntry? = models.find { it.id == id }

    fun modelDir(context: Context, entry: LlmModelEntry): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "llm_models/${entry.id}")

    /** 解析条目对应的 gguf 文件：优先 App 目录，找不到则找 Download 里的同名文件（扫描登记的） */
    fun modelFile(context: Context, entry: LlmModelEntry): File? {
        val inApp = File(modelDir(context, entry), entry.file)
        if (inApp.exists()) return inApp
        val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        listOf(dl, File(dl, "Live2D")).forEach { root ->
            val f = File(root, entry.file)
            if (f.exists()) return f
        }
        return null
    }

    // ===== 用户本地导入的自定义模型（JSON 持久化）=====
    private const val PREFS_NAME = "local_llm_prefs"
    private const val KEY_CUSTOM = "custom_models"

    fun customModels(context: Context): List<LlmModelEntry> = try {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString(KEY_CUSTOM, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LlmModelEntry(
                id = o.optString("id"),
                name = o.optString("name"),
                repo = o.optString("repo", ""),
                file = o.optString("file"),
                sizeBytes = o.optLong("sizeBytes"),
                description = o.optString("description", "本地导入"),
                localOnly = true,
            )
        }.filter { it.id.isNotBlank() && it.file.isNotBlank() }
    } catch (_: Throwable) { emptyList() }

    fun allModels(context: Context): List<LlmModelEntry> = models + customModels(context)

    fun find(context: Context, id: String): LlmModelEntry? = allModels(context).find { it.id == id }

    fun addCustom(context: Context, entry: LlmModelEntry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString(KEY_CUSTOM, "[]") ?: "[]")
        arr.put(org.json.JSONObject().apply {
            put("id", entry.id); put("name", entry.name); put("repo", entry.repo)
            put("file", entry.file); put("sizeBytes", entry.sizeBytes); put("description", entry.description)
        })
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    fun removeCustom(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val kept = customModels(context).filter { it.id != id }
        val arr = org.json.JSONArray()
        kept.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("id", e.id); put("name", e.name); put("repo", e.repo)
                put("file", e.file); put("sizeBytes", e.sizeBytes); put("description", e.description)
            })
        }
        prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }
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

    /**
     * 从 SAF Uri 导入 .gguf（拷贝到 App 外部存储 llm_models/import_<n>/）。
     * 返回登记好的条目；失败返回 null。
     */
    fun importFromUri(uri: android.net.Uri, displayName: String?): LlmModelEntry? {
        return try {
            val resolver = context.contentResolver
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size <= 0) return null
            var n = 0
            var dir: File
            do { dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "llm_models/import_${n++}") } while (dir.exists())
            dir.mkdirs()
            val name = (displayName ?: "model.gguf").ifBlank { "model.gguf" }
            val dest = File(dir, name)
            resolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(dest).use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: return null
            val entry = LlmModelEntry(
                id = dir.name,
                name = name.removeSuffix(".gguf"),
                repo = "",
                file = name,
                sizeBytes = dest.length(),
                description = "本地导入（${dest.length() / 1048576}MB）",
                localOnly = true,
            )
            LlmModelRegistry.addCustom(context, entry)
            entry
        } catch (e: Throwable) {
            AppLogger.e(TAG, "importFromUri failed: ${e.message}", e)
            null
        }
    }

    /**
     * 扫描 Download 目录（含常见子目录）里的 .gguf，直接登记（不拷贝，原文件原地用）。
     * 返回新登记的数量。
     */
    fun scanAndRegisterDownloads(): Int {
        val roots = listOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Live2D"),
        )
        var added = 0
        for (root in roots) {
            val files = root.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) } ?: continue
            for (f in files) {
                val id = "dl_" + f.nameWithoutExtension.hashCode().toUInt().toString()
                if (LlmModelRegistry.find(context, id) != null) continue
                // 排除我们下载目录里的文件
                if (f.absolutePath.contains(context.getExternalFilesDir(null)?.absolutePath ?: "\u0000")) continue
                LlmModelRegistry.addCustom(
                    context,
                    LlmModelEntry(
                        id = id,
                        name = f.nameWithoutExtension,
                        repo = "",
                        file = f.name,
                        sizeBytes = f.length(),
                        description = "Download 扫描（${f.length() / 1048576}MB，原地引用不拷贝）",
                        localOnly = true,
                    ),
                )
                added++
            }
        }
        return added
    }
}