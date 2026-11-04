package com.aicompanion.tavern

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import com.aicompanion.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.CRC32
import kotlin.math.min

/**
 * SillyTavern 角色卡解析器
 * 支持 V1(扁平) / V2(chara) / V3(ccv3) 三种格式
 * 支持 PNG tEXt chunk 提取 + 纯 JSON 文件解析
 */
object TavernCardParser {

    private const val TAG = "TavernCardParser"

    /** PNG 文件大小上限: 10MB */
    private const val MAX_PNG_SIZE = 10 * 1024 * 1024L

    /** JSON 文件大小上限: 5MB */
    private const val MAX_JSON_SIZE = 5 * 1024 * 1024L

    /** PNG 签名头 (8 字节) */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )

    /** 解析后的角色卡数据 */
    data class ParsedCard(
        val name: String,
        val description: String,
        val personality: String,
        val scenario: String,
        val firstMessage: String,
        val mesExample: String,
        val creatorNotes: String,
        val systemPrompt: String,
        val postHistoryInstructions: String = "",
        val tags: List<String>,
        val characterBookEntries: List<BookEntry>,
        val characterBookName: String = "",
        val alternateGreetings: List<String>,
        val avatarBitmap: Bitmap?,
        val uiHtml: String?,
        val specVersion: String,
        val rawExtensions: Map<String, Any>
    )

    data class BookEntry(
        val keys: List<String>,
        val content: String,
        val comment: String = "",
        val keySecondary: List<String> = emptyList(),
        val constant: Boolean = false,
        val selective: Boolean = false,
        val insertionOrder: Int = 100,
        val enabled: Boolean = true,
        val position: String = "before_char",
        val caseSensitive: Boolean = false,
    )

    // ==================== 公开 API ====================

    /**
     * 从 PNG 文件路径解析角色卡
     * 自动检测 tEXt chunk 中的 "chara" (V2) 或 "ccv3" (V3) 关键字
     */
    fun parseFromPng(filePath: String): Result<ParsedCard> {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return Result.failure(FileNotFoundException("文件不存在: $filePath"))
            }
            if (file.length() > MAX_PNG_SIZE) {
                return Result.failure(IOException("PNG 文件过大 (${file.length()} bytes)，超过 ${MAX_PNG_SIZE / 1024 / 1024}MB 限制"))
            }

            FileInputStream(file).use { fis ->
                val avatarBitmap = try { BitmapFactory.decodeFile(filePath) } catch (_: Exception) { null }
                parseFromPngStream(fis, avatarBitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseFromPng 失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 从 InputStream 解析 PNG 角色卡（内部复用）
     */
    private fun parseFromPngStream(inputStream: InputStream, avatarBitmap: Bitmap? = null): Result<ParsedCard> {
        return try {
            val textChunks = extractTextChunks(inputStream)
            if (textChunks.isEmpty()) {
                return Result.failure(IOException("PNG 文件中未找到任何 tEXt chunk 数据"))
            }

            // 优先匹配 ccv3 (V3)，其次 chara (V2)
            val jsonStr = textChunks["ccv3"] ?: textChunks["chara"]
                ?: return Result.failure(IOException("tEXt chunk 中未找到 'ccv3' 或 'chara' 关键字"))

            Log.d(TAG, "从 PNG tEXt 提取到数据，关键字: ${if (textChunks.containsKey("ccv3")) "ccv3" else "chara"}，长度: ${jsonStr.length}")

            // 尝试解码 Base64（SillyTavern 通常对 chara 做 base64 编码）
            val decodedJson = tryDecodeBase64(jsonStr)
            val json = JSONObject(decodedJson)

            // 使用传入的头像 Bitmap
            val card = convertJsonToCard(json, avatarBitmap)
            Result.success(card)
        } catch (e: Exception) {
            Log.e(TAG, "parseFromPngStream 失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 从 JSON 字符串直接解析
     */
    fun parseFromJson(jsonStr: String): Result<ParsedCard> {
        return try {
            if (jsonStr.toByteArray().size > MAX_JSON_SIZE) {
                return Result.failure(IOException("JSON 数据过大，超过 ${MAX_JSON_SIZE / 1024 / 1024}MB 限制"))
            }
            val json = JSONObject(jsonStr.trim())
            val card = convertJsonToCard(json)
            Result.success(card)
        } catch (e: Exception) {
            Log.e(TAG, "parseFromJson 失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 将 ParsedCard 的字段映射填充到 PersonaEditorActivity 的各 EditText
     * @return 保存的头像文件路径（如果有的话），用于更新 Persona.avatarPath
     */
    fun fillToEditor(activity: Activity, card: ParsedCard): String? {
        try {
            activity.findViewById<EditText>(R.id.et_persona_name)?.setText(card.name)

            // description → et_persona_desc (追加外貌信息)
            val descBuilder = StringBuilder()
            if (card.description.isNotBlank()) descBuilder.append(card.description)
            if (card.tags.isNotEmpty()) {
                if (descBuilder.isNotEmpty()) descBuilder.append("\n\n")
                descBuilder.append("标签: ").append(card.tags.joinToString(", "))
            }
            activity.findViewById<EditText>(R.id.et_persona_desc)?.setText(descBuilder.toString())

            // personality → et_persona_personality (追加 mesExample)
            val personalityBuilder = StringBuilder()
            if (card.personality.isNotBlank()) personalityBuilder.append(card.personality)
            if (card.mesExample.isNotBlank()) {
                if (personalityBuilder.isNotEmpty()) personalityBuilder.append("\n\n")
                personalityBuilder.append("[对话示例]\n").append(card.mesExample)
            }
            activity.findViewById<EditText>(R.id.et_persona_personality)?.setText(personalityBuilder.toString())

            // scenario → et_world_setting
            activity.findViewById<EditText>(R.id.et_world_setting)?.setText(card.scenario)

            // firstMessage → et_persona_greeting
            activity.findViewById<EditText>(R.id.et_persona_greeting)?.setText(card.firstMessage)

            // system_prompt → et_free_mode
            // creator_notes → 追加到 et_free_mode
            val freeModeBuilder = StringBuilder()
            if (card.systemPrompt.isNotBlank()) {
                freeModeBuilder.append("[系统提示]\n").append(card.systemPrompt)
            }
            if (card.creatorNotes.isNotBlank()) {
                if (freeModeBuilder.isNotEmpty()) freeModeBuilder.append("\n\n")
                freeModeBuilder.append("[创作者备注]\n").append(card.creatorNotes)
            }
            if (freeModeBuilder.isNotEmpty()) {
                activity.findViewById<EditText>(R.id.et_free_mode)?.setText(freeModeBuilder.toString())
            }

            // character_book.entries → 拼接到 et_world_rules 和 et_world_relationship
            if (card.characterBookEntries.isNotEmpty()) {
                val rulesBuilder = StringBuilder()
                val relationshipBuilder = StringBuilder()
                card.characterBookEntries.forEachIndexed { index, entry ->
                    if (entry.comment.contains("关系", ignoreCase = true) ||
                        entry.keys.any { it.contains("关系", ignoreCase = true) }) {
                        relationshipBuilder.append("【世界书 #${index + 1}${if (entry.comment.isNotBlank()) ": ${entry.comment}" else ""}】\n")
                            .append(entry.content).append("\n\n")
                    } else {
                        rulesBuilder.append("【世界书 #${index + 1}${if (entry.comment.isNotBlank()) ": ${entry.comment}" else ""}】\n")
                            .append(entry.content).append("\n\n")
                    }
                }
                if (rulesBuilder.isNotEmpty()) {
                    val existingRules = activity.findViewById<EditText>(R.id.et_world_rules)?.text?.toString().orEmpty()
                    if (existingRules.isNotBlank()) rulesBuilder.insert(0, "$existingRules\n\n")
                    activity.findViewById<EditText>(R.id.et_world_rules)?.setText(rulesBuilder.toString().trim())
                }
                if (relationshipBuilder.isNotEmpty()) {
                    val existingRel = activity.findViewById<EditText>(R.id.et_world_relationship)?.text?.toString().orEmpty()
                    if (existingRel.isNotBlank()) relationshipBuilder.insert(0, "$existingRel\n\n")
                    activity.findViewById<EditText>(R.id.et_world_relationship)?.setText(relationshipBuilder.toString().trim())
                }
            }

            // 保存头像，返回路径
            var savedAvatarPath: String? = null
            if (card.avatarBitmap != null && card.name.isNotBlank()) {
                savedAvatarPath = saveAvatar(activity, card.avatarBitmap, card.name)
            }

            Log.i(TAG, "fillToEditor 完成: 角色=${card.name}, 版本=${card.specVersion}")
            return savedAvatarPath
        } catch (e: Exception) {
            Log.e(TAG, "fillToEditor 失败: ${e.message}", e)
            Toast.makeText(activity, "填充字段时出错: ${e.message}", Toast.LENGTH_LONG).show()
            return null
        }
    }

    // ==================== 内部方法 ====================

    /**
     * SillyTavern 世界书 position 数字 → 星尘 position 字符串
     * 搬自 SillyTavern 公开源码 world-info.js 的常量定义：
     * 0=before_char, 1=after_char, 2=before_authors_note(?), 3=after(?),
     * 实际 ST world_info position: 0=before, 1=after, 2=ANTop, 3=ANBottom, 4=atDepth, 5=EMTop, 6=EMBottom
     * 星尘仅支持 before_char / after_char，其余归一到 before_char
     */
    private fun mapTavernPosition(pos: Int): String = when (pos) {
        1 -> "after_char"
        else -> "before_char" // 0、2~6 全归 before_char（星尘暂无 AN/depth 注入位）
    }

    /**
     * V2 spec 的 position 字段兼容：既可能是数字（0/1），也可能是字符串
     * （"before_char"/"after_char"，部分导出工具如量角器卡生成器用字符串形式）
     */
    private fun parsePositionAny(entry: org.json.JSONObject, ext: org.json.JSONObject?): String {
        // 先尝试字符串形式
        val strPos = entry.optString("position", "")
        if (strPos == "before_char" || strPos == "after_char") return strPos
        if (ext != null) {
            val extStr = ext.optString("position", "")
            if (extStr == "before_char" || extStr == "after_char") return extStr
        }
        // 数字形式（ST 内部 0=before, 1=after）
        val num = when {
            entry.has("position") && !entry.isNull("position") -> entry.optInt("position", -1)
            ext?.has("position") == true -> ext.optInt("position", -1)
            else -> -1
        }
        // optInt 对非数字字符串返回 0 而非 fallback，需排除：字符串但非合法值时视作未知
        val raw = if (entry.has("position") && !entry.isNull("position")) entry.opt("position") else ext?.opt("position")
        if (raw is String && raw != "0" && raw != "1" && raw != "before_char" && raw != "after_char") return "before_char"
        return mapTavernPosition(num)
    }

    /**
     * 将解析出的酒馆世界书条目转成星尘原生 WorldInfo（可直接 addWorldInfo 挂到角色）
     * 参考 SillyTavern character_book spec 与星尘 WorldInfoEntry 字段一一对应
     */
    fun bookEntriesToWorldInfo(
        bookName: String,
        entries: List<BookEntry>,
    ): com.aicompanion.models.WorldInfo {
        val wiEntries = entries.map { e ->
            com.aicompanion.models.WorldInfoEntry(
                id = java.util.UUID.randomUUID().toString(),
                key = e.keys.joinToString(","),
                keySecondary = e.keySecondary.joinToString(","),
                content = e.content,
                comment = e.comment,
                constant = e.constant,
                selective = e.selective,
                insertionOrder = e.insertionOrder,
                enabled = e.enabled,
                position = e.position,
            )
        }
        return com.aicompanion.models.WorldInfo(
            id = java.util.UUID.randomUUID().toString(),
            name = bookName.ifBlank { "导入的世界书" },
            entries = wiEntries,
        )
    }

    /**
     * 解析 SillyTavern 独立世界书 JSON（酒馆「导出世界书」格式）
     * 兼容两种结构：
     * - 标准导出：{ "entries": { "0": {...}, "1": {...} }, ... }
     * - Character-Book spec：{ "name": ..., "entries": [ {...}, ... ] }
     * 搬自 SillyTavern world-info.js 的导入逻辑字段约定
     */
    fun parseStandaloneWorldBook(jsonStr: String): Pair<String, List<BookEntry>> {
        val root = JSONObject(jsonStr)

        // 兼容：完整角色卡 JSON（spec: chara_card_v2/v3）——世界书嵌在 data.character_book
        if (root.optString("spec", "").startsWith("chara_card")) {
            val card = parseFromJson(jsonStr).getOrNull()
                ?: return Pair("", emptyList())
            return Pair(
                card.characterBookName.ifBlank { "${card.name}的世界书" },
                card.characterBookEntries,
            )
        }

        // 兼容：角色卡数据层（直接就是 data 对象，带 name/description/character_book）
        if (root.has("character_book") && root.has("name") && !root.has("entries")) {
            val book = root.optJSONObject("character_book")
            val entries = mutableListOf<BookEntry>()
            val bookName0 = book?.optString("name", "") ?: ""
            val eArr = book?.optJSONArray("entries")
            if (eArr != null) {
                for (i in 0 until eArr.length()) {
                    parseWbEntry(eArr.getJSONObject(i))?.let { entries.add(it) }
                }
            }
            return Pair(bookName0.ifBlank { root.optString("name", "") }, entries)
        }

        var bookName = root.optString("name", "")
        val entriesOut = mutableListOf<BookEntry>()
        // 兼容：嵌套单一世界书 {"entries": {...}, "name": ...} 已由下面处理；
        // ST 世界书也可能是 {"originalData": {...}} 包裹
        val innerRoot = root.optJSONObject("originalData") ?: root
        if (innerRoot !== root) {
            val (n2, e2) = parseStandaloneWorldBook(innerRoot.toString())
            if (e2.isNotEmpty()) return Pair(bookName.ifBlank { n2 }, e2)
        }
        val entriesVal = root.opt("entries") ?: return Pair(bookName, emptyList())
        when (entriesVal) {
            is JSONArray -> {
                // character_book spec 数组形式
                for (i in 0 until entriesVal.length()) {
                    val entry = entriesVal.getJSONObject(i)
                    parseWbEntry(entry)?.let { entriesOut.add(it) }
                }
            }
            is JSONObject -> {
                // ST 导出：entries 是 {uid: entry} 字典
                val ekeys = entriesVal.keys()
                while (ekeys.hasNext()) {
                    val ekey = ekeys.next()
                    val entry = entriesVal.optJSONObject(ekey) ?: continue
                    parseWbEntry(entry)?.let { entriesOut.add(it) }
                }
            }
        }
        return Pair(bookName, entriesOut)
    }

    private fun parseWbEntry(entry: JSONObject): BookEntry? {
        val content = entry.optString("content", "")
        if (content.isBlank()) return null
        val keys = mutableListOf<String>()
        // ST 导出格式: key 是数组；也有实现是逗号分隔字符串
        when (val kv = entry.opt("key")) {
            is JSONArray -> for (j in 0 until kv.length()) keys.add(kv.optString(j, ""))
            is String -> keys.addAll(kv.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        val secondaryKeys = mutableListOf<String>()
        when (val sk = entry.opt("keysecondary")) {
            is JSONArray -> for (j in 0 until sk.length()) secondaryKeys.add(sk.optString(j, ""))
            is String -> secondaryKeys.addAll(sk.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        val extensions = entry.optJSONObject("extensions")
        val constant = entry.optBoolean("constant", false) ||
            (extensions?.optBoolean("constant", false) ?: false)
        val selective = entry.optBoolean("selective", false) ||
            (extensions?.optBoolean("selective", false) ?: false)
        val insertionOrder = entry.optInt("insertion_order",
            extensions?.optInt("insertion_order", 100) ?: 100)
        val enabledFlag = entry.optBoolean("enabled", true) &&
            extensions?.optBoolean("disable", false) == false
        // ST: disable=true 表示禁用
        val enabledFinal = if (entry.has("disable")) !entry.optBoolean("disable", false) else enabledFlag
        val position = mapTavernPosition(entry.optInt("position", 0))
        val comment = entry.optString("comment", "")
        return BookEntry(
            keys, content, comment,
            keySecondary = secondaryKeys,
            constant = constant,
            selective = selective,
            insertionOrder = insertionOrder,
            enabled = enabledFinal,
            position = position,
        )
    }

    /**
     * PNG tEXt chunk 提取：返回 Map<keyword, text>
     * 使用纯 Java InputStream 逐字节读取，不依赖第三方库
     */
    private fun extractTextChunks(inputStream: InputStream): Map<String, String> {
        val result = mutableMapOf<String, String>()

        try {
            // 验证 PNG 签名
            val signature = ByteArray(8)
            val read = inputStream.read(signature)
            if (read != 8 || !signature.contentEquals(PNG_SIGNATURE)) {
                Log.w(TAG, "不是有效的 PNG 文件")
                return emptyMap()
            }

            // 逐个读取 chunk
            while (true) {
                // 读取 4 字节长度 (大端序)
                val lengthBytes = ByteArray(4)
                if (inputStream.read(lengthBytes) != 4) break
                val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                    ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                    ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                    (lengthBytes[3].toInt() and 0xFF)

                // 读取 4 字节类型
                val typeBytes = ByteArray(4)
                if (inputStream.read(typeBytes) != 4) break
                val type = String(typeBytes, Charsets.US_ASCII)

                // 安全检查：防止恶意超大 chunk（在分配内存之前检查）
                if (length > MAX_PNG_SIZE || length < 0) {
                    Log.w(TAG, "检测到异常大的 chunk ($length bytes)，停止解析")
                    break
                }

                // 读取数据
                val data = if (length > 0) ByteArray(length) else ByteArray(0)
                if (length > 0) {
                    var offset = 0
                    while (offset < length) {
                        val readLen = inputStream.read(data, offset, length - offset)
                        if (readLen <= 0) break
                        offset += readLen
                    }
                }

                // 读取 4 字节 CRC (跳过校验，兼容性优先)
                val crcBytes = ByteArray(4)
                inputStream.read(crcBytes)

                // 检查是否为 tEXt chunk
                when (type) {
                    "tEXt" -> {
                        // tEXt 数据格式: keyword(ASCII) + \0 + text(UTF-8)
                        val separatorIndex = data.indexOf(0)
                        if (separatorIndex >= 0 && separatorIndex < data.size) {
                            val keyword = String(data, 0, separatorIndex, Charsets.US_ASCII)
                            val text = String(data, separatorIndex + 1, data.size - separatorIndex - 1, Charsets.UTF_8)
                            result[keyword] = text
                            Log.d(TAG, "提取到 tEXt chunk: keyword='$keyword', text长度=${text.length}")
                        }
                    }
                    "IEND" -> break // 到达文件末尾
                }

                // chunk 数据已处理，继续读取下一个
            }
        } catch (e: EOFException) {
            // 正常结束
        } catch (e: IOException) {
            Log.e(TAG, "读取 PNG 流时发生 IO 错误: ${e.message}", e)
        }

        return result
    }

    /**
     * CRC32 校验（PNG chunk 需要）
     */
    private fun crc32(data: ByteArray): Int {
        val crc32 = CRC32()
        crc32.update(data)
        return crc32.value.toInt()
    }

    /**
     * 尝试解码 Base64（SillyTavern 的 chara 字段通常是 Base64 编码的 JSON）
     */
    private fun tryDecodeBase64(input: String): String {
        val trimmed = input.trim()
        // 快速判断：如果是有效的 JSON 直接返回
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        // 尝试 Base64 解码
        return try {
            val decoded = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            // 不是 Base64，原样返回
            trimmed
        }
    }

    /**
     * 从 PNG 文件中提取头像 Bitmap
     */
    private fun tryExtractAvatarFromPng(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            // 计算采样率，避免 OOM
            val maxSize = 512
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
                sampleSize *= 2
            }

            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }.let { opts ->
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "头像内存不足，尝试低质量模式", e)
            // 先给 GC 回收机会
            System.gc()
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {}
            try {
                BitmapFactory.Options().apply {
                    inSampleSize = 8
                    inPreferredConfig = Bitmap.Config.RGB_565
                }.let { opts ->
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                }
            } catch (e2: Throwable) {
                Log.e(TAG, "头像提取完全失败: ${e2.message}", e2)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "头像提取失败: ${e.message}", e)
            null
        }
    }

    /**
     * 保存头像到本地存储
     */
    private fun saveAvatar(activity: Activity, bitmap: Bitmap, name: String): String? {
        return try {
            val avatarsDir = File(activity.filesDir, "avatars").apply { mkdirs() }
            // 清理文件名中的非法字符
            val safeName = name.replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5]"), "_")
            val avatarFile = File(avatarsDir, "tavern_$safeName.png")

            FileOutputStream(avatarFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
                fos.flush()
            }

            Log.d(TAG, "头像已保存: ${avatarFile.absolutePath} (${avatarFile.length()} bytes)")
            avatarFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存头像失败: ${e.message}", e)
            null
        }
    }

    /**
     * JSON → ParsedCard 核心转换
     */
    private fun convertJsonToCard(json: JSONObject, avatar: Bitmap? = null): ParsedCard {
        val (version, normalized, _) = normalizeCard(json)

        val data = normalized.optJSONObject("data") ?: normalized

        val name = data.optString("name", "未知角色")
        val description = data.optString("description", "")
        val personality = data.optString("personality", "")
        val scenario = data.optString("scenario", "")
        val firstMes = data.optString("first_mes", data.optString("first_message", ""))
        val mesExample = data.optString("mes_example", "")
        val creatorNotes = data.optString("creator_notes", "")
        val systemPrompt = data.optString("system_prompt", "")
        val postHistory = data.optString("post_history_instructions", "")

        // tags
        val tags = mutableListOf<String>()
        val tagsArr = data.optJSONArray("tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val tag = tagsArr.optString(i, "").trim()
                if (tag.isNotEmpty()) tags.add(tag)
            }
        }

        // character book entries
        val bookEntries = mutableListOf<BookEntry>()
        val characterBook = data.optJSONObject("character_book")
        var bookName = characterBook?.optString("name", "") ?: ""
        if (characterBook != null) {
            val entriesArr = characterBook.optJSONArray("entries")
            if (entriesArr != null) {
                for (i in 0 until entriesArr.length()) {
                    val entry = entriesArr.getJSONObject(i)
                    val keys = mutableListOf<String>()
                    val keysArr = entry.optJSONArray("keys")
                    if (keysArr != null) {
                        for (j in 0 until keysArr.length()) {
                            keys.add(keysArr.optString(j, ""))
                        }
                    }
                    val content = entry.optString("content", "")
                    val comment = entry.optString("comment", "")
                    if (content.isNotEmpty()) {
                        // 完整解析酒馆条目字段（SillyTavern character_book / V2 spec）
                        val secondaryKeys = mutableListOf<String>()
                        entry.optJSONArray("secondary_keys")?.let { sk ->
                            for (j in 0 until sk.length()) secondaryKeys.add(sk.optString(j, ""))
                        }
                        // V3 spec: keysecondary；部分实现也用 secondary_keys（V2）
                        if (secondaryKeys.isEmpty()) {
                            entry.optJSONArray("keysecondary")?.let { sk ->
                                for (j in 0 until sk.length()) secondaryKeys.add(sk.optString(j, ""))
                            }
                        }
                        val extensions = entry.optJSONObject("extensions")
                        // constant（蓝灯/常驻）：条目级 > extensions.constant
                        val constant = entry.optBoolean("constant", false) ||
                            (extensions?.optBoolean("constant", false) ?: false)
                        val selective = entry.optBoolean("selective", false) ||
                            (extensions?.optBoolean("selective", false) ?: false)
                        // insertion_order（V2/V3 通用）
                        val insertionOrder = entry.optInt("insertion_order",
                            extensions?.optInt("insertion_order", 100) ?: 100)
                        val enabledFlag = entry.optBoolean("enabled", true) &&
                            extensions?.optBoolean("enabled", true) != false
                        val position = parsePositionAny(entry, extensions)
                        val caseSensitive = extensions?.optBoolean("case_sensitive", false) ?: false
                        bookEntries.add(
                            BookEntry(
                                keys, content, comment,
                                keySecondary = secondaryKeys,
                                constant = constant,
                                selective = selective,
                                insertionOrder = insertionOrder,
                                enabled = enabledFlag,
                                position = position,
                                caseSensitive = caseSensitive,
                            )
                        )
                    }
                }
            }
        }

        // alternate greetings
        val altGreetings = mutableListOf<String>()
        val altGreetArr = data.optJSONArray("alternate_greetings")
        if (altGreetArr != null) {
            for (i in 0 until altGreetArr.length()) {
                altGreetings.add(altGreetArr.optString(i, ""))
            }
        }

        // extensions
        val extensions = normalized.optJSONObject("extensions") ?: JSONObject()
        val rawExtensions = mutableMapOf<String, Any>()
        val extKeys = extensions.keys()
        while (extKeys.hasNext()) {
            val key = extKeys.next()
            rawExtensions[key] = extensions.get(key)
        }

        // UI HTML (extensions.label 或 extensions.html)
        var uiHtml: String? = null
        if (extensions.has("label")) {
            uiHtml = extensions.getString("label")
        } else if (extensions.has("html")) {
            uiHtml = extensions.getString("html")
        }

        return ParsedCard(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMes,
            mesExample = mesExample,
            creatorNotes = creatorNotes,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistory,
            tags = tags,
            characterBookEntries = bookEntries,
            characterBookName = bookName,
            alternateGreetings = altGreetings,
            avatarBitmap = avatar,
            uiHtml = uiHtml,
            specVersion = version,
            rawExtensions = rawExtensions
        )
    }

    /**
     * 版本检测与标准化
     * 返回 Triple(版本标识, 标准化后的JSONObject, 头像Bitmap?)
     *
     * V1: 扁平结构，顶层有 name 字段，没有 data 包装层
     * V2: 有 spec_chara_version / spec 字段，data 包装层
     * V3: 有 spec_version = "ccv3"，data 包装层
     */
    private fun normalizeCard(json: JSONObject): Triple<String, JSONObject, Bitmap?> {
        // 检测 V3 (ccv3)
        val specVersion = json.optString("spec_version", "")
        if (specVersion == "ccv3") {
            Log.d(TAG, "检测到 V3 (ccv3) 格式")
            return Triple("V3 (ccv3)", json, null)
        }

        // 检测 V2 (标准 SillyTavern V2)
        val specCharaVersion = json.optString("spec_chara_version", "")
        if (specCharaVersion.isNotBlank()) {
            Log.d(TAG, "检测到 V2 格式, spec=$specCharaVersion")
            return Triple("V2 ($specCharaVersion)", json, null)
        }

        // 检测有 spec 字段的变体
        if (json.has("spec")) {
            Log.d(TAG, "检测到带 spec 字段的 V2 变体格式")
            return Triple("V2 (spec variant)", json, null)
        }

        // 检测 V1 (扁平格式)：顶层有 name 且没有 data 包装层
        if (json.has("name") && !json.has("data")) {
            Log.d(TAG, "检测到 V1 (扁平) 格式，自动包装为 V2 结构")
            // 将扁平 V1 包装为 V2 结构
            val wrapped = JSONObject().apply {
                put("spec", "SillyTavern")
                put("spec_version", "1.0")
                put("data", json)
            }
            return Triple("V1 (auto-wrapped)", wrapped, null)
        }

        // 如果已经有 data 层但没明确版本号，视为 V2 兼容格式
        if (json.has("data")) {
            Log.d(TAG, "检测到 V2 兼容格式 (有 data 层)")
            return Triple("V2 (compatible)", json, null)
        }

        // 最后尝试：整个 JSON 作为 data
        Log.d(TAG, "无法确定格式，将整体作为 V2 data 处理")
        return Triple("Unknown", JSONObject().apply { put("data", json) }, null)
    }
}
