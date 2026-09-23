package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.ChatContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The generative route: any OpenAI-compatible `/chat/completions` endpoint.
 * Drafts the 3 candidate replies, and (D stage) summarizes text. Reads
 * replyBaseUrl / replyKey / replyModel from [Prefs].
 */
class ReplyClient(private val prefs: Prefs) {

    /**
     * Exactly 3 varied candidate replies in Chinese.
     *
     * @param ctx D-stage knowledge context. When present its background and
     *        history are prepended to the prompt with an instruction to stay
     *        consistent with them and invent nothing beyond them.
     */
    fun draft(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        // The exact output shape is spelled out because this prompt runs on
        // small fast models that otherwise invent their own (observed live:
        // [{content,strategy}] objects, unquoted braces, full-width quotes).
        // Stating "array of strings" alone was not enough. parseThree still
        // repairs whatever comes back — this just makes the good case common.
        val sys = "你是中文即时通讯回复助手。只输出一个 JSON 数组，数组里只能有 3 个字符串元素，" +
            "不要对象、不要键名、不要 strategy 之类的字段，就是 3 条纯文本。" +
            "格式必须严格是：[\"第一条\",\"第二条\",\"第三条\"]。" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。" +
            "不要解释，不要 markdown 代码块，不要引号以外的任何内容，直接输出那个 JSON 数组。"
        val user = knowledgeBlock(relationship, ctx) +
            "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        return parseThree(chat(sys, user, temperature = 0.8))
    }

    /** The background + history preamble; empty string when there is no context. */
    private fun knowledgeBlock(relationship: String, ctx: ChatContext?): String {
        ctx ?: return ""
        val background = ctx.background(relationship)
        val history = ctx.history
        if (background.isBlank() && history.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("以下是关于我和对方的背景与知识库，回复必须与之一致，")
            .append("可以直接引用其中事实，不要编造知识库里没有的事实。\n")
        if (background.isNotBlank()) sb.append(background).append('\n')
        if (history.isNotEmpty()) {
            sb.append("\n更早的聊天记录（越靠下越新）：\n")
            history.takeLast(prefs.contextHistoryCount.coerceIn(0, 100)).forEach {
                sb.append(if (it.side == "me") "我：" else "对方：").append(it.text).append('\n')
            }
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * One plain chat round trip for the settings connectivity test. Deliberately
     * NOT [summarize]: the test should exercise the ordinary path, not whatever
     * the summary prompt happens to be.
     */
    fun ping(): String =
        chat("你是连通性测试助手，只按要求回答，不要解释。", "请只回复两个字：收到", temperature = 0.0).trim()

    /** Condense a block of text (used by the D-stage contact auto-summary). */
    fun summarize(text: String): String {
        if (text.isBlank()) return ""
        val sys = "你是中文摘要助手。把给到的聊天记录压缩成不超过 120 字的第三人称要点摘要，" +
            "只保留事实、偏好、承诺和待办，不要评论，不要编造。直接输出摘要正文。"
        return chat(sys, text, temperature = 0.2).trim()
    }

    /** One chat-completions round trip; returns the assistant message content. */
    private fun chat(system: String, user: String, temperature: Double): String {
        val url = prefs.replyEndpoint()
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", prefs.replyModel)
            .put("messages", messages)
            .put("temperature", temperature)
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    /**
     * Pull exactly 3 reply strings out of whatever the model returned.
     *
     * Small models do NOT reliably emit `["a","b","c"]`. Observed live from
     * mimo-v2.6-flash on the same prompt, three runs in a row:
     *   run 1  ["a", "b", "c"]                      -> valid JSON
     *   run 2  [{a},{b},{c}]                        -> unquoted braces, invalid JSON
     *   run 3  [{“a”},{“b”},{“c”}]                  -> full-width quotes, invalid JSON
     *   run 4  [{"text":"a"},{"text":"b"},...]      -> valid JSON, but objects
     * The last one was the visible bug: `getString` on a JSONObject returns its
     * serialized form, so the panel literally showed `{"text":"..."}` as the
     * reply text. So: parse strictly, then repair the common near-JSON shapes,
     * then fall back to scraping quoted runs, then to plain lines.
     */
    private fun parseThree(content: String): List<String> {
        val raw = content.trim()
        if (raw.isEmpty()) return pad(emptyList())

        // 1. Strict JSON, and unwrap object elements via their text-ish key.
        strictArray(raw)?.let { arr ->
            val out = collectTexts(arr)
            if (out.isNotEmpty()) return pad(out)
        }

        // 2. Repair the near-JSON shapes above, then retry strict parsing.
        val fixed = repairJson(raw)
        if (fixed != raw) {
            strictArray(fixed)?.let { arr ->
                val out = collectTexts(arr)
                if (out.isNotEmpty()) return pad(out)
            }
        }

        // 3. Scrape quoted runs (handles the full-width-quote case cleanly).
        QUOTED.findAll(fixed)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !it.startsWith("{") }
            .toList()
            .let { if (it.isNotEmpty()) return pad(it) }

        // 4. Last resort: one reply per line, stripped of list/quote furniture.
        val lines = fixed.split("\n")
            .map { it.trim().trim('-', '*', '1', '2', '3', '.', ' ', '"', '\'', '[', ']', '{', '}') }
            .filter { it.isNotBlank() }
        return pad(lines)
    }

    /** Pad/truncate to exactly 3, so the rank question always has 3 candidates. */
    private fun pad(items: List<String>): List<String> {
        val out = items.map { tidy(it) }.filter { it.isNotEmpty() }.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }

    /**
     * Peel quotes that wrap the whole reply. Models frequently quote the text
     * when they wrap it in an object (`{“回复内容”}`), and the repair step then
     * leaves those quotes inside the string — which renders as a reply with
     * visible quotation marks around it.
     */
    private fun tidy(s: String): String {
        var t = s.trim()
        while (t.length >= 2) {
            val a = t.first()
            val b = t.last()
            val wrapped = (a == '"' && b == '"') || (a == '\'' && b == '\'') ||
                (a == '\u201c' && b == '\u201d') || (a == '\u300c' && b == '\u300d')
            if (!wrapped) break
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    /** The bracketed span parsed as a JSON array, or null if it is not one. */
    private fun strictArray(s: String): JSONArray? {
        val start = s.indexOf('[')
        val end = s.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return try {
            JSONArray(s.substring(start, end + 1))
        } catch (_: Exception) { null }
    }

    /** One element's text: a bare string, or the text-ish field of an object. */
    private fun collectTexts(arr: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val text = when (val el = arr.opt(i)) {
                is String -> el
                is JSONObject -> TEXT_KEYS.firstNotNullOfOrNull { k ->
                    el.optString(k).takeIf { it.isNotBlank() }
                } ?: el.keys().asSequence()
                    .map { el.optString(it) }
                    .firstOrNull { it.isNotBlank() }
                else -> null
            }
            if (!text.isNullOrBlank()) out.add(text.trim())
        }
        return out
    }

    /**
     * Turn the common near-JSON outputs into real JSON:
     *  - full-width quotes `“a”` -> `"a"`
     *  - unquoted keys `{text: "a"}` -> `{"text": "a"}`
     *  - bare objects `{a}` -> `"a"`
     * Order matters: key-quoting first, so `{text: "x"}` is not mistaken for a
     * bare object (the bare-object pattern skips anything containing `:`).
     */
    private fun repairJson(s: String): String {
        var t = s.replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
        t = KEY_UNQUOTED.replace(t) { m -> "${m.groupValues[1]}\"${m.groupValues[2]}\":" }
        t = BARE_OBJECT.replace(t) { m ->
            "\"" + m.groupValues[1].trim().replace("\"", "\\\"") + "\""
        }
        return t
    }

    companion object {
        /**
         * Object keys a model may wrap the reply text in. `content` and
         * `strategy` were observed live from mimo when the prompt did not pin
         * the output shape — `content` holds the reply, `strategy` the rationale.
         */
        private val TEXT_KEYS = listOf("text", "content", "reply", "value", "message")

        /** `{text:` / `,text:` -> `{"text":` (unquoted object keys). */
        private val KEY_UNQUOTED = Regex("""([{,])\s*([A-Za-z_][A-Za-z0-9_]*)\s*:""")

        /**
         * `{some words}` -> `"some words"`. Deliberately excludes anything
         * containing `:` or a nested brace, so an already-quoted key/value pair
         * is never swallowed by this rule.
         */
        private val BARE_OBJECT = Regex("""\{([^{}:]*)\}""")

        /** `"..."` / `“...”` / `'...'` runs of real content. */
        private val QUOTED = Regex("""["“”']([^"“”']{2,})["“”']""")
    }
}
