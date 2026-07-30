package com.x3hub.app.core.config

import android.content.Context

/**
 * Small non-secret settings, in the same SharedPreferences file the API
 * keys use. Separate object because a key and a preference have nothing
 * in common but their storage: keys are pushed from a computer and
 * masked on screen, preferences are toggled on the glasses.
 */
object HubPrefs {

    private const val PREFS_FILE = "x3hub_config"
    private const val PREF_BARGE_IN = "voice_barge_in"
    private const val PREF_LINK_RESEARCH = "link_research"
    private const val PREF_GEMINI_STT = "page_agent_gemini_stt"   // legacy boolean
    private const val PREF_STT_MODE = "page_agent_stt_mode"

    /** Who transcribes the page agent's spoken tasks. */
    const val STT_GROQ = "groq"
    const val STT_GEMINI = "gemini"
    const val STT_AB = "ab"

    /**
     * GROQ (default) — Whisper, a dedicated transcriber, on a path where the
     * wearer has stopped speaking and is waiting for the agent to move.
     *
     * GEMINI — one key runs the whole app. This is what happens anyway when
     * no Groq key is configured; the setting only decides which to prefer
     * when both exist.
     *
     * AB — send the SAME recording to both and log the two transcripts with
     * their timings. Comparing by flipping a switch between utterances
     * compares different audio as much as it compares transcribers; the only
     * honest comparison gives them the same words to hear. Groq's answer is
     * the one that reaches the agent, so turning this on does not change
     * behaviour — it only costs a second request.
     */
    fun sttMode(context: Context): String =
        prefs(context).getString(PREF_STT_MODE, null)
            ?: if (prefs(context).getBoolean(PREF_GEMINI_STT, false)) STT_GEMINI else STT_GROQ

    fun setSttMode(context: Context, mode: String) {
        prefs(context).edit().putString(PREF_STT_MODE, mode).apply()
    }

    /** Cycles Groq → Gemini → A/B → Groq, for a one-button settings row. */
    fun nextSttMode(current: String): String = when (current) {
        STT_GROQ -> STT_GEMINI
        STT_GEMINI -> STT_AB
        else -> STT_GROQ
    }

    /**
     * Whether the wearer can talk over Gemini mid-reply.
     *
     * ON (default) is the natural conversation: start speaking and the
     * reply stops. It costs something real though — the glasses have no
     * platform AEC on the raw mic, so Gemini's own voice comes back in
     * through the microphone, and anywhere it is loud enough to clear the
     * echo gate the model interrupts ITSELF and transcribes its own words
     * as the wearer's. That is not hypothetical: it is what happens on a
     * desk next to a monitor speaker, and it makes the session unusable.
     *
     * OFF is half-duplex — the mic is not forwarded at all while Gemini
     * is speaking. Turns cannot overlap, so nothing can echo, at the cost
     * of having to wait for the reply to finish.
     */
    fun bargeInEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_BARGE_IN, true)

    fun setBargeInEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_BARGE_IN, enabled).apply()
    }

    /**
     * Swap Google Search grounding for Gemini's URL-reading tool.
     *
     * This is a CHOICE, not a feature flag, because the Live API refuses to
     * take both — a setup carrying googleSearch and urlContext together is
     * closed with "Search tool, and Url Context tool are not supported
     * together", verified against the live endpoint. So the session runs
     * with one or the other:
     *
     *   OFF (default)  googleSearch — the assistant can answer about
     *                  current things it was never trained on, which is
     *                  most of what a wearer asks aloud.
     *   ON             urlContext — the assistant can FETCH AND READ links
     *                  it is given or finds on a page, at the cost of not
     *                  being able to search the web at all.
     *
     * Read at connect time, so it takes effect on the next session.
     */
    fun linkResearchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_LINK_RESEARCH, false)

    fun setLinkResearchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_LINK_RESEARCH, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
