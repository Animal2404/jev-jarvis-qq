package com.jev.probe.core

import android.graphics.Rect

/**
 * One captured chat bubble.
 *
 * [side] is "me" (right) or "other" (left). In a group, [speaker] carries the
 * nickname shown with the bubble and [side] stays "other" for everyone who is
 * not me — so all the single-party logic downstream keeps working unchanged,
 * while the group-aware paths (judgment, prompts, panel) can name who spoke.
 */
data class Msg(val side: String, val text: String, val speaker: String? = null)

/**
 * A bubble the node tree can locate but not read (Feishu draws its message text
 * itself). [rect] is in screen coordinates; [side] is what the tree could infer
 * around the bubble. The service OCRs each rect to get the words.
 */
data class BubbleRect(val rect: Rect, val side: String)

/**
 * A snapshot of the currently-open conversation in whichever chat app is
 * foreground (see ChatAppAdapter).
 *
 * Adapter contract: `extract` returning null means "not in a chat window".
 * Returning a snapshot whose [messages] is empty means "in a chat window, but
 * the tree holds no text" — that is the OCR fallback's cue, and the one case
 * where [bubbleRects] may be populated.
 *
 * [note] is a caveat about how this snapshot was produced, shown verbatim in
 * the analysis panel (OCR captures cannot tell who said what).
 *
 * [isGroup] is set by adapters that can positively tell a multi-party chat from
 * a 1:1 one. It is deliberately nullable-by-default only in the sense of being
 * a plain Boolean: adapters that cannot tell leave it false, and the service
 * additionally infers "group-ish" from the presence of speaker names.
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null,
    val isGroup: Boolean = false
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** The last message, if any. */
    val latest: Msg? get() = messages.lastOrNull()

    /**
     * Nicknames seen in this snapshot, newest last, de-duplicated. Empty for a
     * 1:1 chat (no adapter records a speaker there).
     */
    val speakers: List<String>
        get() = messages.mapNotNull { it.speaker?.takeIf { s -> s.isNotBlank() } }
            .distinct()

    /**
     * Whether the analysis should treat this as multi-party: either an adapter
     * said so, or several distinct people are speaking in the captured window.
     */
    val groupLike: Boolean get() = isGroup || speakers.size >= 2

    /** A stable signature of the last few messages, to detect real changes. */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") { "${it.side}:${it.speaker.orEmpty()}:${it.text}" }
}

/** Jev's judgment result for one snapshot, plus the ranked candidate replies. */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)

/**
 * A candidate reply. [prob] is Jev's ranking probability, or null when the
 * candidates were never ranked (fewer than 3 real ones came back — ranking
 * would otherwise have to invent a winner among placeholders).
 */
data class RankedReply(val text: String, val prob: Double?)
