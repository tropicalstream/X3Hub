package com.x3hub.app.core.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HUD pin board — persistence + cross-module bridge for user-pinned
 * HUD content ("hud posts"): icons that open URLs, post-it notes that
 * link to Google Tasks, and pictures that open full screen.
 *
 * Written by BOTH modules in the same process:
 *   • visionclaw's HudPinTool (Gemini voice: "pin that to my HUD")
 *   • tapbrowser's HudPinBoardController (long-tap delete / move)
 * so it follows the ChatCardBridge singleton pattern: thread-safe
 * state, listeners fire on the mutating thread, UI consumers hop to
 * main themselves.
 *
 * Persistence: its OWN SharedPreferences file ("hud_pin_store"), NOT
 * visionclaw_prefs and NOT chat_context. Pins aren't companion-app
 * config and keeping them out of visionclaw_prefs avoids ever
 * colliding with CompanionServer's allowed_config_keys machinery
 * (lesson of the Oakland bug, commit 030d119: be deliberate about
 * which prefs file owns what).
 */
object HudPinStore {

    const val TYPE_ICON = "icon"
    const val TYPE_NOTE = "note"
    const val TYPE_PICTURE = "picture"

    /**
     * A saved web page: [HudPin.payload] is the thumbnail's file path (so
     * the picture loader works on it unchanged) and [HudPin.sourceUrl] is
     * the page to reopen when it is tapped. The library entry itself lives
     * in BookmarkStore — this pin is only its presence on the HUD, and
     * removing the pin does not forget the bookmark.
     */
    const val TYPE_BOOKMARK = "bookmark"

    /**
     * Live card — [payload] is a natural-language WATCH QUERY ("Warriors
     * score", "top AI headline", "new trending Rust repos", "changes to
     * <page>"), optionally scoped to [HudPin.sourceUrl]. The
     * LiveCardEngine (visionclaw side) refreshes [HudPin.content] every
     * [HudPin.intervalSec] and flips [HudPin.stale] on fetch failure;
     * the board renders content and dims stale cards.
     */
    const val TYPE_LIVE = "live"

    /**
     * Countdown chip — a pending [ReminderStore] reminder made visible on
     * the HUD while it waits. [HudPin.id] IS the owning reminder's id: the
     * scheduler removes the chip on fire/cancel by that id alone, so there
     * is no second mapping to keep in sync. [payload] is the reminder text,
     * [dueAtMs] the moment it fires; the board ticks the remaining time
     * locally so the store isn't rewritten once a second.
     */
    const val TYPE_COUNTDOWN = "countdown"

    /** Hard cap — the pin zone is small (~150×90dp usable). */
    const val MAX_PINS = 10

    private const val PREFS_FILE = "hud_pin_store"
    private const val KEY_PINS = "hud_pins"

    /**
     * One HUD post.
     *
     * [payload] meaning by [type]:
     *   icon    → the URL the icon opens in TapBrowser
     *   note    → the note body text
     *   picture → absolute file path (screen grabs saved by HudPinTool)
     *             or an http(s) image URL
     * [linkUrl]: optional tap-through override. Notes default to
     * Google Tasks; icons default to [payload]; pictures open the
     * fullscreen viewer and ignore it.
     * [customX]/[customY]: overlay-space position in px once the user
     * has manually moved the pin; -1 = auto-grid slot.
     */
    data class HudPin(
        val id: String = UUID.randomUUID().toString(),
        val type: String,
        val label: String,
        val payload: String,
        val linkUrl: String? = null,
        val customX: Int = -1,
        val customY: Int = -1,
        val createdAt: Long = System.currentTimeMillis(),
        // ── live-card fields (TYPE_LIVE only; inert defaults otherwise) ──
        /** Optional URL to watch (a scoreboard, feed, repo list, any page). */
        val sourceUrl: String? = null,
        /** Latest engine-produced display text ("" until first refresh). */
        val content: String = "",
        /** Wall-clock ms of the last SUCCESSFUL refresh; 0 = never. */
        val updatedAt: Long = 0L,
        /** Refresh cadence in seconds; 0 = not a live pin. */
        val intervalSec: Int = 0,
        /** True when the last refresh attempt failed — UI dims the card. */
        val stale: Boolean = false,
        /**
         * Short human-readable status shown on the card when a refresh
         * isn't succeeding — e.g. "rate-limited", "no data", "error 500".
         * Runtime-only (deliberately NOT persisted, so a transient error
         * never survives a restart); cleared on the next success.
         */
        val statusNote: String? = null,
        /**
         * Wall-clock ms this pin counts down to (TYPE_COUNTDOWN only).
         * Defaulted so pins persisted before countdowns existed still
         * parse; 0 = no deadline.
         */
        val dueAtMs: Long = 0L,
        // ── browser-window resume (TYPE_BROWSER only) ──
        /**
         * Where the window had actually navigated TO, which is usually not
         * [payload]: payload is where it was first opened, and the wearer
         * then followed links. Restoring payload sends them back to a search
         * page they left ten minutes ago.
         */
        val lastUrl: String? = null,
        /**
         * Absolute path to a still of the page as it last looked. Shown
         * immediately on restart so the board comes back looking the way it
         * was left, instead of flashing empty frames while the network
         * re-fetches every window.
         */
        val snapshotPath: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("label", label)
            .put("payload", payload)
            .put("linkUrl", linkUrl ?: JSONObject.NULL)
            .put("customX", customX)
            .put("customY", customY)
            .put("createdAt", createdAt)
            .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
            .put("content", content)
            .put("updatedAt", updatedAt)
            .put("intervalSec", intervalSec)
            .put("stale", stale)
            .put("dueAtMs", dueAtMs)
            .put("lastUrl", lastUrl ?: JSONObject.NULL)
            .put("snapshotPath", snapshotPath ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject): HudPin? {
                val type = o.optString("type").takeIf { it.isNotBlank() } ?: return null
                return HudPin(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    type = type,
                    label = o.optString("label"),
                    payload = o.optString("payload"),
                    linkUrl = o.optString("linkUrl").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    customX = o.optInt("customX", -1),
                    customY = o.optInt("customY", -1),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    sourceUrl = o.optString("sourceUrl").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    content = o.optString("content"),
                    updatedAt = o.optLong("updatedAt", 0L),
                    intervalSec = o.optInt("intervalSec", 0),
                    stale = o.optBoolean("stale", false),
                    dueAtMs = o.optLong("dueAtMs", 0L),
                    lastUrl = o.optString("lastUrl").takeIf { it.isNotBlank() && it != "null" },
                    snapshotPath = o.optString("snapshotPath")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }
    }

    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile private var appContext: Context? = null
    private val lock = Any()
    @Volatile private var cache: List<HudPin>? = null
    private val listeners = CopyOnWriteArrayList<(List<HudPin>) -> Unit>()

    /**
     * Idempotent. Either module may call first (tapbrowser
     * MainActivity.onCreate in practice; HudPinTool defensively).
     * Always stores the application context, never an Activity.
     */
    fun init(context: Context) {
        if (appContext == null) {
            synchronized(lock) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun all(): List<HudPin> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val loaded = load()
            cache = loaded
            // The first read off disk IS the previous session's board. Note
            // those ids once, before anything this session can add to them.
            // Flagged rather than inferred from the set being empty: a board
            // that starts with no pins is a real state, and inferring from
            // emptiness would then mark the FIRST pin the wearer opens as a
            // restored one and leave their video muted.
            if (!restoredCaptured) {
                restoredCaptured = true
                loaded.forEach { restoredIds.add(it.id) }
            }
            return loaded
        }
    }

    private val restoredIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var restoredCaptured = false

    /**
     * True when this pin was on the board before the app started, rather
     * than created during this session.
     *
     * Exists so a restored browser window can come back SILENT. A video
     * window never produces a usable snapshot — the capture is uniform and
     * gets rejected as blank — so it takes the plain reload path on every
     * cold start and begins playing at once. That was harmless while
     * YouTube muted itself; now that it does not, putting the glasses on
     * and opening the hub would start yesterday's video out loud, which
     * nobody asked for. Opening a video is a request for sound; finding one
     * where you left it is not.
     */
    fun wasRestoredFromDisk(id: String): Boolean {
        all()   // ensure the first load has happened and the set is populated
        return restoredIds.contains(id)
    }

    /**
     * Adds [pin]; returns false when at [MAX_PINS] capacity.
     *
     * Dedupe: re-pinning the SAME target (same type + payload, e.g.
     * asking Gemini to pin the current station twice, or the same
     * station with fresher metadata) REPLACES the existing pin in
     * place — keeping its id and any manual position — instead of
     * stacking an identical twin on the board.
     */
    fun add(pin: HudPin): Boolean {
        synchronized(lock) {
            val current = all()
            val existingIdx = current.indexOfFirst {
                it.type == pin.type && it.payload == pin.payload
            }
            if (existingIdx >= 0) {
                val existing = current[existingIdx]
                val next = current.toMutableList()
                next[existingIdx] = pin.copy(
                    id = existing.id,
                    customX = existing.customX,
                    customY = existing.customY,
                    createdAt = existing.createdAt
                )
                persist(next)
                lastAddedPinId = existing.id
                stampBrowserAdd(pin.type, existing.id)
            } else {
                if (current.size >= MAX_PINS) return false
                persist(current + pin)
                lastAddedPinId = pin.id
                stampBrowserAdd(pin.type, pin.id)
            }
        }
        notifyListeners()
        return true
    }

    /** Remove by exact id. Returns true when something was removed. */
    /**
     * Record where a browser window had got to, and what it looked like.
     *
     * Written as the app goes away, so the next start can put the board back
     * as the wearer left it. Deletes the previous still — nothing else in
     * this store cleans up files, and a snapshot per window per launch would
     * accumulate forever.
     */
    @Synchronized
    fun updateBrowserResume(id: String, lastUrl: String?, snapshotPath: String?): Boolean {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        val old = list[i]
        if (old.snapshotPath != null && old.snapshotPath != snapshotPath) {
            runCatching { java.io.File(old.snapshotPath).delete() }
        }
        list[i] = old.copy(
            lastUrl = lastUrl ?: old.lastUrl,
            snapshotPath = snapshotPath ?: old.snapshotPath
        )
        cache = list
        persist(list)
        // Deliberately NOT notifying: this runs while the app is going away,
        // and a store notification would rebuild the whole board mid-teardown.
        return true
    }

    fun remove(id: String): Boolean {
        val removed: Boolean
        synchronized(lock) {
            val current = all()
            val next = current.filterNot { it.id == id }
            removed = next.size != current.size
            if (removed) persist(next)
        }
        if (removed) notifyListeners()
        return removed
    }

    /**
     * Remove by fuzzy label ("delete the cat pin" shouldn't require
     * the exact stored label). Case-insensitive containment either
     * direction; falls back to matching against note body text.
     * Returns the removed pin's label, or null when nothing matched.
     */
    fun removeByLabel(query: String): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        var removedLabel: String? = null
        synchronized(lock) {
            val current = all()
            val victim = current.firstOrNull {
                val l = it.label.trim().lowercase()
                l == q || l.contains(q) || q.contains(l) && l.isNotEmpty()
            } ?: current.firstOrNull {
                it.type == TYPE_NOTE && it.payload.lowercase().contains(q)
            } ?: return null
            removedLabel = victim.label
            persist(current.filterNot { it.id == victim.id })
        }
        notifyListeners()
        return removedLabel
    }

    /**
     * The pin most recently written through [add], new or replaced.
     *
     * This is the missing answer to "which window did the wearer just ask
     * for". A voice-opened window is INERT by design, so with two windows on
     * the board neither is active and selection by activity fails — measured
     * live: open_browser put bandcamp up, page_agent dispatched 300ms later,
     * and the listener told a wearer looking at the page to open a page.
     * Recency of focus cannot answer either, because a RE-opened pin reuses
     * its window without touching it. The store is the one place every open
     * passes through, so the store remembers.
     */
    @Volatile var lastAddedPinId: String? = null
        private set

    /**
     * The last BROWSER pin asked for, and when, on the uptime clock —
     * comparable with a window's lastFocusMs, so the picker can ask the
     * only question that matters: which came later, the wearer's hand or
     * the wearer's voice? A fixed ladder cannot answer that; whichever
     * rung goes first is wrong half the time.
     *
     * Browser-only on purpose: the generic lastAddedPinId is restamped by
     * EVERY add, so the app bookmarking a page — its own act, seconds after
     * the wearer's — used to erase the voice claim and send the follow-up
     * errand to whatever window the wearer's hand last touched.
     */
    @Volatile var lastAddedBrowserPinId: String? = null
        private set

    @Volatile var lastAddedBrowserAtMs: Long = 0L
        private set

    private fun stampBrowserAdd(type: String, id: String) {
        // The one spelling of the type string lives in BrowserTool; a
        // literal here would be a second one that could drift.
        if (type == com.x3hub.app.core.tools.BrowserTool.TYPE_BROWSER) {
            lastAddedBrowserPinId = id
            lastAddedBrowserAtMs = android.os.SystemClock.uptimeMillis()
        }
    }

    /**
     * Re-point a browser pin at a new address — voice navigation
     * REPURPOSES a window ("load radio garden in this window"), so the pin
     * follows; click-drift inside a page deliberately does not touch it.
     * Without this the board's payload record and the window's real
     * document diverge, and everything keyed on payload — re-open dedupe,
     * the one-player sweep — reasons from a stale address.
     */
    @Synchronized
    fun repointBrowser(id: String, url: String, label: String?): Boolean {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        val old = list[i]
        if (old.payload == url && (label == null || label == old.label)) return true
        list[i] = old.copy(
            payload = url,
            label = label ?: old.label,
            lastUrl = url
        )
        cache = list
        persist(list)
        // Notify: the board reuses the cached window for a known id (no
        // reload), and the rebuild is what refreshes the visible label.
        notifyListeners()
        return true
    }

    /** Persist a manual move (overlay-space px). */
    fun updatePosition(id: String, x: Int, y: Int) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val next = current.toMutableList()
            next[idx] = next[idx].copy(customX = x, customY = y)
            persist(next)
        }
        notifyListeners()
    }

    /**
     * Persist several moves as ONE change with ONE listener pass.
     *
     * Placing a window settles its whole cluster — the anchor plus every
     * neighbour that squared up to it. Written one at a time that is one
     * full board render per pin, and the wearer watches the settle happen
     * as a stutter of separate hops instead of one motion.
     */
    fun updatePositions(moves: Map<String, Pair<Int, Int>>) {
        if (moves.isEmpty()) return
        synchronized(lock) {
            val next = all().map { pin ->
                moves[pin.id]?.let { (x, y) -> pin.copy(customX = x, customY = y) } ?: pin
            }
            persist(next)
        }
        notifyListeners()
    }

    /** Engine writes a live card's fresh display text (success path).
     *  Clears any stale flag and status note. */
    fun updateContent(id: String, content: String) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val next = current.toMutableList()
            next[idx] = next[idx].copy(
                content = content,
                updatedAt = System.currentTimeMillis(),
                stale = false,
                statusNote = null
            )
            persist(next)
        }
        notifyListeners()
    }

    /** Engine flags a live card whose refresh keeps failing (dead source).
     *  [note] is a short user-facing reason shown on the card. */
    fun markStale(id: String, note: String? = null) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return
            val cur = current[idx]
            if (cur.stale && cur.statusNote == note) return
            val next = current.toMutableList()
            next[idx] = cur.copy(stale = true, statusNote = note)
            persist(next)
        }
        notifyListeners()
    }

    /**
     * Set a card's status note WITHOUT marking it stale — for conditions
     * that aren't a dead source, e.g. "rate-limited" (throttled but the
     * last value is still valid). Pass null to clear.
     */
    fun setStatus(id: String, note: String?) {
        synchronized(lock) {
            val current = all()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0 || current[idx].statusNote == note) return
            val next = current.toMutableList()
            next[idx] = next[idx].copy(statusNote = note)
            persist(next)
        }
        notifyListeners()
    }

    fun clear() {
        synchronized(lock) { persist(emptyList()) }
        notifyListeners()
    }

    // ── Refresh-request bus (UI → engine, same-process) ──────────────

    private val refreshListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** LiveCardEngine subscribes; fires with the pin id to refresh NOW. */
    fun onRefreshRequest(listener: (String) -> Unit): AutoCloseable {
        refreshListeners.add(listener)
        return AutoCloseable { refreshListeners.remove(listener) }
    }

    /** UI asks for an immediate refresh (tap on a live card). */
    fun requestRefresh(id: String) {
        for (l in refreshListeners) {
            try {
                l(id)
            } catch (_: Throwable) {
                // never let a consumer crash the publisher
            }
        }
    }

    /**
     * Subscribe; fires once synchronously with current state, then on
     * every mutation. Returns an [AutoCloseable] for lifecycle-tied
     * removal (same contract as ChatCardBridge.observe).
     */
    fun observe(listener: (List<HudPin>) -> Unit): AutoCloseable {
        listeners.add(listener)
        try {
            listener(all())
        } catch (_: Throwable) {
            // never let a buggy listener escape
        }
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = all()
        for (l in listeners) {
            try {
                l(snapshot)
            } catch (_: Throwable) {
                // ditto
            }
        }
    }

    private fun persist(pins: List<HudPin>) {
        cache = pins
        val arr = JSONArray()
        pins.forEach { arr.put(it.toJson()) }
        prefs()?.edit()?.putString(KEY_PINS, arr.toString())?.apply()
    }

    private fun load(): List<HudPin> {
        val raw = prefs()?.getString(KEY_PINS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { HudPin.fromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
