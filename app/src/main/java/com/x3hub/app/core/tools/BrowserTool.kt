package com.x3hub.app.core.tools

import android.content.Context
import android.net.Uri
import com.x3hub.app.core.bridge.HudPinStore
import java.net.URLEncoder
import java.util.Locale

/**
 * open_browser — Gemini's way of putting a live web page on the HUD
 * ("open archive.org", "look up the tide times").
 *
 * A browser window is nothing more than a [HudPinStore] pin of type
 * [TYPE_BROWSER] whose payload is the URL to load. The tool runs on a
 * voice-tool coroutine with no activity reference and no main thread, so
 * it cannot build a WebView; it writes the pin and the board's store
 * observer inflates the window on the UI thread. Same path HudPinTool
 * uses for notes and live cards — deliberately no second bridge, because
 * two mechanisms means two places for the board to fall out of sync.
 *
 * Everything about how the window BEHAVES (one click to activate, double
 * tap to modify/resize, triple tap to leave) lives in the board
 * controller. This file owns only what a voice call needs to decide:
 * where to point the window, what to call it, and whether there is room
 * for another one.
 */
class BrowserTool(private val context: Context) : AiTapTool {

    override val name = "open_browser"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        HudPinStore.init(context)

        val url = arg(args, "url", "link", "address")
        val query = arg(args, "query", "search", "text", "q")
        // "open it in light mode". Dark is the default because black is
        // transparent on the waveguide and a white page is a lamp in the
        // wearer's eye — but the darkening is a paint transform the page
        // knows nothing about, and on a site it mishandles the text can come
        // out the colour of its own background. Which pages need the
        // exception is a judgement about the page in front of you, so the
        // wearer makes it out loud when they open one.
        val light = arg(args, "mode", "appearance", "theme", "colors", "colours")
            .lowercase().let { it.contains("light") || it.contains("normal") }

        val target: String = when {
            url.isNotBlank() -> resolveUrl(url) ?: return Result.failure(
                IllegalArgumentException(
                    "Can't open '$url' — only http and https pages work here. " +
                        "Give a normal web address, or pass 'query' to search instead."
                )
            )
            query.isNotBlank() -> searchUrl(query)
            // Neither argument given ("open a browser"). The home page is the
            // search engine, so the user lands somewhere they can act from.
            else -> HOME_URL
        }

        val label = when {
            url.isNotBlank() -> hostLabel(target)
            query.isNotBlank() -> shortLabel(query)
            else -> "web"
        }

        val open = browserPins()
        val alreadyOpen = open.firstOrNull { it.payload == target }
        if (alreadyOpen == null && open.size >= MAX_BROWSER_WINDOWS) {
            // Each window is a full WebView — renderer process, network
            // stack, its own JS heap — and three of them already cover most
            // of a 640×480 eye. Refusing out loud beats quietly thrashing.
            return Result.success(
                "There are already $MAX_BROWSER_WINDOWS browser windows open (" +
                    open.joinToString(", ") { it.label } + "). Ask the user to close one " +
                    "first — double-tap a window and tap the ✕ — then try again."
            )
        }

        val added = HudPinStore.add(
            HudPinStore.HudPin(
                type = TYPE_BROWSER,
                label = label,
                payload = target,
                // Re-opening a URL that is already on the board replaces the
                // existing pin in place (HudPinStore.add dedupes on type +
                // payload), and a replacement carries the NEW pin's fields.
                // Carrying the old size index forward is what stops "open
                // archive.org" from silently shrinking a window the user
                // had already resized.
                // Keep the size the wearer had already chosen, but let a
                // fresh request restate the appearance — asking for the same
                // page "in light mode" has to mean something.
                content = encodeSizeIndex(
                    alreadyOpen?.let { sizeIndexOf(it) } ?: BASE_SIZE_INDEX,
                    light = light
                )
            )
        )
        if (!added) {
            // The 10-pin board cap, not the browser cap — notes and cards
            // filled it. Name the difference so the model gives useful advice.
            return Result.success(
                "The HUD board is full (${HudPinStore.MAX_PINS} pins), so there's no room " +
                    "for a browser window. Ask the user which pin to remove first."
            )
        }

        val where = when {
            url.isNotBlank() -> "on $label"
            query.isNotBlank() -> "searching for $query"
            else -> "on the search page"
        }
        // Spoken aloud verbatim, so: one sentence, no punctuation the TTS has
        // to guess at. The interaction hint rides along only on the first
        // window, where the user has nothing on screen to have learnt it from.
        val hint = if (open.isEmpty()) " Click it once to use it." else ""
        return Result.success("Opened a browser window $where.$hint")
    }

    // ------------------------------------------------------------------
    // Argument handling
    // ------------------------------------------------------------------

    /**
     * First non-blank value among [keys]. ToolDispatcher stringifies every
     * JSON value, so a null argument arrives as the four characters "null"
     * rather than as absent — treat that as absent or every un-filled
     * optional would look like a real request.
     */
    private fun arg(args: Map<String, String>, vararg keys: String): String {
        for (k in keys) {
            val v = args[k]?.trim().orEmpty()
            if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) return clean(v)
        }
        return ""
    }

    /** Dictation and model output both arrive wrapped or full-stopped. */
    private fun clean(raw: String): String =
        raw.trim().trim('"', '\'', '“', '”', '‘', '’').trim().trimEnd('.').trim()

    // ------------------------------------------------------------------
    // URL resolution
    // ------------------------------------------------------------------

    /**
     * Normalise what the model called a URL, or return null when it is not
     * safe to load.
     *
     * Only http and https survive. The model is frequently repeating text it
     * read off a web page, so javascript:, data:, file:, intent: and content:
     * are all reachable from untrusted input — and each of them, handed to a
     * WebView, does something considerably worse than show a page.
     */
    private fun resolveUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val schemeEnd = s.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = s.substring(0, schemeEnd).lowercase(Locale.US)
            return if (scheme == "http" || scheme == "https") s else null
        }
        // Bare host, with or without a path: "archive.org", "bbc.co.uk/news".
        // The pattern also rejects the slash-less schemes — "javascript:…",
        // "data:…", "intent:…" — since it permits a colon only in front of a
        // port number.
        if (!HOST_RE.matches(s)) return null
        return "https://$s"
    }

    private fun searchUrl(query: String): String =
        SEARCH_PREFIX + URLEncoder.encode(query.trim(), "UTF-8")

    /** "https://www.bbc.co.uk/news" → "bbc.co.uk". Falls back to the raw text. */
    private fun hostLabel(url: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        val stripped = host.removePrefix("www.").trimEnd('.')
        return stripped.ifBlank { url.removePrefix("https://").removePrefix("http://") }
            .take(LABEL_MAX_CHARS)
    }

    /** Three words is what fits a window title strip at this size. */
    private fun shortLabel(text: String): String =
        text.trim().split(Regex("\\s+")).take(3).joinToString(" ").take(LABEL_MAX_CHARS)

    companion object {

        /**
         * Pin type for a browser window. Declared here rather than in
         * HudPinStore because the store is shared with other work in flight;
         * the board controller must compare against THIS constant so there is
         * exactly one spelling of the string in the app.
         */
        const val TYPE_BROWSER = "browser"

        /**
         * Three live WebViews is the ceiling. Measured against the viewport
         * rather than memory: three windows at the base size already cover
         * about 40% of a 640×480 eye, and past that the wearer is reading
         * through their own UI.
         */
        const val MAX_BROWSER_WINDOWS = 3

        /** SmartView's default engine, and its home page. */
        const val HOME_URL = "https://duckduckgo.com/"
        private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

        private const val LABEL_MAX_CHARS = 24

        /**
         * Window widths in LOGICAL viewport px (the overlay's 640×480 space,
         * not dp). Heights are always width × 4/3 — portrait 3:4, because a
         * portrait viewport makes pages lay themselves out in mobile mode,
         * which is where they give the most readable lines per pixel.
         *
         * A ladder rather than a scale factor so that resizing is exactly
         * reversible: swipe forward then back returns to the pixel you
         * started on, which a repeated ×1.25 / ÷1.25 does not. Roughly 25%
         * per step; the top rung is trimmed so its 426px height still clears
         * the under-HUD zone (HudPinBoardController.UNDER_HUD_ZONE is 430
         * tall).
         */
        private val WIDTH_LADDER = intArrayOf(110, 138, 170, 212, 264, 320)

        /** Index of the 170×226 default described in the interaction spec. */
        const val BASE_SIZE_INDEX = 2

        /** Valid size indices are 0 until this. */
        val SIZE_STEPS: Int get() = WIDTH_LADDER.size

        /**
         * Window size in logical px for a ladder index, clamped. Allocates a
         * Pair per call, which is fine: it is called when a window is built
         * or resized, never per frame and never per touch move.
         */
        fun sizeAt(index: Int): Pair<Int, Int> {
            val w = WIDTH_LADDER[index.coerceIn(0, WIDTH_LADDER.size - 1)]
            // Integer division, so the base rung is 170×226 exactly as specified.
            return w to (w * 4 / 3)
        }

        /**
         * The size index a browser pin is currently at.
         *
         * It rides in [HudPinStore.HudPin.content], which is free text and
         * inert for every type except live cards. The store has no size field
         * and adding one would mean editing a file three agents are holding;
         * this reuses a persisted string instead of inventing a parallel
         * store that could disagree with the pin list.
         */
        fun sizeIndexOf(pin: HudPinStore.HudPin): Int =
            // substringBefore('|') so the appearance suffix below cannot cost
            // the wearer a resize they had already made — the whole string
            // used to be the number, and a bare toIntOrNull on "2|light"
            // returns null and silently snaps the window back to base size.
            pin.content.trim().substringBefore('|').toIntOrNull()
                ?.coerceIn(0, WIDTH_LADDER.size - 1)
                ?: BASE_SIZE_INDEX

        /**
         * Whether this window shows the site's own colours instead of being
         * darkened. Dark is the default everywhere else, so absence means
         * dark and old pins keep behaving exactly as they did.
         */
        fun isLightMode(pin: HudPinStore.HudPin): Boolean =
            pin.content.contains(LIGHT_SUFFIX, ignoreCase = true)

        /** Value to hand [HudPinStore.updateContent] when persisting a resize. */
        @JvmOverloads
        fun encodeSizeIndex(index: Int, light: Boolean = false): String =
            index.coerceIn(0, WIDTH_LADDER.size - 1).toString() +
                if (light) LIGHT_SUFFIX else ""

        private const val LIGHT_SUFFIX = "|light"

        /** Every browser window currently on the board, in board order. */
        fun browserPins(): List<HudPinStore.HudPin> =
            HudPinStore.all().filter { it.type == TYPE_BROWSER }

        fun isBrowserPin(pin: HudPinStore.HudPin): Boolean = pin.type == TYPE_BROWSER

        /**
         * Host, optionally with a path — deliberately no scheme, no spaces,
         * and a real TLD, so free-text queries ("cuttlefish facts") fall
         * through to search instead of becoming https://cuttlefish facts.
         */
        private val HOST_RE = Regex(
            "^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(?::\\d{1,5})?(?:/\\S*)?$",
            RegexOption.IGNORE_CASE
        )
    }
}
