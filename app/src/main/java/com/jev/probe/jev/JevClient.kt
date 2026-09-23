package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(prefs: Prefs) {

    private val prefs = prefs
    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        target: String? = null
    ): Analysis = judgeClient.judge(snapshot, relationship, ctx, target)

    /** Draft candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        target: String? = null
    ): List<RankedReply> {
        val candidates = replyClient.draft(snapshot, relationship, ctx, target)
        return judgeClient.rank(snapshot, relationship, candidates, ctx, target)
    }

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        target: String? = null
    ): Analysis {
        val a = judge(snapshot, relationship, ctx, target)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx, target) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    /**
     * Who a draft should be addressed to, given the configured target and what
     * was actually captured. Blank config means "whoever spoke last" — in a
     * group that is the only sensible default, and naming nobody is better than
     * naming the wrong person.
     */
    fun resolveTarget(snapshot: ChatSnapshot): String? {
        if (!prefs.groupMode) return null
        if (!snapshot.groupLike) return null
        val configured = prefs.groupTarget.trim()
        if (configured.isBlank()) return snapshot.latest?.speaker
        // Loose match so "老王" still finds "老王（出差中）".
        return snapshot.speakers.firstOrNull { it.contains(configured) || configured.contains(it) }
            ?: configured
    }
}
