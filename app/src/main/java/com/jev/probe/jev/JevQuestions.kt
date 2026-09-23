package com.jev.probe.jev

import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.kb.LogEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed Jev question set, ported verbatim from tools/jev/questions.py (the
 * wording that passed calibration). Instructions/criteria in English; chat text
 * stays Chinese. The state `from` field uses "me"/"other" (the instructions
 * already refer to "the other person" throughout).
 */
object JevQuestions {

    /**
     * Appended to every question so the D-stage `background` field (relationship,
     * contact notes, knowledge-base hits) reads as given context rather than as
     * an off-topic digression that should be penalized.
     */
    const val BACKGROUND_NOTE =
        " Facts given in background are provided context, not off-topic."

    /**
     * Appended when the capture looks like a group chat. Without it every
     * question's "the other person" silently collapses several participants
     * into one, so the judgment describes a person who does not exist (the
     * group-chat confusion the user reported). The state carries
     * `chat.is_group` / `chat.latest_speaker`; this tells the model to use them.
     */
    const val GROUP_NOTE =
        " IMPORTANT: this is a multi-party group chat (state.chat.is_group is true)." +
            " Every message carries a `speaker` nickname; messages with speaker names" +
            " other than the latest one are other members, not the same person." +
            " Judge the intent of state.chat.latest_speaker ONLY — the one who sent the" +
            " latest message — and never attribute an earlier member's words to them." +
            " Group banter and jokes are not relationship conflict; do not read a" +
            " couple-style emotional test into ordinary group chatter."

    /**
     * The trailing note for one question set. Group captures get BACKGROUND_NOTE
     * plus GROUP_NOTE; 1:1 captures keep exactly the wording that passed
     * calibration, with no group language to confuse the judgment.
     */
    private fun noteFor(group: Boolean): String =
        if (group) BACKGROUND_NOTE + GROUP_NOTE else BACKGROUND_NOTE

    /**
     * Name the person the judgment must be about, when the user pinned one.
     * In a group the "latest message" and the person you actually want to
     * answer can differ (someone else posted after them), so saying it outright
     * beats hoping the model infers it.
     */
    private fun focusNote(focusSpeaker: String?): String {
        val s = focusSpeaker?.trim().orEmpty()
        if (s.isEmpty()) return ""
        return " The user wants the judgment to be about the group member named" +
            " \"$s\" — read that member's own messages, not the room's mood in general."
    }

    /**
     * The trailing note appended to every question in the CURRENT judge() call.
     *
     * Held as a field and set once at the top of [judge], rather than passed as
     * a parameter to each of the 7 helpers: threading it through every call site
     * is what broke the previous attempt (a mis-placed `, note` turned into a
     * third argument to `put(...)`, which takes two, and the whole file failed
     * to compile). One assignment cannot go wrong seven ways.
     *
     * [judge] is the only caller and it always sets this before building any
     * question, so the default here is only a safety net.
     */
    private var activeNote: String = BACKGROUND_NOTE

    private fun noul(instructions: String, t: String, f: String) =
        JSONObject().apply {
            put("type", "noul")
            put("instructions", instructions + activeNote)
            put("criteria", JSONObject().put("true", t).put("false", f))
        }

    private fun choice(instructions: String, criteria: Map<String, String>) =
        JSONObject().apply {
            put("type", "choice")
            put("instructions", instructions + activeNote)
            put("criteria", JSONObject().also { c -> criteria.forEach { (k, v) -> c.put(k, v) } })
        }

    private fun score(instructions: String, levels: List<String>) =
        JSONObject().apply {
            put("type", "score")
            put("instructions", instructions + activeNote)
            put("criteria", JSONArray().also { a -> levels.forEach { a.put(it) } })
        }

    /** The 7 judgment questions. Returns a fresh JSONObject each call.
     *
     * @param group read the room as multi-party (see [GROUP_NOTE]).
     * @param focusSpeaker in a group, the nickname this judgment is about
     *        (blank = whoever sent the latest message). */
    fun judge(group: Boolean = false, focusSpeaker: String? = null): JSONObject {
        // Set before any question is built; every helper reads it.
        activeNote = noteFor(group) + focusNote(focusSpeaker)
        return JSONObject().apply {
        put("literal_question", noul(
            "Is the other person's latest message meant purely literally, with no subtext? " +
                "Judge from the whole thread, not one sentence in isolation.",
            "The latest message is a straightforward statement, question, or plan " +
                "with no implied accusation, test, sarcasm, hint, or unsaid request.",
            "There is subtext: a test of whether you remember or care, sarcasm, " +
                "an implied complaint, a hint they will not say outright, a trap question, " +
                "an accusation dressed as a question, or a cold/short line that really means blame."
        ))
        put("true_intent", choice(
            "What is the other person's true intent in the latest message, given the full conversation? " +
                "Prefer tone and context over surface wording. " +
                "If they are checking whether you remember something or still care, choose confirm_you_care " +
                "even if the words look like a request to 'say it' or to do something. " +
                "If they already accepted and closed the matter peacefully, choose close_topic. " +
                "Ending the relationship, deleting you, or 'don't talk to me' is vent_anger, never close_topic.",
            linkedMapOf(
                "confirm_you_care" to ("They are testing whether you remember, pay attention, or still care. " +
                    "Signals: 'did you forget again', 'then say it', 'you better', sarcastic 'busy person', " +
                    "asking you to prove you know a past conversation. " +
                    "If they mainly want a new deliverable or a yes on a time, do not use this."),
                "vent_anger" to ("They are angry or hurt and mainly want the feeling acknowledged. " +
                    "They are blaming or raising the temperature; a specific plan is not the main point yet."),
                "request_action" to ("They want a concrete action, time, deliverable, or commitment from you now, " +
                    "and this is a real ask, not a loyalty test."),
                "seek_explanation" to ("They want a factual explanation of why something happened. " +
                    "They asked why or what is going on, not mainly for an apology or a new plan."),
                "casual_chat" to ("Light talk, banter, sharing, teasing with a laugh, or friendly logistics " +
                    "with no emotional test and no conflict. A friend suggesting a meal time can be this " +
                    "if the thread is warm."),
                "close_topic" to ("Peaceful wrap-up only: they accepted an apology, confirmed a happy plan, said thanks, " +
                    "or clearly signaled they need nothing more. " +
                    "Not a breakup, not 'don't contact me', not sarcastic 'I'm used to it'.")
            )
        ))
        put("danger_level", score(
            "How close is this conversation to a fight or to hurting the relationship? " +
                "Match the current scene. " +
                "If they genuinely accepted an apology or confirmed a happy plan, score the cooled-down present, " +
                "not an earlier complaint. " +
                "If an ultimatum (break up, report to the boss, stop covering for you) is still in force " +
                "and has not been withdrawn, stay in that high bin even if the latest line names a specific task.",
            listOf(
                "Light chat or joking; no complaint, no test, no deadline.",
                "Mild tease or a small reminder that is easy to laugh off; a clumsy reply would only feel slightly awkward.",
                "A mild complaint or 'please remember next time' said without heat; they still send warm or practical follow-ups.",
                "Noticeable unhappiness; they mention being forgotten, ignored, or kept waiting, but still give you a chance to make it right.",
                "Sarcasm, cold short replies, or 'you better'; they are testing you, and a sloppy or fake-confident reply will escalate.",
                "Openly upset; they accuse you of not listening or not caring; they expect a real response, not a joke.",
                "Clearly angry and blaming you; a wrong reply will turn this into a fight.",
                "Last-chance warning. They will not cover for you, do not want to keep talking unless this changes, " +
                    "or tell you to finish a named checklist yourself because trust is almost gone.",
                "An ultimatum is already on the table even if they also give a practical next step: " +
                    "break up if you forget again, report you tonight, or stop working together if you miss this.",
                "Active rupture: they said it is over, told you not to reply, deleted you, or are exploding."
            )
        ))
        put("should_reply_now", noul(
            "Should your next message contain substantive content? " +
                "Substantive means: admitting a specific known fault, giving a concrete time/plan/deliverable, " +
                "explaining facts you actually know, or reciting the recalled content they asked you to say. " +
                "This is NOT 'should you send any message'. Timing is irrelevant. " +
                "Answer FALSE if the thing they want you to recite or prove is not present in this snippet " +
                "(you would be guessing). 'Then say it' / 'you better' while you are stalling is FALSE. " +
                "Answer FALSE if they already accepted and closed the topic. " +
                "Answer true only if the needed fact, plan, or named fault is already in this snippet.",
            "The needed fact, named fault, or named time/place is already in this snippet, " +
                "and they are waiting for that substance now.",
            "Do not put substance in the next message: the recalled content is not in this snippet, " +
                "they are testing whether you remember, a holding line is enough, " +
                "saying less is safer, or they already closed the topic."
        ))
        put("best_action", choice(
            "What type of next action is best? Do not decide whether to send a message immediately. " +
                "Ignore timing. Choose only the action type. " +
                "If they asked you to recall a specific past message or event and you have not shown that you actually remember it, " +
                "choose check_history - do not apologize or invent a plan instead.",
            linkedMapOf(
                "check_history" to ("Look up prior chat or facts before taking a position. " +
                    "Use when they ask you to repeat, recall, or prove you remember something specific."),
                "apologize" to ("Lead with a sincere apology for a real mistake or hurt already identified. " +
                    "Not for an unnamed forgotten thing when you should first find out what it was."),
                "give_commitment" to ("Give a concrete promise, deadline, or arrangement they asked for " +
                    "in a conflict or work-pressure setting."),
                "explain" to ("Explain what happened or why, without leading with apology or a new plan."),
                "acknowledge" to ("Show you heard them and care, without new facts, an apology, or a plan. " +
                    "Use for light chat or when they mainly need to feel seen."),
                "say_less" to ("Keep it short or add nothing. Extra words would over-explain, reopen a closed topic, " +
                    "or pour fuel on an ultimatum that told you not to talk."),
                "make_plan" to ("Propose or confirm logistics (time, place, task) for a non-conflict request " +
                    "such as a meal or a meeting.")
            )
        ))
        put("she_needs", choice(
            "What does the other person need from you right now? Judge the LATEST message first. " +
                "If they genuinely accepted (thanks / got it / 没事了 / 那就这样 / 收到了 / 过去了), " +
                "you MUST choose nothing, even if earlier they wanted action or an apology. " +
                "Sarcastic 'I'm used to it', 'whatever', 'I don't want to hear it', 'don't bother coming' " +
                "is NOT genuine satisfaction - do not choose nothing. " +
                "If they asked you to recap a named time/place/date, choose action. " +
                "If they are testing whether you remember or still care, and the content is unnamed, choose care.",
            linkedMapOf(
                "apology" to "They need a sincere apology for hurt or a mistake, and they have not accepted one yet.",
                "action" to ("They need a concrete action, time, commitment, recap of a named fact, or follow-through, " +
                    "and they have not yet accepted one."),
                "explanation" to "They need a clear explanation of what happened or why, and have not received it.",
                "care" to ("They need proof you remember, listen, or care - a loyalty or attention test - " +
                    "not yet a plan or an apology. Sarcastic 'I am used to it' belongs here, not nothing."),
                "nothing" to ("They need nothing further. Genuine acceptance, a peaceful closed topic, " +
                    "warm casual chat with no ask, or a rupture where they told you not to reply. " +
                    "Not sarcasm pretending to be fine.")
            )
        ))
        put("tension_resolved", noul(
            "Has interpersonal tension already been resolved? " +
                "Answer true only if there was never tension, or the other person has clearly accepted, " +
                "cooled down, joked again, or said it is fine. " +
                "A sarcastic 'you better', an unanswered test, leftover blame, or an open ultimatum means false.",
            "No remaining tension: they accepted, joked again, said it's fine, " +
                "confirmed a happy plan, or the chat was never tense.",
            "Tension is still present: they are waiting, testing, angry, sarcastic, " +
                "issuing an ultimatum, or the issue is open."
        ))
        }
    }

    /**
     * Build Jev state from a snapshot (last 10 messages), optionally carrying
     * the D-stage knowledge context.
     *
     * @param background relationship + contact notes + matched knowledge notes.
     * @param history older messages for this contact, already de-duplicated
     *        against what is on screen.
     *
     * Both extra fields are omitted when empty, so a user with no knowledge base
     * sends exactly the same body v1.2 did.
     */
    fun buildState(
        snapshot: ChatSnapshot,
        relationship: String,
        background: String = "",
        history: List<LogEntry> = emptyList()
    ): JSONObject {
        val msgs = JSONArray()
        val last10 = snapshot.messages.takeLast(10)
        for (m in last10) {
            val o = JSONObject().put("from", m.side).put("text", m.text)
            // In a group every non-me bubble has side "other", so without the
            // nickname Jev would read five different people as one person and
            // judge "their" intent as if it were a single thread.
            m.speaker?.takeIf { it.isNotBlank() }?.let { o.put("speaker", it) }
            msgs.put(o)
        }
        val chat = JSONObject()
            .put("relationship", relationship)
            .put("messages", msgs)
            .put("latest_from", last10.lastOrNull()?.side ?: "other")
        val latestSpeaker = last10.lastOrNull()?.speaker
        if (!latestSpeaker.isNullOrBlank()) chat.put("latest_speaker", latestSpeaker)
        // Tells the judgment questions to read this as a room, not a dialogue.
        if (snapshot.groupLike) {
            chat.put("is_group", true)
            chat.put("group_name", snapshot.title.orEmpty())
        }
        val state = JSONObject().put("chat", chat)
        if (background.isNotBlank()) state.put("background", background)
        if (history.isNotEmpty()) {
            val h = JSONArray()
            history.forEach { h.put(JSONObject().put("from", it.side).put("text", it.text)) }
            state.put("history", h)
        }
        return state
    }

    /**
     * The best_reply ranking question over exactly 3 candidates (Chinese text
     * kept). Group captures get wording that judges "fits this room" instead of
     * "fits this relationship" — a reply that is right for a couple is often
     * absurd in a group, which is what made rankings look wrong there.
     */
    fun rankQuestion(
        candidates: List<String>,
        snapshot: ChatSnapshot? = null,
        focusSpeaker: String? = null
    ): JSONObject {
        require(candidates.size == 3) { "rankQuestion expects exactly 3 candidates" }
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val criteria = JSONObject()
        keys.forEachIndexed { i, k -> criteria.put(k, candidates[i]) }
        val group = snapshot?.groupLike == true
        val instructions = buildString {
            if (group) {
                append("Which candidate reply is the most appropriate next message in this group chat, ")
                append("given what the room is talking about right now? ")
                val to = focusSpeaker?.takeIf { it.isNotBlank() }
                if (to != null) append("The reply is aimed at group member \"$to\"; ")
                append("prefer one that lands naturally in a group — short, on-topic, not overly intimate, ")
                append("not a monologue. ")
                append("Penalize replies that read like a private couple's message, that over-promise, ")
                append("that are dismissive, or that answer a different member than the one addressed.")
            } else {
                append("Which candidate reply is the most appropriate next message, ")
                append("given the conversation and the other person's true need? ")
                // Deliberately does NOT say "prefer a reply that matches the best
                // action type": that phrasing kept selecting the wordiest,
                // most-committal candidate, which is the assistant register the
                // user flagged as awkward. The ranking now favours the candidate
                // that reads like something a person would actually type.
                append("Prefer the candidate that reads like a real person typing a short message ")
                append("in a chat app: natural, brief, matching the other person's tone. ")
                append("Penalize candidates that sound like an assistant or customer service — ")
                append("preachy, over-explaining, over-promising, or longer than the situation needs. ")
                append("Penalize dismissive or off-topic replies. ")
                append("If the facts are not yet confirmed, prefer the candidate that looks them up ")
                append("instead of faking memory or a vague apology.")
            }
            append(noteFor(group))
        }
        val q = JSONObject().apply {
            put("type", "choice")
            put("instructions", instructions)
            put("criteria", criteria)
        }
        return JSONObject().put("best_reply", q)
    }
}
