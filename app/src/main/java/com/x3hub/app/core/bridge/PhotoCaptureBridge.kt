package com.x3hub.app.core.bridge

/**
 * Whether the voice shutter is currently WORKING — from the tool handing
 * the capture off to the photo landing (or failing) in storage and on
 * the board.
 *
 * Same shape and same reason as [AgentActivityBridge], kept separate so
 * neither can clear the other's glyph: a capture takes seconds, the page
 * agent takes a minute, and both can be true at once. The wearer just
 * sees ⚙ for as long as anything is still working.
 */
object PhotoCaptureBridge {

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
