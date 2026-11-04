package com.aicompanion.tavern

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酒馆独立预设（Preset）管理器。
 *
 * 支持 SillyTavern 预设 JSON 格式：
 * - prompts: [{ name, identifier, content, system_prompt, role, ... }]
 * - prompt_order: [{ character_id: 100000/100001, order: [{ identifier, enabled }] }]
 *
 * 启用的块按顺序拼接，作为全局前缀注入每次对话的 system 最前面。
 * 存储在 SharedPreferences（tavern_preset），内容可能较大，压缩存 base64。
 */
object TavernPresetManager {

    data class PresetBlock(
        val identifier: String,
        val name: String,
        val content: String,
        var enabled: Boolean,
        val isMarker: Boolean, // chat marker（chat history / dialogue examples），无实际内容
    )

    private const val PREFS = "tavern_preset"
    private const val KEY_BLOCKS = "blocks_json"
    private const val KEY_NAME = "preset_name"

    /** 解析酒馆预设 JSON。返回 null 表示格式不识别。 */
    fun parse(jsonStr: String): Pair<String, List<PresetBlock>>? {
        val root = try { JSONObject(jsonStr) } catch (e: Exception) { return null }
        val prompts = root.optJSONArray("prompts") ?: return null
        if (prompts.length() == 0) return null

        // 读取 prompt_order（取 character_id=100001 的顺序表，缺省时取第一个）
        var orderMap: Map<String, Boolean>? = null
        val po = root.optJSONArray("prompt_order")
        if (po != null && po.length() > 0) {
            var best: JSONObject? = null
            for (i in 0 until po.length()) {
                val o = po.optJSONObject(i) ?: continue
                if (o.optInt("character_id", -1) == 100001) { best = o; break }
                if (best == null) best = o
            }
            val arr = best?.optJSONArray("order")
            if (arr != null) {
                val m = mutableMapOf<String, Boolean>()
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    m[it.optString("identifier")] = it.optBoolean("enabled", true)
                }
                if (m.isNotEmpty()) orderMap = m
            }
        }

        val blocks = mutableListOf<PresetBlock>()
        for (i in 0 until prompts.length()) {
            val p = prompts.optJSONObject(i) ?: continue
            val id = p.optString("identifier", "p$i")
            val content = p.optString("content", "")
            val isMarker = p.optBoolean("marker", false) || (content.isBlank() && !p.has("content"))
            // 注入点过滤：main/系统类块注入 system；nsfw/jailbreak 同样拼在 system 前缀里
            val enabled = orderMap?.get(id) ?: !isMarker
            blocks.add(
                PresetBlock(
                    identifier = id,
                    name = p.optString("name", "").ifBlank { id },
                    content = content,
                    enabled = enabled,
                    isMarker = isMarker,
                )
            )
        }
        if (blocks.none { it.content.isNotBlank() }) return null
        val name = root.optString("name", "").ifBlank { "导入的预设" }
        return Pair(name, blocks)
    }

    fun save(context: Context, name: String, blocks: List<PresetBlock>) {
        val arr = JSONArray()
        blocks.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.identifier)
                put("name", b.name)
                put("content", b.content)
                put("enabled", b.enabled)
                put("marker", b.isMarker)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, name)
            .putString(KEY_BLOCKS, arr.toString())
            .apply()
    }

    fun load(context: Context): Pair<String, List<PresetBlock>>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BLOCKS, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val blocks = mutableListOf<PresetBlock>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                blocks.add(
                    PresetBlock(
                        identifier = o.optString("id"),
                        name = o.optString("name"),
                        content = o.optString("content"),
                        enabled = o.optBoolean("enabled", true),
                        isMarker = o.optBoolean("marker", false),
                    )
                )
            }
            Pair(prefs.getString(KEY_NAME, "预设") ?: "预设", blocks)
        } catch (e: Exception) { null }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 构建注入前缀：所有启用且非 marker 的块按顺序拼接。
     * 空则返回 ""。
     */
    fun buildPrefix(context: Context): String {
        val loaded = load(context) ?: return ""
        return loaded.second
            .filter { it.enabled && !it.isMarker && it.content.isNotBlank() }
            .joinToString("\n\n") { it.content.trim() }
            .trim()
    }

    fun hasPreset(context: Context): Boolean = load(context)?.second?.any { it.enabled && !it.isMarker } == true
}
