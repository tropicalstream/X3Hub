package com.x3hub.app.core.agent

/**
 * Which spoken errands are questions ABOUT a video, and what the question
 * actually is once the routing words are taken off.
 *
 * Pulled out of the activity so it can be tested without a device: the
 * first cut lived inline, used RegexOption.COMMENTS with '|' line
 * continuations, and collapsed into an empty alternative that matched
 * everything — so every request was classified as an action and the
 * native path silently never ran. Six of six live cases failed before
 * anyone could see why. A pure function with a test is the fix that
 * outlasts the bug.
 */
object AskRouting {

    /** Requests that ACT on the page; the existing flows own these. */
    private val ACTS = Regex(
        "\\b(play|pause|stop|mute|unmute|skip|next|previous|open|search|scroll|" +
            "subscribe|like|share|fullscreen|volume|close|go to)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Shapes a question takes when someone speaks it. */
    private val ASKS = Regex(
        "^(summari[sz]e|recap|explain|describe|tell me|what|who|when|where|why|how|" +
            "which|is |are |does |did |can |could |should )",
        RegexOption.IGNORE_CASE
    )

    /**
     * The routing preamble, which is aimed at US and must never be typed
     * into the site's box. Optional by design: the orchestrator is told to
     * hand over just the question, so 'ask YouTube to summarize the video'
     * usually arrives already stripped to 'summarize the video'.
     */
    private val PREFIX = Regex(
        """^\s*ask\s+(?:youtube|the\s+video|yt)\s*(?:to|about|,|:)?\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    /** The bare question, or null when this errand is not a question. */
    fun question(task: String): String? {
        val t = task.trim()
        if (t.isEmpty()) return null
        val stripped = PREFIX.find(t)?.groupValues?.get(1)?.trim() ?: t
        if (ACTS.containsMatchIn(stripped)) return null
        if (!ASKS.containsMatchIn(stripped) && !t.lowercase().startsWith("ask ")) return null
        return stripped.trimEnd('.', '?').trim().takeIf { it.isNotBlank() }
    }
}
