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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
