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
     * @param target In a group, the person this reply is aimed at (blank = the
     *        one who spoke last). Null or blank in a 1:1 chat.
     */
    fun draft(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        target: String? = null
    ): List<String> {
        val group = prefs.groupMode && snapshot.groupLike
        // How much of the thread the model is shown. Configurable (was a fixed
        // 10): with only the last few lines the drafts answered the final
        // sentence and ignored what the conversation was actually about, which
        // is what made them read as 无厘头.
        val window = snapshot.messages.takeLast(prefs.replyWindow)
        // In a group the transcript must name the speaker, otherwise every
        // bubble reads as the same "对方" and the model answers the wrong person.
        val convo = window.joinToString("\n") { m ->
            val who = when {
                m.side == "me" -> "我"
                group -> m.speaker?.takeIf { it.isNotBlank() } ?: "群友"
                else -> "对方"
            }
            "$who：${m.text}"
        }
        // The persona, the anti-assistant rules and the worked examples all live
        // in PromptLibrary, which documents the evidence behind each rule. The
        // built-in text is always sent; a user's own prompt (if they wrote one)
        // is appended AFTER it, so a single line of custom text cannot override
        // the register.
        val sys = PromptLibrary.compose(
            userPrompt = prefs.userPrompt,
            groupRules = if (group) PromptLibrary.groupRules(snapshot.title.orEmpty(), target) else null
        )
        val user = knowledgeBlock(relationship, ctx) +
            if (group) groupUserBlock(snapshot, relationship, convo, target)
            else "关系：$relationship\n\n" +
                "完整对话（越靠下越新，共 ${window.size} 条，请通读后再回）：\n$convo\n\n" +
                "先判断这段对话在聊什么，再给出 3 条候选回复。"

        // Temperature ~0.95: the human-like end of the usual range. Below that
        // the three candidates converge on one phrasing; far above it the model
        // starts drifting off the conversation.
        val first = extractReplies(chat(sys, user, temperature = 0.95))
        if (first.size >= 3) return first.take(3)

        // One retry with a blunter instruction and a lower temperature. The
        // first attempt losing candidates is common when the model wraps them
        // oddly or answers with prose; asking again is cheaper than padding the
        // list with filler, which is what used to reach the panel as a bogus
        // top-ranked suggestion.
        val retrySys = sys + "重要：必须严格输出 3 条。上一轮你的输出格式不合法，这次只输出方括号和引号，不要任何其他字符。"
        val second = extractReplies(chat(retrySys, user, temperature = 0.4))
        val merged = LinkedHashSet<String>()
        merged.addAll(first)
        merged.addAll(second)
        return merged.toList().take(3)
    }

    /**
     * The group variant of the user turn. The speaker list matters: it tells the
     * model which nicknames are actually in the room, so a draft cannot invent
     * a person who is not there or confuse two similar names.
     */
    private fun groupUserBlock(
        snapshot: ChatSnapshot,
        relationship: String,
        convo: String,
        target: String?
    ): String {
        val sb = StringBuilder()
        sb.append("群名：").append(snapshot.title.orEmpty().ifBlank { "（未识别）" }).append('\n')
        val names = snapshot.speakers
        if (names.isNotEmpty()) {
            sb.append("群里刚发过言的人：").append(names.joinToString("、")).append('\n')
        }
        val to = target?.takeIf { it.isNotBlank() }
        if (to != null) sb.append("这次要回复的人是：").append(to).append('\n')
        sb.append("我和对方的关系（仅供参考，群聊里不一定适用）：").append(relationship).append('\n')
        sb.append("\n完整对话（每行开头是发言人，越靠下越新，请通读后再回）：\n")
        sb.append(convo).append('\n')
        sb.append("\n先判断群里在聊什么，再给出 3 条候选回复。")
        return sb.toString()
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
            // Mild anti-repetition. Three candidates are generated in one call,
            // and without this the model tends to emit the same sentence three
            // ways ("确实尬" / "确实尬了" / "确实挺尬"), which reads as machine
            // output. These are the gentle end of the usual range: enough to
            // break the echo, not enough to start inventing facts.
            .put("frequency_penalty", 0.3)
            .put("presence_penalty", 0.2)
        applyThinking(body, prefs.replyThinking)
        val resp = HttpJson.post(url, prefs.effectiveReplyKey(), body, Route.REPLY, HttpJson.headersFor(url))
        return resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
    }

    /**
     * Tell the model whether it may deliberate before answering.
     *
     * Only the field that the gateway accepts is sent — it rejects unknown
     * fields outright (a live probe of `chat_template_kwargs` returned
     * HTTP 400 UNKNOWN_FIELD, which would have failed every request).
     * `thinking: {"type": "disabled"}` is the one that measurably works on
     * TokenRhythm + mimo: 4480 ms median vs 7258 ms when unset.
     *
     * When thinking is ON, nothing is sent: that is already the model's
     * default, and adding a field it might not know is the risk we avoid.
     */
    private fun applyThinking(body: JSONObject, enabled: Boolean) {
        if (enabled) return
        body.put("thinking", JSONObject().put("type", "disabled"))
        body.put("enable_thinking", false)
    }

    /**
     * Pull the reply candidates out of whatever the model returned.
     *
     * Returns ONLY real candidates (0..3 of them) — never padded with filler.
     * That is a deliberate change: the old version padded to 3 with
     * "（稍等，我看下）", Jev then ranked those placeholders, and one ended up as
     * the top suggestion at 73% in the panel. Fewer honest candidates beat a
     * full set of invented ones; the caller retries once, and the panel says so
     * when it cannot fill the list.
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
    private fun extractReplies(content: String): List<String> {
        val raw = content.trim()
        if (raw.isEmpty()) return emptyList()

        // 1. Strict JSON, and unwrap object elements via their text-ish key.
        strictArray(raw)?.let { arr ->
            clean(collectTexts(arr)).let { if (it.isNotEmpty()) return it }
        }

        // 2. Repair the near-JSON shapes above, then retry strict parsing.
        val fixed = repairJson(raw)
        if (fixed != raw) {
            strictArray(fixed)?.let { arr ->
                clean(collectTexts(arr)).let { if (it.isNotEmpty()) return it }
            }
        }

        // 3. Scrape quoted runs (handles the full-width-quote case cleanly).
        clean(QUOTED.findAll(fixed)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !it.startsWith("{") }
            .toList())
            .let { if (it.isNotEmpty()) return it }

        // 4. Last resort: split on the separators a model uses when it ignores
        //    the JSON request entirely — newlines, and the Chinese list
        //    separators / full-width numbering that broke the old line-only
        //    version (it returned the whole "甲；乙；丙" blob as one reply).
        val parts = fixed.split("\n", "；", ";", "  ", "\u3001")
            .map { stripListFurniture(it) }
            .filter { it.isNotBlank() }
        return clean(parts)
    }

    /**
     * Remove list numbering from one line: `1.` `2、` `3)` `-` `*` etc., plus
     * stray wrapping brackets and quotes. The old code used `trim(...)` with a
     * character set, which also ate meaningful leading characters and left a
     * bare "、" behind on full-width numbering.
     */
    private fun stripListFurniture(line: String): String {
        var t = line.trim()
        t = LEAD_INDEX.replace(t, "")
        t = t.trim().trimStart('-', '*', '\u2022', ' ', '\t')
        t = t.trim('[', ']', '{', '}', '"', '\'', '\u201c', '\u201d')
        return t.trim()
    }

    /** Drop blanks, de-duplicate, keep the first 3 in model order. */
    private fun clean(items: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in items) {
            val t = tidy(raw)
            if (t.isNotEmpty()) seen.add(t)
            if (seen.size == 3) break
        }
        return seen.toList()
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

        /**
         * Leading list numbering: `1.` `2、` `3)` `1、` etc. Handles half- and
         * full-width delimiters, which is what the old `trim('-','*','1'…)`
         * approach mangled (it ate any leading 1/2/3 and left the "、" behind).
         */
        private val LEAD_INDEX = Regex("""^\s*\d{1,2}\s*[.、)）:：]\s*""")
    }
}
