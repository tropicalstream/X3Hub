package com.x3hub.app.core.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Pages the app carries with it, served at https://x3hub.local/.
 *
 * The podcast player exists because the podcast SITES failed on this
 * hardware in ways that could not be fixed from outside: listennotes lays
 * out 564px wide in a 320px viewport whatever it is told, and podchaser
 * never hydrates at all — its search page renders a marketing brochure
 * with zero input elements (both measured, see PageCommands). The answer
 * is a page DESIGNED for a 226px-tall floating window, backed by an API
 * instead of someone else's front-end.
 *
 * Served through shouldInterceptRequest rather than file:// or
 * loadDataWithBaseURL so a player window is an ordinary browser pin: a
 * normal https URL that persists, restores after a restart, and can carry
 * a ?q= query. The host is invented, resolves nowhere, and never touches
 * the network — the interceptor answers before any lookup happens.
 */
object LocalPages {

    const val HOST = "x3hub.local"
    const val PLAYER_URL = "https://x3hub.local/podplayer.html"

    /** Serve an app page, or null when the request is not ours. */
    fun serve(context: Context, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.host != HOST) return null
        val name = url.lastPathSegment ?: return null
        // Assets only, by exact leaf name: no path traversal to reason
        // about, and an unknown page 404s as an empty response rather than
        // leaking a real network fetch to a host that does not exist.
        return runCatching {
            val stream = context.assets.open(name)
            WebResourceResponse(mimeFor(name), "utf-8", stream)
        }.getOrElse {
            WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.')) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}
