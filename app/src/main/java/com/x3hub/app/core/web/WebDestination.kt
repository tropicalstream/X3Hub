package com.x3hub.app.core.web

import android.net.Uri
import com.x3hub.app.core.agent.PageCommands
import java.net.URLEncoder
import java.util.Locale

/**
 * THE resolver: every spoken destination becomes a URL here, and only here.
 *
 * There used to be three of these — the open path's `when` ladder, a weaker
 * copy inside the in-current-window branch, and a third hidden one where a
 * resolved URL was shipped as the English string "go to …" and re-parsed by
 * PageCommands.URL_RE. Every routing bug of the "it worked without 'in this
 * window' and broke with it" family was the same defect: a site known to
 * one resolver and not another, so a word about WINDOW PLACEMENT silently
 * changed WHERE THE WEARER WENT.
 *
 * The rule now: resolution is a pure function of what was asked for.
 * Placement — this window, that window, a new one — is decided later, from
 * board state, and can only choose a surface. It can never weaken the
 * destination, the label, or the errand.
 */
object WebDestination {

    /** What a spoken request resolves to. */
    data class Destination(
        /** The page to load. Always an http(s) URL. */
        val url: String,
        /** What to call the window, and the page, out loud. */
        val label: String,
        /**
         * What remains TO DO once the page is there, or null when the URL
         * already carries the whole request (a search URL embeds its query;
         * nothing is left over). Dispatched through the page-command router
         * after the navigation lands.
         */
        val errand: String? = null,
        /** How the URL was arrived at — replies are phrased from this. */
        val kind: Kind,
    )

    enum class Kind {
        /** A site's own search results for the query. */
        SITE_SEARCH,
        /** A page or site named directly (url arg, spoken host, site root). */
        DIRECT,
        /** A web search, because nothing more specific matched. */
        WEB_SEARCH,
        /** Nothing asked for — the search home page. */
        HOME,
    }

    /**
     * Resolve `(url, site, query, errand)` — the open_browser argument
     * surface — to one Destination, or null when `url` was given but is not
     * loadable (the one case that deserves a spoken refusal rather than a
     * silent fallback: the model NAMED an address and the address is bad).
     *
     * First match wins:
     *  1. url               → resolveUrl, query (if any) becomes an errand
     *  2. site+query        → the site's own search URL, nothing left to do
     *  3. site+query, known site without a search URL → site root + errand
     *  4. site+query, unknown site → web search for "query site"
     *  5. site alone        → the site itself (spoken host or search-table
     *                         root), else a web search for its name
     *  6. query alone       → a site if the query IS one's name, else search
     *  7. nothing           → the home page
     */
    fun resolve(
        url: String,
        site: String,
        query: String,
        errand: String = "",
    ): Destination? {
        val spokenErrand = errand.takeIf { it.isNotBlank() }

        if (url.isNotBlank()) {
            val direct = resolveUrl(url) ?: return null
            return Destination(
                url = direct,
                label = labelFor(direct, query),
                // A query alongside a url used to be silently dropped;
                // "open bandcamp.com and play my purchases" kept the
                // opening and lost the playing.
                errand = spokenErrand ?: query.takeIf { it.isNotBlank() }
                    ?.let { defaultErrand(direct, it) },
                kind = Kind.DIRECT,
            )
        }

        if (site.isNotBlank() && query.isNotBlank()) {
            PageCommands.siteSearchUrl(site, query)?.let { searchUrl ->
                return Destination(
                    url = searchUrl,
                    label = labelFor(searchUrl, query),
                    // The URL already asks the question. An errand on top
                    // would ask it twice — EXCEPT when the model spelled
                    // one out, which means the request continues past the
                    // search ("…and play the first one").
                    errand = spokenErrand,
                    kind = Kind.SITE_SEARCH,
                )
            }
            spokenHost(site)?.let { host ->
                val root = "https://$host"
                return Destination(
                    url = root,
                    label = labelFor(root, query),
                    // No search URL exists for this site, so the query is
                    // work to do ON the page — radio.garden tunes, it does
                    // not search.
                    errand = spokenErrand ?: defaultErrand(root, query),
                    kind = Kind.DIRECT,
                )
            }
            // An unknown site is not a failure: searching the web for
            // "<query> <site>" is a reasonable answer, and far better than
            // refusing because no table has an entry.
            val search = webSearchUrl("$query $site")
            return Destination(search, shortLabel(query), spokenErrand, Kind.WEB_SEARCH)
        }

        if (site.isNotBlank()) {
            // "Open bandcamp" with the name in the site arg used to fall
            // through every branch and land on the HOME page — the wearer
            // named a site and got DuckDuckGo.
            val root = spokenHost(site)?.let { "https://$it" }
                ?: PageCommands.siteRootUrl(site)
            return if (root != null) {
                Destination(root, labelFor(root, ""), spokenErrand, Kind.DIRECT)
            } else {
                Destination(webSearchUrl(site), shortLabel(site), spokenErrand, Kind.WEB_SEARCH)
            }
        }

        if (query.isNotBlank()) {
            // A query that is really a site's NAME opens the site. "Open
            // radio for all" reaches the tool as a query, because with no
            // "dot" in it the model has nothing to call a URL — and a web
            // search ABOUT radio4all.net is never what naming it meant.
            spokenHost(query)?.let { host ->
                val root = "https://$host"
                return Destination(root, labelFor(root, ""), spokenErrand, Kind.DIRECT)
            }
            return Destination(webSearchUrl(query), shortLabel(query), spokenErrand, Kind.WEB_SEARCH)
        }

        return Destination(HOME_URL, "web", spokenErrand, Kind.HOME)
    }

    // ------------------------------------------------------------------
    // Errand derivation
    // ------------------------------------------------------------------

    /**
     * The verb for a query that could not ride inside the URL.
     *
     * "Play" was once hard-coded for every destination, which reads fine on
     * a radio site and absurd on an encyclopedia — "play usb c cable" fell
     * through the router to an LLM round trip. "Search for X" is natively
     * routable everywhere (the router's search rule, then the site's own
     * box), so it is the default; the media surfaces where playing is the
     * point keep "play".
     */
    private fun defaultErrand(url: String, query: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
            .lowercase(Locale.US).removePrefix("www.")
        return when {
            // Bandcamp: "play <thing>" on the FRONT page routes to an agent
            // that cannot search or navigate — its own docs say so. The
            // search-then-act phrasing routes natively: site search, then
            // the agent presses play on the results. ("My purchases" style
            // requests arrive as explicit errands, which win before this.)
            host == "bandcamp.com" || host.endsWith(".bandcamp.com") ->
                "search for $query and play it"
            url.startsWith(LocalPages.PLAYER_URL) ||
                MEDIA_HOSTS.any { host == it || host.endsWith(".$it") } -> "play $query"
            else -> "search for $query"
        }
    }

    /**
     * Sites where an un-URL-able query means "make sound come out", so the
     * play-family verbs the native flows key on stay reachable.
     */
    private val MEDIA_HOSTS = setOf("radio.garden", "radio4all.net")

    /** Same site, ignoring scheme, www., and everything after the host. */
    fun sameHost(a: String?, b: String?): Boolean {
        fun host(u: String?) = runCatching { Uri.parse(u ?: "").host }.getOrNull()
            ?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
        val ha = host(a)
        return ha.isNotEmpty() && ha == host(b)
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    private fun labelFor(url: String, query: String): String = when {
        // The player is "Podcasts" wherever it came from — its host is an
        // implementation detail nobody said out loud, and hearing
        // "x3hub.local" back is the app showing its plumbing.
        url.startsWith(LocalPages.PLAYER_URL) -> "Podcasts"
        query.isNotBlank() -> shortLabel(query)
        else -> hostLabel(url)
    }

    /** "https://www.bbc.co.uk/news" → "bbc.co.uk". Falls back to the raw text. */
    fun hostLabel(url: String): String {
        if (url.startsWith(LocalPages.PLAYER_URL)) return "Podcasts"
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        val stripped = host.removePrefix("www.").trimEnd('.')
        return stripped.ifBlank { url.removePrefix("https://").removePrefix("http://") }
            .take(LABEL_MAX_CHARS)
    }

    /** Three words is what fits a window title strip at this size. */
    fun shortLabel(text: String): String =
        text.trim().split(Regex("\\s+")).take(3).joinToString(" ").take(LABEL_MAX_CHARS)

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
    fun resolveUrl(raw: String): String? {
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
    fun spokenHost(raw: String): String? {
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

    fun webSearchUrl(query: String): String =
        SEARCH_PREFIX + URLEncoder.encode(query.trim(), "UTF-8")

    /** SmartView's default engine, and its home page. */
    const val HOME_URL = "https://duckduckgo.com/"
    private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

    private const val LABEL_MAX_CHARS = 24

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
     * Suffixes a dictated address may end in. An allowlist rather than a
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
