package com.jev.probe.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Exports every setting (API keys included) to one JSON file and restores it
 * after a reinstall.
 *
 * Why a file and not just the app's own storage: this app is sideloaded, so
 * every version bump means uninstall + install, which wipes app-private
 * SharedPreferences. The user then had to retype two API keys and every
 * setting each time. Writing the config to a user-chosen file (via SAF, see
 * SettingsActivity) survives reinstall, app data clearing, and switching phones.
 *
 * The file contains API keys in plain text. That is a deliberate trade: it is
 * the user's own file on their own device, and the alternative (retyping the
 * keys) is what this exists to remove. The settings page says so plainly.
 */
object ConfigBackup {

    /** Bumped when the payload shape changes incompatibly. */
    private const val FORMAT = 1

    /**
     * All persisted settings, in a fixed order so the exported file is stable
     * and diffable. Kept as (key, value) pairs rather than reflective field
     * access: the property names above are the real storage keys, and a typo
     * here would silently drop a setting.
     */
    private fun snapshot(p: Prefs): List<Pair<String, Any>> = listOf(
        // judge route
        "judge_provider" to p.judgeProvider,
        "judge_base_url" to p.judgeBaseUrl,
        "judge_key" to p.judgeKey,
        "judge_model" to p.judgeModel,
        // reply route
        "reply_base_url" to p.replyBaseUrl,
        "reply_key" to p.replyKey,
        "reply_model" to p.replyModel,
        "reply_thinking" to p.replyThinking,
        // vision route
        "vision_base_url" to p.visionBaseUrl,
        "vision_key" to p.visionKey,
        "vision_model" to p.visionModel,
        // knowledge / context
        "context_enabled" to p.contextEnabled,
        "context_history_count" to p.contextHistoryCount,
        "auto_summary" to p.autoSummary,
        // OCR
        "ocr_engine" to p.ocrEngine,
        "ocr_unknown_apps" to p.ocrForUnknownApps,
        "ocr_fallback" to p.ocrFallback,
        "ocr_auto_analyze" to p.ocrAutoAnalyze,
        // behavior
        "relationship" to p.relationship,
        "enabled" to p.enabled,
        "auto_analyze" to p.autoAnalyze,
        "group_mode" to p.groupMode,
        "group_target" to p.groupTarget,
        // overlay
        "overlay_opacity" to p.overlayOpacity,
        "bubble_x" to p.bubbleX,
        "bubble_y" to p.bubbleY,
        "panel_width" to p.panelWidth,
        "panel_height" to p.panelHeight
    )

    /** Whitelist is a set, handled separately from the scalar list above. */
    private const val KEY_WHITELIST = "whitelist"

    /** The config as pretty JSON. Keys are included — this is the user's own backup. */
    fun exportJson(p: Prefs): String {
        val settings = JSONObject()
        snapshot(p).forEach { (k, v) -> settings.put(k, v) }
        settings.put(KEY_WHITELIST, org.json.JSONArray(p.whitelist.toList().sorted()))
        return JSONObject()
            .put("app", "jev-assistant")
            .put("format", FORMAT)
            .put("exportedAt", System.currentTimeMillis())
            .put("settings", settings)
            .toString(2)
    }

    /**
     * Apply an exported file. Returns a human-readable summary of what was
     * restored, or throws with a readable reason.
     *
     * Only keys present in the file are touched, so an old backup still
     * restores cleanly after new settings are added.
     */
    fun importJson(p: Prefs, json: String): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的配置文件（JSON 解析失败）")
        }
        val s = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("配置文件里没有 settings 段")
        val fmt = root.optInt("format", 0)
        if (fmt > FORMAT) {
            throw IllegalArgumentException("配置来自更新的版本（format $fmt），当前只支持到 $FORMAT")
        }

        var applied = 0
        fun str(k: String, set: (String) -> Unit) {
            if (s.has(k)) { set(s.optString(k)); applied++ }
        }
        fun bool(k: String, set: (Boolean) -> Unit) {
            if (s.has(k)) { set(s.optBoolean(k)); applied++ }
        }
        fun int(k: String, set: (Int) -> Unit) {
            if (s.has(k)) { set(s.optInt(k)); applied++ }
        }

        str("judge_provider") { p.judgeProvider = it }
        str("judge_base_url") { p.judgeBaseUrl = it }
        str("judge_key") { p.judgeKey = it }
        str("judge_model") { p.judgeModel = it }
        str("reply_base_url") { p.replyBaseUrl = it }
        str("reply_key") { p.replyKey = it }
        str("reply_model") { p.replyModel = it }
        bool("reply_thinking") { p.replyThinking = it }
        str("vision_base_url") { p.visionBaseUrl = it }
        str("vision_key") { p.visionKey = it }
        str("vision_model") { p.visionModel = it }
        bool("context_enabled") { p.contextEnabled = it }
        int("context_history_count") { p.contextHistoryCount = it }
        bool("auto_summary") { p.autoSummary = it }
        str("ocr_engine") { p.ocrEngine = it }
        bool("ocr_unknown_apps") { p.ocrForUnknownApps = it }
        bool("ocr_fallback") { p.ocrFallback = it }
        bool("ocr_auto_analyze") { p.ocrAutoAnalyze = it }
        str("relationship") { p.relationship = it }
        bool("enabled") { p.enabled = it }
        bool("auto_analyze") { p.autoAnalyze = it }
        bool("group_mode") { p.groupMode = it }
        str("group_target") { p.groupTarget = it }
        int("overlay_opacity") { p.overlayOpacity = it }
        int("bubble_x") { p.bubbleX = it }
        int("bubble_y") { p.bubbleY = it }
        int("panel_width") { p.panelWidth = it }
        int("panel_height") { p.panelHeight = it }

        s.optJSONArray(KEY_WHITELIST)?.let { arr ->
            val set = HashSet<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty()) set.add(v)
            }
            p.whitelist = set
            applied++
        }

        val keys = if (p.judgeKey.isNotBlank() && p.replyKey.isNotBlank()) "两个密钥都在"
        else if (p.judgeKey.isNotBlank()) "只有判断密钥，回复密钥缺失"
        else "密钥缺失"
        return "已恢复 $applied 项设置 · $keys"
    }

    /** Default file name shown in the save/open dialogs. */
    fun suggestedName(): String = "jev-assistant-config.json"

    /**
     * Copy the app's knowledge base (notes / contacts / history) alongside the
     * settings, so a reinstall can bring the user's context back too.
     * Best effort: a missing directory is fine.
     */
    fun copyKnowledgeBase(ctx: Context, targetDir: File): Int {
        val src = File(ctx.filesDir, "kb")
        if (!src.isDirectory) return 0
        var n = 0
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).path
            val out = File(targetDir, rel)
            out.parentFile?.mkdirs()
            runCatching { f.copyTo(out, overwrite = true); n++ }
        }
        return n
    }

    /** Restore a knowledge-base copy made by [copyKnowledgeBase]. */
    fun restoreKnowledgeBase(ctx: Context, sourceDir: File): Int {
        if (!sourceDir.isDirectory) return 0
        val dst = File(ctx.filesDir, "kb")
        var n = 0
        sourceDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(sourceDir).path
            val out = File(dst, rel)
            out.parentFile?.mkdirs()
            runCatching { f.copyTo(out, overwrite = true); n++ }
        }
        return n
    }
}
