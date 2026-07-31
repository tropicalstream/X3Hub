package com.x3hub.app.core.bridge

/**
 * Whether the page agent is currently WORKING — from dispatch to terminal
 * (done, failed, refused, stopped, or its window destroyed).
 *
 * Exists for the wearer's eyes, not for control flow: the agent can run for
 * a minute with nothing on screen to show for it, and in dim mode the
 * battery-and-time readout is the only surface there is. A small glyph next
 * to the battery is how "it is still working" and "it silently died" stop
 * looking identical.
 *
 * Set only by PageAgentController. The listener is the activity's, for
 * repainting the HUD strip and the dim readout on change; state changes
 * arrive on the main thread already, but the flag is volatile so the tool
 * coroutine could read it too.
 */
object AgentActivityBridge {

    @Volatile var busy: Boolean = false
        private set

    @Volatile private var listener: ((Boolean) -> Unit)? = null

    fun setListener(l: ((Boolean) -> Unit)?) {
        listener = l
    }

    fun set(working: Boolean) {
        if (busy == working) return
        busy = working
        listener?.invoke(working)
    }
}
