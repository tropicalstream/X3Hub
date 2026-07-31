package com.x3hub.app.core.tools

import android.content.Context
import android.net.Uri
import com.x3hub.app.core.agent.AgentTaskBridge
import com.x3hub.app.core.agent.PageCommands
import com.x3hub.app.core.web.LocalPages
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

        // "open jazz on listennotes" — a subject AND the site to look it up
        // on. Without this the site name could only arrive inside the query,
        // so the wearer standing on a podcast directory got a DuckDuckGo page
        // about podcasts instead of that directory's own results.
        val site = arg(args, "site", "on", "where", "engine")

        // A site with no search URL is not a web search — it is a page
        // errand on that site. "A station on radio garden" used to become a
        // DuckDuckGo search for the station's name; and when the window was
        // not open yet, the first fix still fell through to that search,
        // because it only knew how to use a window that already existed. If
        // the site is open, the errand goes to its window; if the site is
        // KNOWN but closed, it is opened and the errand follows it in — the
        // listener's ensureLoaded holds the task until the page is there.
        var followUpTask: String? = null
        var followUpHint: String? = null
        val siteStem = site.lowercase().replace(Regex("[^a-z0-9]"), "")
        val siteSearchMiss = site.isNotBlank() && query.isNotBlank() &&
            PageCommands.siteSearchUrl(site, query) == null
        if (siteSearchMiss) {
            val open = browserPins().firstOrNull {
                siteStem.isNotEmpty() &&
                    it.payload.lowercase().replace(Regex("[^a-z0-9]"), "").contains(siteStem)
            }
            if (open != null && AgentTaskBridge.request("play $query", windowHint = siteStem)) {
                return Result.success(
                    "Asking the ${open.label} page to play $query. The result will follow."
                )
            }
            // Site closed but known by name: open it and defer the errand.
            spokenHost(site)?.let {
                followUpTask = "play $query"
                followUpHint = siteStem
            }
        }

        val target: String = when {
            followUpTask != null -> "https://${spokenHost(site)}"
            site.isNotBlank() && query.isNotBlank() ->
                PageCommands.siteSearchUrl(site, query)
                    // An unknown site is not a failure: searching the web for
                    // "<query> <site>" is a reasonable answer, and far better
                    // than refusing because the table has no entry.
                    ?: searchUrl("$query $site")
            url.isNotBlank() -> resolveUrl(url) ?: return Result.failure(
                IllegalArgumentException(
                    "Can't open '$url' — only http and https pages work here. " +
                        "Give a normal web address, or pass 'query' to search instead."
                )
            )
            // A query that is really a site's NAME opens the site. "Open
            // radio for all" reaches this tool as a query, because with no
            // "dot" in it the model has nothing to call a URL — and a web
            // search ABOUT radio4all.net is never what naming it meant.
            query.isNotBlank() ->
                spokenHost(query)?.let { "https://$it" } ?: searchUrl(query)
            // Neither argument given ("open a browser"). The home page is the
            // search engine, so the user lands somewhere they can act from.
            else -> HOME_URL
        }

        val label = when {
            // The player is "Podcasts" wherever it came from — its host is
            // an implementation detail nobody said out loud.
            target.startsWith(LocalPages.PLAYER_URL) -> "Podcasts"
            site.isNotBlank() && query.isNotBlank() -> shortLabel(query)
            url.isNotBlank() -> hostLabel(target)
            query.isNotBlank() -> shortLabel(query)
            else -> "web"
        }

        // ONE player window. A new search would otherwise open a second
        // player beside the first (different ?q= means a different payload,
        // which defeats the store's own dedupe), and two players both
        // holding an <audio> is a recipe for a duet.
        if (target.startsWith(LocalPages.PLAYER_URL)) {
            browserPins()
                .filter { it.payload.startsWith(LocalPages.PLAYER_URL) && it.payload != target }
                .forEach { HudPinStore.remove(it.id) }
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

        // The deferred errand rides in AFTER the pin exists, so the task
        // listener's lastAddedPinId resolution points at this very window
        // and its ensureLoaded holds the task until the page is ready.
        followUpTask?.let { t ->
            AgentTaskBridge.request(t, windowHint = followUpHint)
            return Result.success(
                "Opening ${hostLabel(target)} and asking it to play $query. " +
                    "The result will follow."
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
        spokenHost(s)?.let { return "https://$it" }
        // Bare host, with or without a path: "archive.org", "bbc.co.uk/news".
        // The pattern also rejects the slash-less schemes — "javascript:…",
        // "data:…", "intent:…" — since it permits a colon only in front of a
        // port number.
        if (!HOST_RE.matches(s)) return null
        return "https://$s"
    }

    /**
     * A domain the wearer SAID, rather than one anybody would type.
     *
     * Speech has no full stops and no digits: "radio4all.net" is heard as
     * "radio for all dot net", which is not a host by any pattern, so it
     * used to fall through to a web search — the wearer names a site and
     * gets a results page for it.
     *
     * Two steps. Spoken "dot" becomes a real dot and the spaces close up,
     * which alone rescues anything whose letters survive dictation. Then a
     * table catches the rest: numbers spoken as words, where "4" reaches us
     * as "for" or "four" and no rule can tell that from an actual word.
     *
     * Only text that LOOKS dictated is touched — it must contain a spoken
     * "dot" or match the table outright — so "cats" still searches for cats.
     */
    private fun spokenHost(raw: String): String? {
        val lower = raw.lowercase(Locale.US).trim().trimEnd('.', '!', '?')
        val joined = lower
            .replace(Regex("\\s+dot\\s+"), ".")
            .replace(Regex("\\s+"), "")
        SPOKEN_SITES[joined]?.let { return it }
        // The SUFFIX is a guess too, and a wrong one is invisible: asked for
        // radio4all.net the model offered radioforall.org, which is
        // well-formed, passes every check, and does not resolve at all —
        // measured, it answers nothing. Having corrected the name, correct it
        // whatever ending was attached.
        if (joined.contains('.')) {
            SPOKEN_SITES[joined.substringBeforeLast('.')]?.let { return it }
        }
        // No spoken "dot" means this was never a dictated address; leave it
        // to the search path rather than guessing a host out of prose.
        if (!Regex("\\s+dot\\s+").containsMatchIn(lower)) return null
        if (!HOST_RE.matches(joined)) return null
        // The last part has to be a REAL suffix, because "dot" is an
        // ordinary English word and the shape alone proves nothing. Simulated
        // over the phrasings a wearer actually produces, the bare pattern
        // turned "what is the dot product" into whatisthe.product and
        // "podcasts about dot net framework" into podcastsabout.netframework
        // — both perfectly valid-looking hosts, both nonsense, and both
        // stealing a question that should have been a search.
        val tld = joined.substringAfterLast('.').substringBefore('/').substringBefore(':')
        return joined.takeIf { tld in SPOKEN_TLDS }
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
        /**
         * Dictated forms that no amount of pattern-matching can repair,
         * because the mangling is a real English word. Keyed on the text
         * after "dot" has been turned into a dot and the spaces closed up,
         * so one entry covers every spacing the transcriber produces.
         */
        private val SPOKEN_SITES = mapOf(
            // radio4all.net — "4" is heard as "for", occasionally "four",
            // and the wearer may or may not say the ".net" at all.
            "radioforall.net" to "www.radio4all.net",
            "radiofourall.net" to "www.radio4all.net",
            "radioforall" to "www.radio4all.net",
            "radiofourall" to "www.radio4all.net",
            "radio4all" to "www.radio4all.net",
            // radio.garden puts its name ACROSS the dot, so "radio garden"
            // closes up to radiogarden and the obvious guess is .com. That
            // guess is not the site: measured, radiogarden.com answers 410
            // and redirects to a domain-for-sale listing, which is the blank
            // window the wearer was looking at. The correction has to apply
            // even though radiogarden.com is a perfectly well-formed host —
            // being well-formed is exactly what made it convincing.
            "radiogarden" to "radio.garden",
            "radiogarden.com" to "radio.garden",
            "radiogarden.net" to "radio.garden",
            "radiogarden.org" to "radio.garden",
            // The podcast names all land on the app's own player page. The
            // real sites were measured unusable in a window this size —
            // listennotes lays out 564px wide whatever it is told, podchaser
            // never hydrates at all — so "open listen notes" means "I want
            // podcasts", and the player is the thing here that can do that.
            "listennotes" to "x3hub.local/podplayer.html",
            "podchaser" to "x3hub.local/podplayer.html",
            "podcasts" to "x3hub.local/podplayer.html",
            "podcast" to "x3hub.local/podplayer.html",
            "podcastplayer" to "x3hub.local/podplayer.html"
        )

        /**
         * Suffixes a dictated address may end in. A allowlist rather than a
         * pattern because "dot" is a common English word and the shape of a
         * host proves nothing on its own — see [spokenHost]. Deliberately
         * short: a miss costs one web search, a false positive costs the
         * wearer their answer.
         */
        private val SPOKEN_TLDS = setOf(
            "com", "net", "org", "edu", "gov", "mil", "int",
            "io", "ai", "app", "dev", "co", "me", "tv", "fm", "cc", "xyz",
            "info", "biz", "news", "radio", "garden", "live", "media",
            "uk", "us", "ca", "au", "de", "fr", "es", "it", "nl", "se",
            "no", "fi", "dk", "pl", "ru", "jp", "cn", "in", "br", "mx", "nz"
        )

        private val HOST_RE = Regex(
            "^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}(?::\\d{1,5})?(?:/\\S*)?$",
            RegexOption.IGNORE_CASE
        )
    }
}
