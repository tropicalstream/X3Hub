package com.x3hub.app.core.bridge

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Asks the activity to bookmark whatever page is on screen.
 *
 * Unlike [com.x3hub.app.core.agent.AgentTaskBridge], this one carries a
 * result back. It has to: capturing a thumbnail means drawing a View, which
 * only the main thread may do, while voice tools run on Dispatchers.IO with
 * no activity — and the assistant then has to say something true about what
 * happened. "Saved The Cat article" and "there is no page open" are
 * different sentences, and the tool cannot tell which is right without
 * hearing back.
 *
 * The timeout is not decoration. If the activity is gone, or the handler
 * throws before answering, a plain await would hang the tool coroutine and
 * the wearer would get silence with no explanation.
 */
object BookmarkBridge {

    data class Saved(
        val ok: Boolean,
        val title: String? = null,
        val error: String? = null,
        /** Whether a HUD pin was actually created — the spoken reply must not
         * claim one that was not: the wearer will go looking for it. */
        val pinned: Boolean = false,
    )

    /** The activity's side: do the work, then call back exactly once. */
    @Volatile private var handler: ((reply: (Saved) -> Unit) -> Unit)? = null

    fun setHandler(h: ((reply: (Saved) -> Unit) -> Unit)?) {
        handler = h
    }

    suspend fun bookmarkVisiblePage(timeoutMs: Long = 8_000): Saved {
        val h = handler
            ?: return Saved(false, error = "The HUD is not running, so there is nothing to save.")
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var answered = false
                runCatching {
                    h { saved ->
                        // A handler that replies twice must not crash the
                        // tool — resuming a finished continuation throws.
                        if (!answered) {
                            answered = true
                            if (cont.isActive) cont.resume(saved)
                        }
                    }
                }.onFailure {
                    if (!answered) {
                        answered = true
                        if (cont.isActive) {
                            cont.resume(Saved(false, error = "Could not reach the page."))
                        }
                    }
                }
            }
        }
        return result ?: Saved(false, error = "Saving the page timed out.")
    }
}
