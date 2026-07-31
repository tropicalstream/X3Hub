package com.x3hub.app.core.agent

/**
 * Carries a page errand from the voice tool to the activity.
 *
 * The tool runs on a voice-tool coroutine with no activity reference —
 * same constraint BrowserTool has — but unlike a pin, an errand cannot
 * be expressed as a store write: it has to reach a live WebView. So this
 * is the same shape as HudStateBridge, a single listener the activity
 * installs while it is resumed.
 *
 * The errand is TYPED, not English. It used to be a task string, and a
 * resolved URL shipped as "go to https://…" had to survive re-parsing by
 * the router's URL regex on the far side — a third resolver, hidden in
 * prose, that silently demoted any URL shape the regex missed to an LLM
 * agent task. The bridge now says what it means: navigate here, then do
 * this, on that window.
 *
 * Deliberately last-write-wins with no queue. Two errands in flight
 * would race for one window, and a wearer who says something twice means
 * the second one.
 */
object AgentTaskBridge {

    /**
     * One errand for one window.
     *
     * @param navigateTo Load this URL first, or null when the errand is
     *   about the page already showing. The listener performs the load
     *   itself and gates [task] on ITS OWN load settling — not on the URL
     *   having changed, which never happens when the window is already
     *   at the destination.
     * @param task What to do on the page, in the wearer's words, routed
     *   through the page-command router. Null means the navigation IS the
     *   whole errand.
     * @param windowPinId The exact window, by pin identity — set when the
     *   caller just created or found the pin. Beats every heuristic:
     *   a name match fails exactly when the window has not loaded yet, or
     *   when the URL does not spell the site's name.
     * @param windowHint The window by NAME ("radio garden") when the
     *   caller knows which site the errand is about but not the pin.
     *   Matched with non-alphanumerics stripped, against the window's URL.
     */
    data class PageErrand(
        val navigateTo: String? = null,
        val task: String? = null,
        val windowPinId: String? = null,
        val windowHint: String? = null,
    )

    @Volatile private var listener: ((PageErrand) -> Unit)? = null

    /** Installed by the activity in onCreate; cleared in onDestroy. */
    fun setListener(l: ((PageErrand) -> Unit)?) {
        listener = l
    }

    /** True when a listener was there to take it — and the errand says something. */
    fun request(errand: PageErrand): Boolean {
        if (errand.navigateTo == null && errand.task == null) return false
        val l = listener ?: return false
        l(errand)
        return true
    }

    /** Convenience for the plain "do this on the current page" callers. */
    fun request(task: String, windowHint: String? = null): Boolean =
        request(PageErrand(task = task, windowHint = windowHint))
}
