package com.x3hub.app.core.bridge

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Lets the assistant read and control the window the wearer has selected.
 *
 * Everything here needs a live WebView on the main thread, and voice tools
 * run on Dispatchers.IO with no activity — so, like [BookmarkBridge], the
 * request crosses over and the answer comes back. Reading is inherently
 * asynchronous anyway (evaluateJavascript answers on a callback), which is
 * why the handler is given a reply function rather than returning a value.
 *
 * One bridge for read AND control on purpose: they share the same "which
 * window did the wearer pick" rule, and splitting them would mean two
 * copies of it that could disagree.
 */
object WindowBridge {

    data class Reply(val ok: Boolean, val text: String)

    /** action → argument → reply exactly once. Installed by the activity. */
    @Volatile private var handler: ((String, String, (Reply) -> Unit) -> Unit)? = null

    fun setHandler(h: ((String, String, (Reply) -> Unit) -> Unit)?) {
        handler = h
    }

    suspend fun request(action: String, arg: String = "", timeoutMs: Long = 8_000): Reply {
        val h = handler ?: return Reply(false, "The HUD is not running.")
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var answered = false
                fun once(r: Reply) {
                    if (answered) return
                    answered = true
                    if (cont.isActive) cont.resume(r)
                }
                runCatching { h(action, arg) { r -> once(r) } }
                    .onFailure { once(Reply(false, "Could not reach the page.")) }
            }
        }
        return result ?: Reply(false, "The page did not respond in time.")
    }
}
