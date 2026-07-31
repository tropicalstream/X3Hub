package com.x3hub.app.core.agent

/**
 * Carries a spoken agent task from the voice tool to the activity.
 *
 * The tool runs on a voice-tool coroutine with no activity reference —
 * same constraint BrowserTool has — but unlike a pin, an agent task cannot
 * be expressed as a store write: it has to reach a live WebView. So this
 * is the same shape as HudStateBridge, a single listener the activity
 * installs while it is resumed.
 *
 * Deliberately last-write-wins with no queue. Two agent tasks in flight
 * would race for one window, and a wearer who says something twice means
 * the second one.
 */
object AgentTaskBridge {

    @Volatile private var listener: ((String, String?) -> Unit)? = null

    /** Installed by the activity in onCreate; cleared in onDestroy. */
    fun setListener(l: ((String, String?) -> Unit)?) {
        listener = l
    }

    /**
     * True when a listener was there to take it. [windowHint] names which
     * window the task is about — matched loosely against window URLs —
     * because the caller sometimes knows ("a station on radio garden")
     * while the resolver's active/latest heuristics can only guess.
     */
    fun request(task: String, windowHint: String? = null): Boolean {
        val l = listener ?: return false
        l(task, windowHint)
        return true
    }
}
