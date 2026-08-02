package com.x3hub.app.core.tools

import android.content.Context
import com.x3hub.app.core.agent.AgentTaskBridge
import com.x3hub.app.core.agent.AgentTaskBridge.PageErrand
import com.x3hub.app.core.web.LocalPages
import com.x3hub.app.core.web.WebDestination
import com.x3hub.app.core.bridge.HudPinStore

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

        // "…and play the first one" — the part of the request that continues
        // PAST opening, in the wearer's own words. Without this argument the
        // verb had to be guessed, and it was guessed as "play" for every
        // destination including encyclopedias.
        val errandArg = arg(args, "errand", "then", "action")

        // "load it IN THIS WINDOW" — placement, not destination. Board space
        // is three windows on a 640px eye; a wearer who says "in the
        // current window" is husbanding it, and opening a fourth surface
        // they did not ask for spends what they were saving.
        //
        // Dim forces the same placement without being asked: the display is
        // black, so the wearer cannot see windows and cannot close them —
        // every open while dimmed drives the ONE invisible window, and a
        // board that entered dim with one window leaves dim with one.
        val inCurrent = com.x3hub.app.core.bridge.DimBridge.dimmed ||
            arg(args, "window", "target", "destination")
                .lowercase().let { it.contains("current") || it.contains("same") || it.contains("this") }

        // ONE resolver, before any placement decision. Where the page shows
        // up can choose a surface; it can never change where the wearer
        // goes, what it is called, or what remains to be done there. Every
        // bug of the "it worked until I said in this window" family was a
        // second, weaker resolver hiding inside a placement branch.
        val dest = WebDestination.resolve(url, site, query, errandArg)
            ?: return Result.failure(
                IllegalArgumentException(
                    "Can't open '$url' — only http and https pages work here. " +
                        "Give a normal web address, or pass 'query' to search instead."
                )
            )

        // --- Placement: the window the wearer is already using. ---
        // Only an instruction when there IS one: on an empty board this
        // falls through to the open path, which is what was wanted anyway —
        // the ask is to not spawn ANOTHER window, and none → one doesn't.
        if (inCurrent && browserPins().isNotEmpty()) {
            val sent = AgentTaskBridge.request(
                PageErrand(navigateTo = dest.url, task = dest.errand)
            )
            if (sent) {
                return Result.success(
                    "Loading ${dest.label} in the current window" +
                        (dest.errand?.let {
                            " and asking it to $it. The agent works on its own from here"
                        } ?: "") + "."
                )
            }
            // No activity listening — open normally below rather than
            // promising a navigation nothing will perform.
        }

        // ONE player window. A new search would otherwise open a second
        // player beside the first (different ?q= means a different payload,
        // which defeats the store's own dedupe), and two players both
        // holding an <audio> is a recipe for a duet. The listener enforces
        // the same rule for in-current navigations, where no pin is written.
        if (dest.url.startsWith(LocalPages.PLAYER_URL)) {
            browserPins()
                .filter { it.payload.startsWith(LocalPages.PLAYER_URL) && it.payload != dest.url }
                .forEach { HudPinStore.remove(it.id) }
        }

        val open = browserPins()
        val alreadyOpen = open.firstOrNull { it.payload == dest.url }
        // The site's window, even when it has wandered off the exact
        // address: "play X on radio garden" while its window sits on a deep
        // station page used to count as a NEW window — and at the cap, the
        // request was refused with the site sitting right there.
        val sameSite = alreadyOpen
            ?: open.firstOrNull { WebDestination.sameHost(it.payload, dest.url) }
        if (sameSite == null && open.size >= MAX_BROWSER_WINDOWS) {
            // Each window is a full WebView — renderer process, network
            // stack, its own JS heap — and three of them already cover most
            // of a 640×480 eye. Refusing out loud beats quietly thrashing.
            // Before any dispatch, deliberately: a refused window must not
            // leave an errand running loose looking for a surface.
            return Result.success(
                "There are already $MAX_BROWSER_WINDOWS browser windows open (" +
                    open.joinToString(", ") { it.label } + "). Ask the user to close one " +
                    "first — double-tap a window and tap the ✕ — then try again."
            )
        }

        val pinId: String?
        if (sameSite != null && alreadyOpen == null) {
            // Reuse the site's window rather than opening a sibling. No
            // store write here — the payload differs, so add() would mint a
            // second pin; the listener re-points the pin when it navigates.
            pinId = sameSite.id
        } else {
            val added = HudPinStore.add(
                HudPinStore.HudPin(
                    type = TYPE_BROWSER,
                    label = dest.label,
                    payload = dest.url,
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
                    "The HUD board is full (${HudPinStore.MAX_PINS} pins), so there's no " +
                        "room for a browser window. Ask the user which pin to remove first."
                )
            }
            pinId = alreadyOpen?.id ?: HudPinStore.lastAddedBrowserPinId
        }

        // --- Placement: an existing window, or the one just created. ---
        // A REUSED window is never navigated by the store — the board's
        // getOrPut returns the cached view — so it must be sent to the
        // destination explicitly, or "open archive.org" visibly does
        // nothing. A FRESH window loads its payload itself; only the errand
        // rides, and the listener holds it until the document is really
        // there. Both are addressed by pin IDENTITY, not by name: a name
        // match fails exactly when the window has not loaded yet or the URL
        // does not spell the site's name.
        val needsNav = sameSite != null
        if (needsNav || dest.errand != null) {
            val sent = AgentTaskBridge.request(
                PageErrand(
                    navigateTo = if (needsNav) dest.url else null,
                    task = dest.errand,
                    windowPinId = pinId
                )
            )
            if (!sent && dest.errand != null) {
                // The pin exists but nothing is listening — say what
                // actually happened instead of promising a result.
                return Result.success(
                    "Opened ${dest.label}, but the page errand could not be handed over."
                )
            }
        }

        if (dest.errand != null) {
            return Result.success(
                "Opening ${dest.label} and asking it to ${dest.errand}. " +
                    "The agent works on its own from here."
            )
        }

        // Phrased from HOW the destination resolved, not from which argument
        // happened to carry it: "searching for X" was once spoken when the
        // tool had in fact opened X's own site and run no search at all.
        val where = when (dest.kind) {
            WebDestination.Kind.SITE_SEARCH -> "searching for $query"
            WebDestination.Kind.WEB_SEARCH -> "searching the web"
            WebDestination.Kind.DIRECT -> "on ${dest.label}"
            WebDestination.Kind.HOME -> "on the search page"
        }
        // Spoken aloud verbatim, so: one sentence, no punctuation the TTS has
        // to guess at. The interaction hint rides along only on the first
        // window, where the user has nothing on screen to have learnt it from.
        // A REUSED window did not "open" — saying it did taught the wearer
        // their existing window had been replaced.
        if (sameSite != null) {
            return Result.success("Brought the ${dest.label} window forward.")
        }
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

    // URL resolution, spoken-host repair, labels: all in WebDestination.
    // Deliberately NOT here — a second copy in this file is exactly the
    // defect this layout replaced.

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
    }
}
