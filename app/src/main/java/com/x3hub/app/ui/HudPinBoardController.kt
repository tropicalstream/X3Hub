package com.x3hub.app.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.x3hub.app.R
import com.x3hub.app.core.bridge.DefaultPins
import com.x3hub.app.core.bridge.HudPinStore
import com.x3hub.app.core.bridge.HudPinStore.HudPin
import com.x3hub.app.core.live.LiveCardEngine
import com.x3hub.app.core.tools.BrowserTool
import java.net.URL
import java.util.Locale

/**
 * HUD pin board — renders the user's pinned notes / pictures / live
 * cards into the area under the HUD strip. Ported from TapInsight
 * beta6-gold; X3Gemini changes:
 *
 *   • The pin zone is the WHOLE under-HUD area (no browser to avoid),
 *     hard-clamped inside the logical 640×480 viewport.
 *   • No URL opening: notes are inert (tap does nothing), live cards
 *     tap-to-refresh, pictures open the fullscreen viewer.
 *
 * Interaction model (glasses trackpad) is unchanged:
 *   • Tap a pin → open it (picture → fullscreen viewer, live card →
 *     refresh now).
 *   • DOUBLE-TAP with the cursor over a pin → "hud modify" mode: the
 *     pin highlights and grows an ✕ (delete) chip. The NEXT tap ends
 *     the mode immediately: on ✕ → delete; anywhere else → the pin
 *     moves to that spot (clamped to the zone) and the position
 *     persists. Double-tap again also exits without changes.
 *
 * Threading: HudPinStore listeners fire on the mutating thread (a
 * voice-tool coroutine when Gemini pins something) — every mutation
 * hops to main via [uiHandler] before touching views.
 */
class HudPinBoardController(
    private val activity: Activity,
    private val board: FrameLayout,
    private val uiHandler: Handler,
    private val forceCursorVisible: () -> Unit,
    private val showToast: (String) -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private var subscription: AutoCloseable? = null
    private val pinViews = LinkedHashMap<String, FrameLayout>() // pin id → container
    private var pinsSnapshot: List<HudPin> = emptyList()

    /**
     * Countdown pin id → the TextView showing its remaining time, plus the
     * deadline. The 1s tick writes ONLY into these views: render() rebuilds
     * the whole board and exits modify mode, so ticking through it would
     * fight the user mid pin-drag once a second.
     */
    private val countdownViews = LinkedHashMap<String, Pair<TextView, Long>>()
    private var tickerRunning = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            val now = System.currentTimeMillis()
            countdownViews.forEach { (_, tv) -> tv.first.text = remainingText(tv.second, now) }
            uiHandler.postDelayed(this, 1000L)
        }
    }

    // hud-modify state
    private var modifyPinId: String? = null
    private var fullscreenView: FrameLayout? = null

    private val bitmapCache = LruCache<String, Bitmap>(8)

    fun start() {
        HudPinStore.init(activity)
        // Before the observer, so a fresh install's first render already
        // has them and the wearer never sees the empty board flash.
        DefaultPins.seedIfFirstRun(activity)
        subscription?.runCatching { close() }
        // observe() replays the current board to a new listener, so the
        // seed above is already in the first render it delivers.
        subscription = HudPinStore.observe { pins ->
            uiHandler.post { render(pins) }
        }
    }

    fun stop() {
        subscription?.runCatching { close() }
        subscription = null
        stopTicker()
    }

    /**
     * Re-slot the grid after HUD geometry changes — a window stepping its
     * size ladder, the camera preview appearing or leaving.
     *
     * FORCED, because the pin list has not changed and that is exactly the
     * point: this asks for a re-LAYOUT, not a re-read. Without the flag it
     * re-entered render with the very list already in the field, the
     * text-only fast path compared every pin to itself, found nothing
     * changed, and returned before placing anything — so a resized window
     * grew over its neighbours and the camera preview never pushed the
     * pins out from under it. Every caller of this function was a no-op.
     */
    fun refreshZone() {
        if (pinViews.isEmpty() && pinsSnapshot.isEmpty()) return
        render(pinsSnapshot, force = true)
    }

    // ------------------------------------------------------------------
    // Zone geometry
    // ------------------------------------------------------------------

    /** Pin zone in BOARD-local px (board is match_parent in the overlay). */
    private data class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun boardLocalX(view: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        view.getLocationOnScreen(a); board.getLocationOnScreen(b)
        return a[0] - b[0]
    }

    private fun boardLocalY(view: View): Int {
        val a = IntArray(2); val b = IntArray(2)
        view.getLocationOnScreen(a); board.getLocationOnScreen(b)
        return a[1] - b[1]
    }

    /** Zone from the LAST render — carried pins clamp against this. */
    private var lastZone: Zone? = null

    private fun computeZone(): Zone {
        // The whole area under the HUD strip. The strip is 36px tall at
        // y=2, so content starts at y=44 (same top line as TapInsight's
        // calibrated shelf); the rest of the logical viewport is ours.
        val right = if (board.width > 0) board.width - 6 else UNDER_HUD_ZONE.right
        val bottom = if (board.height > 0) board.height - 6 else UNDER_HUD_ZONE.bottom
        return Zone(UNDER_HUD_ZONE.left, UNDER_HUD_ZONE.top, right, maxOf(bottom, UNDER_HUD_ZONE.top + 28))
    }

    /** Clamp a pin's margins so its rect stays fully inside [zone]. */
    private fun clampToZone(lp: FrameLayout.LayoutParams, w: Int, h: Int, zone: Zone) {
        lp.leftMargin = lp.leftMargin.coerceIn(zone.left, maxOf(zone.left, zone.right - w))
        lp.topMargin = lp.topMargin.coerceIn(zone.top, maxOf(zone.top, zone.bottom - h))
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * True when nothing about the board's SHAPE changed — same pins, same
     * order, same sizes and positions — and only card text moved.
     */
    private fun onlyContentChanged(next: List<HudPin>): Boolean {
        val prev = pinsSnapshot
        if (prev.size != next.size || pinViews.size != prev.size) return false
        return prev.indices.all { i ->
            val a = prev[i]
            val b = next[i]
            a.id == b.id && a.type == b.type && a.label == b.label &&
                a.payload == b.payload && a.customX == b.customX &&
                a.customY == b.customY && a.snapshotPath == b.snapshotPath
        }
    }

    private fun render(pins: List<HudPin>, force: Boolean = false) {
        // A live card refreshing its text used to rebuild the ENTIRE board:
        // every view destroyed and recreated, browser WebViews included. With
        // two live cards on a five-minute cadence that is a full teardown
        // several times an hour, and for the frame it takes to lay out again
        // every pin measures 0x0 — which is how a window came to be
        // photographed as nothing at all. Text-only updates now redraw just
        // the cards whose text moved.
        // MODIFY is excluded from the fast path: it rebuilds a card's
        // children, which silently takes the ✕ chip with them while the
        // mode stays armed — the wearer then taps where the ✕ was, the
        // delete branch finds no chip, and the tap falls through to MOVE.
        // A delete gesture became a move, and the card was persisted as
        // hand-placed. The full render below exits the mode and re-enters
        // it on the new views, which is the path built for exactly this.
        if (!force && modifyPinId == null && onlyContentChanged(pins)) {
            val previous = pinsSnapshot
            pinsSnapshot = pins
            var needsFullRender = false
            pins.forEachIndexed { i, pin ->
                if (previous[i].content == pin.content &&
                    previous[i].stale == pin.stale &&
                    previous[i].statusNote == pin.statusNote
                ) return@forEachIndexed
                val container = pinViews[pin.id] ?: return@forEachIndexed
                val rebuilt = when (pin.type) {
                    HudPinStore.TYPE_LIVE -> buildLiveContent(pin)
                    else -> return@forEachIndexed
                }
                // A card's BOX was measured for the text it had. A card that
                // has just loaded grew from "updating…" to eight lines of
                // news, and swapping the text into the old box clipped it to
                // a title with nothing under it. When the new text does not
                // fit the old box, the whole board has to reflow — the pins
                // around it were placed against the old size too.
                val (nw, nh) = measureContent(rebuilt, dp(LIVE_MAX_WIDTH_DP))
                if (nw != container.layoutParams.width ||
                    nh != container.layoutParams.height
                ) {
                    needsFullRender = true
                    return@forEachIndexed
                }
                container.removeAllViews()
                rebuilt.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                rebuilt.isClickable = false
                rebuilt.isFocusable = false
                container.addView(rebuilt)
            }
            if (!needsFullRender) return
            // fall through and rebuild the board at the new sizes
        }
        pinsSnapshot = pins
        // The highlight and chip belong to view instances that are about to
        // be destroyed, so modify mode has to come down here — but a resize
        // re-renders the board *underneath* a wearer who is still editing,
        // and dropping the mode there means their next swipe is no longer a
        // resize. It falls through to the dim pull instead and blacks the
        // display out mid-edit. So the mode is re-entered on the new views.
        val resumeModifyPinId = modifyPinId
        exitModifyMode()
        // RETAINED RENDER. Everything else here is rebuilt from scratch,
        // which is fine for text and pictures and ruinous for a WebView: a
        // detach tears the page's video layer off the surface it is
        // rendering into, and Chromium does not always resubscribe on the
        // way back — measured on the glasses, a moved window kept playing
        // its soundtrack with the picture frozen, decode counter stuck,
        // while the element still reported itself playing. So a browser
        // window that survives this render is never detached at all: its
        // container stays on the board and only its layout params move.
        val survivingBrowsers = HashMap<String, FrameLayout>()
        pinViews.forEach { (id, container) ->
            val pin = pins.firstOrNull { it.id == id } ?: return@forEach
            if (pin.type == BrowserTool.TYPE_BROWSER &&
                browserWindows.containsKey(id) &&
                container.parent === board
            ) {
                survivingBrowsers[id] = container
            }
        }
        for (i in board.childCount - 1 downTo 0) {
            val child = board.getChildAt(i)
            if (survivingBrowsers.values.none { it === child }) board.removeViewAt(i)
        }
        movePreview = null
        pinViews.clear()
        pinViews.putAll(survivingBrowsers)
        // Windows whose pin is gone die HERE, not lazily: each one is a live
        // WebView (renderer process, JS heap) plus a page-agent controller in
        // the host, and nothing else ever walks this cache again. Before this
        // sweep, releaseBrowserWindow had no caller at all — a deleted pin
        // left its WebView running invisibly forever.
        val liveIds = pins.mapTo(HashSet()) { it.id }
        val dead = browserWindows.keys.filter { it !in liveIds }
        for (id in dead) {
            val w = browserWindows.remove(id) ?: continue
            onBrowserWindowReleased?.invoke(w)
            w.destroy()
        }
        // The views the ticker writes into are about to be discarded.
        countdownViews.clear()
        if (pins.isEmpty()) {
            stopTicker()
            return
        }
        if (board.width <= 0) {
            // pre-layout — retry once the overlay has real bounds
            stopTicker()
            board.post { if (board.width > 0) render(pinsSnapshot) }
            return
        }

        val zone = computeZone()
        lastZone = zone
        val gap = dp(GAP_DP)
        var x = zone.left
        var y = zone.top
        var rowH = 0

        // TWO passes: custom-positioned pins first, so the flow grid can
        // route around every one of them regardless of store order.
        val customRects = mutableListOf<IntArray>() // [l, t, r, b]
        // The camera preview shares the zone — while it's visible, treat
        // its rect as a blocker so grid pins never tile underneath it.
        activity.findViewById<View?>(R.id.unipanelCameraPreviewFrame)?.let { cam ->
            if (cam.visibility == View.VISIBLE && cam.width > 0) {
                val l = boardLocalX(cam)
                val t = boardLocalY(cam)
                customRects += intArrayOf(l, t, l + cam.width, t + cam.height)
            }
        }
        val ordered = pins.sortedBy { if (it.customX >= 0 && it.customY >= 0) 0 else 1 }
        for (pin in ordered) {
            val container = buildPinView(pin)
            val w = container.layoutParams.width
            val h = container.layoutParams.height
            val lp = container.layoutParams as FrameLayout.LayoutParams
            if (pin.customX >= 0 && pin.customY >= 0) {
                lp.leftMargin = pin.customX
                lp.topMargin = pin.customY
                clampToZone(lp, w, h, zone)
                cascadeIfEclipsed(lp, w, h, customRects, zone)
                customRects += intArrayOf(
                    lp.leftMargin, lp.topMargin, lp.leftMargin + w, lp.topMargin + h
                )
            } else {
                // Flow grid: wrap at the zone's right edge, skip past any
                // custom pin the candidate cell would overlap, and hard-
                // clamp the result inside the zone.
                var guard = 0
                var found = false
                while (guard++ < 64) {
                    if (x + w > zone.right && x > zone.left) {
                        x = zone.left
                        y += rowH + gap
                        rowH = 0
                        continue
                    }
                    val blocker = customRects.firstOrNull { r ->
                        x < r[2] + gap && x + w + gap > r[0] &&
                            y < r[3] + gap && y + h + gap > r[1]
                    }
                    if (blocker != null) {
                        x = blocker[2] + gap
                        continue
                    }
                    found = true
                    break
                }
                lp.leftMargin = x
                lp.topMargin = y
                clampToZone(lp, w, h, zone)
                // Running out of room used to mean landing wherever the
                // search gave up — which is how a news card ended up drawn
                // straight over a browser window on a ten-pin board. The
                // clamp can do it too, by pulling a pin back inside the zone
                // and into something. Either way, go BELOW everything
                // instead: off the bottom is recoverable by moving a pin,
                // on top of a window hides content with no clue why.
                if (!found || collidesWithCustom(lp, w, h, customRects, gap)) {
                    // Try below everything — but only KEEP it if it is
                    // actually better. On a board already at capacity the
                    // clamp drags that fallback straight back up into the
                    // window row, and blind faith in it put a browser window
                    // on top of another one. When nothing fits, the honest
                    // move is the position that hides the least.
                    val keepX = lp.leftMargin
                    val keepY = lp.topMargin
                    val before = overlapArea(keepX, keepY, w, h, customRects)
                    lp.leftMargin = zone.left
                    lp.topMargin = (customRects.maxOfOrNull { it[3] } ?: zone.top) + gap
                    clampToZone(lp, w, h, zone)
                    val after = overlapArea(lp.leftMargin, lp.topMargin, w, h, customRects)
                    if (after >= before) {
                        lp.leftMargin = keepX
                        lp.topMargin = keepY
                    }
                    cascadeIfEclipsed(lp, w, h, customRects, zone)
                    x = lp.leftMargin
                    y = lp.topMargin
                    rowH = 0
                }
                x = lp.leftMargin + w + gap
                y = lp.topMargin
                rowH = maxOf(rowH, h)
            }
            container.layoutParams = lp
            val alreadyOnBoard = container.parent === board
            if (!alreadyOnBoard) board.addView(container)
            pinViews[pin.id] = container
            // Only a window that genuinely arrived (first build, or one
            // restored after the board was emptied) can have a stalled
            // video layer. A retained one never left its surface.
            if (!alreadyOnBoard && pin.type == BrowserTool.TYPE_BROWSER) {
                browserWindows[pin.id]?.nudgeVideoAfterReattach()
            }
        }
        // Z-ORDER, reasserted without detaching anything. Retained windows
        // keep the child indices they had, so newly added pins would draw
        // over them purely by arrival order. bringChildToFront REORDERS a
        // child — it does not detach it — so replaying the placement order
        // restores exactly the stacking a full rebuild used to produce.
        for (pin in ordered) {
            pinViews[pin.id]?.let { board.bringChildToFront(it) }
        }
        if (resumeModifyPinId != null && pinViews.containsKey(resumeModifyPinId)) {
            enterModifyMode(resumeModifyPinId)
        }
        // Re-assert the raised window: the rebuild above re-added children
        // in pin order, silently undoing any raise.
        lastRaisedPinId?.let { id ->
            pinViews[id]?.let { board.bringChildToFront(it) }
        }
        syncTicker()
    }

    // ------------------------------------------------------------------
    // Countdown ticking
    // ------------------------------------------------------------------

    /** Run the 1s tick only while countdown chips are actually on screen. */
    private fun syncTicker() {
        if (countdownViews.isEmpty()) {
            stopTicker()
            return
        }
        if (tickerRunning) return
        tickerRunning = true
        // Immediate first pass so a just-pinned chip never shows a stale
        // value for up to a second.
        ticker.run()
    }

    private fun stopTicker() {
        tickerRunning = false
        uiHandler.removeCallbacks(ticker)
    }

    /** `m:ss`, or `h:mm:ss` past the hour. Overdue chips read "now". */
    private fun remainingText(dueAtMs: Long, now: Long): String {
        val secs = (dueAtMs - now + 999L) / 1000L
        if (secs <= 0L) return "now"
        val h = secs / 3600L
        val m = (secs % 3600L) / 60L
        val s = secs % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /**
     * Live browser windows, kept alive across board relayouts.
     *
     * Every other pin type is cheap to rebuild, so the board simply throws the
     * views away and makes new ones whenever the store changes. A WebView is
     * not cheap and, more to the point, it holds STATE the wearer cares about:
     * the page, its scroll position, its history, whatever is playing. Rebuild
     * one and a browser window silently resets itself every time an unrelated
     * pin appears. So browser views are created once per pin id and reused.
     */
    private val browserWindows = LinkedHashMap<String, BrowserWindowView>()

    /** The live window for a pin, created on first sight. */
    fun browserWindowFor(pin: HudPin): BrowserWindowView =
        browserWindows.getOrPut(pin.id) {
            BrowserWindowView(activity).also { w ->
                w.onExitRequested = { forceCursorVisible() }
                // BEFORE loadUrl, always. addJavascriptInterface only takes
                // effect on the next navigation, so attaching the agent's
                // bridge lazily (on the first double-tap, with the page long
                // since loaded) leaves the agent running against a bridge
                // that does not exist in the document — it initialises, plans,
                // then dies on "X3Bridge is not defined".
                w.installInputBridge()
                onBrowserWindowCreated?.invoke(w)
                w.maxSizeProvider = {
                    val z = computeZone()
                    Pair(z.right - z.left, z.bottom - z.top)
                }
                // Resume where the wearer actually was, not where the
                // window was first opened — they followed links, and
                // sending them back to the original URL loses that.
                val resumeUrl = pin.lastUrl?.takeIf { it.isNotBlank() }
                    ?: pin.payload.takeIf { it.isNotBlank() }
                // A window coming back from last session starts silent —
                // see HudPinStore.wasRestoredFromDisk. Clicking it gives it
                // its voice back.
                w.autoplayWithSound = !HudPinStore.wasRestoredFromDisk(pin.id)
                // Before the first load, so the page is painted the way it
                // was asked for rather than darkened and then reloaded.
                w.darkMode = !BrowserTool.isLightMode(pin)
                val snap = pin.snapshotPath?.takeIf { java.io.File(it).exists() }
                when {
                    resumeUrl == null -> Unit
                    snap != null -> w.showSnapshotUntilUsed(snap, resumeUrl)
                    else -> w.loadUrl(resumeUrl)
                }
            }
        }

    /** Called once per new window, before its first load. */
    var onBrowserWindowCreated: ((BrowserWindowView) -> Unit)? = null

    /** Called as a window's pin is deleted, before the WebView is destroyed. */
    var onBrowserWindowReleased: ((BrowserWindowView) -> Unit)? = null

    /**
     * The window under a screen point, if any — for click/gesture routing.
     *
     * TOPMOST in draw order, not first in map order: when cards stack, the
     * wearer is pointing at the one they can SEE, and map order is just
     * creation order. In the overlap region the front card wins; on the
     * peeking strip only the back card contains the point, so a tap there
     * reaches it — and the click's activation then raises it.
     */
    fun browserWindowAt(screenX: Float, screenY: Float): BrowserWindowView? =
        browserWindows.values
            .filter { it.containsScreenPoint(screenX, screenY) }
            .maxByOrNull { boardChildIndexOf(it) }

    /** The board child that carries [v] (the view itself, or its wrapper). */
    private fun boardChildIndexOf(v: View): Int {
        var cur: View = v
        while (cur.parent !== board) {
            cur = (cur.parent as? View) ?: return -1
        }
        return board.indexOfChild(cur)
    }

    /**
     * Bring a window to the front of the stack, and KEEP it there: the
     * board rebuilds on every store change, which resets child order to
     * pin order, and a raise that silently undid itself on the next
     * bookmark or live-card refresh would read as flaky. The raised pin
     * is remembered and re-applied after every render.
     */
    fun raiseToFront(window: BrowserWindowView) {
        lastRaisedPinId = pinIdFor(window)
        var cur: View = window
        while (cur.parent !== board) {
            cur = (cur.parent as? View) ?: return
        }
        board.bringChildToFront(cur)
        board.invalidate()
    }

    private var lastRaisedPinId: String? = null

    /** Every live window, for lifecycle forwarding and "deactivate the others". */
    fun browserWindows(): Collection<BrowserWindowView> = browserWindows.values

    /** Dump how the board actually allocated space. Debug builds only. */
    fun debugDumpLayout() = uiHandler.postDelayed({
        val z = lastZone
        android.util.Log.i(
            "X3HubBoard",
            "zone=${z?.left},${z?.top} -> ${z?.right},${z?.bottom} " +
                "(${(z?.right ?: 0) - (z?.left ?: 0)}x${(z?.bottom ?: 0) - (z?.top ?: 0)}) " +
                "board=${board.width}x${board.height} pins=${pinsSnapshot.size}"
        )
        pinsSnapshot.forEach { pin ->
            val v = pinViews[pin.id]
            val lp = v?.layoutParams as? FrameLayout.LayoutParams
            val inZone = if (lp != null && z != null) {
                lp.leftMargin >= z.left && lp.topMargin >= z.top &&
                    lp.leftMargin + (lp.width) <= z.right &&
                    lp.topMargin + (lp.height) <= z.bottom
            } else false
            // Selection state, because "which window does the app think is
            // picked" is invisible from outside and was the whole answer to
            // "Gemini says no page is open" while three were on the board.
            // Every window draws a border, so a screenshot cannot tell you
            // this and neither could this dump until now.
            val w = browserWindows[pin.id]
            val sel = w?.let { " state=${it.state} focusAge=${
                android.os.SystemClock.uptimeMillis() - it.lastFocusMs}ms" }.orEmpty()
            android.util.Log.i(
                "X3HubBoard",
                "  ${pin.type.padEnd(9)} '${pin.label.take(18)}' " +
                    "box=${lp?.width}x${lp?.height} at=${lp?.leftMargin},${lp?.topMargin} " +
                    "measured=${v?.width}x${v?.height} laidOut=${v?.isLaidOut} " +
                    "attached=${v?.parent != null} fitsInZone=$inZone$sel"
            )
        }
    }, 1500L)

    /** Windows with their pin ids — the host needs the id to persist state. */
    fun browserWindowEntries(): List<Pair<String, BrowserWindowView>> =
        browserWindows.entries.map { it.key to it.value }

    /** The pin id backing a window, so a caller can close it via the store. */
    /** The pin under a screen point, if any. */
    fun pinAt(screenX: Float, screenY: Float): String? =
        topPinAt(screenX, screenY)?.key

    /**
     * The topmost pin at a point, in DRAW order. Every hit-test the taps
     * go through resolves this way, or a stack misbehaves: the first-in-map
     * rule sent a triple-tap on the FRONT card into MODIFY on the buried
     * one — the wearer watched the wrong window sprout a delete chip.
     */
    private fun topPinAt(screenX: Float, screenY: Float): Map.Entry<String, FrameLayout>? =
        pinViews.entries
            .filter { (_, v) -> viewContains(v, screenX, screenY) }
            .maxByOrNull { (_, v) -> board.indexOfChild(v) }

    fun pinIdFor(window: BrowserWindowView): String? =
        browserWindows.entries.firstOrNull { it.value === window }?.key

    /** Drop a window whose pin has gone, so its WebView is not leaked. */
    fun releaseBrowserWindow(pinId: String) {
        browserWindows.remove(pinId)?.destroy()
    }

    /** Container FrameLayout: content + (hidden until modify) ✕ chip. */
    private fun buildPinView(pin: HudPin): FrameLayout {
        // A browser window still on the board is REUSED whole — see the
        // retained-render note in render(). Only the box it declares can
        // have changed (the wearer may have stepped its size ladder); the
        // caller sets the margins, as it does for a fresh container.
        if (pin.type == BrowserTool.TYPE_BROWSER) {
            val kept = pinViews[pin.id]?.takeIf { it.parent === board }
            val window = browserWindows[pin.id]
            if (kept != null && window != null) {
                (kept.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.width = window.windowWidth
                    lp.height = window.windowHeight
                    kept.layoutParams = lp
                }
                // Re-bound to THIS render's pin object: the old lambda
                // captured the pin as it was, and openPin reads its fields.
                kept.setOnClickListener {
                    if (modifyPinId != null) exitModifyMode() else openPin(pin)
                }
                return kept
            }
        }
        val container = FrameLayout(activity)
        val content: View = when (pin.type) {
            HudPinStore.TYPE_PICTURE -> buildPictureContent(pin)
            HudPinStore.TYPE_BOOKMARK -> buildBookmarkContent(pin)
            HudPinStore.TYPE_LIVE -> buildLiveContent(pin)
            HudPinStore.TYPE_COUNTDOWN -> buildCountdownContent(pin)
            BrowserTool.TYPE_BROWSER -> browserWindowFor(pin)
            else -> buildNoteContent(pin)
        }
        val (w, h) = when (pin.type) {
            // A browser window carries its own size — it is on a fixed 3:4
            // ladder the wearer can step through — so the board takes the
            // window's word for it rather than imposing a box.
            BrowserTool.TYPE_BROWSER -> (content as BrowserWindowView).let {
                it.windowWidth to it.windowHeight
            }
            // Notes and live cards hug their rendered content. The previous
            // fixed boxes stretched both the visible background and the
            // clickable hit target far beyond short text.
            HudPinStore.TYPE_NOTE -> measureContent(content, dp(NOTE_MAX_WIDTH_DP))
            HudPinStore.TYPE_PICTURE -> dp(64) to dp(48)
            // Portrait, because the pages are: the windows themselves are a
            // 3:4 ladder, and a landscape box would letterbox every capture.
            HudPinStore.TYPE_BOOKMARK -> dp(66) to dp(96)
            HudPinStore.TYPE_LIVE -> measureContent(content, dp(LIVE_MAX_WIDTH_DP))
            // One line of label + time — deliberately small, a countdown is
            // glanceable status, not content.
            HudPinStore.TYPE_COUNTDOWN -> dp(132) to dp(24)
            else -> dp(92) to dp(46)
        }
        container.layoutParams = FrameLayout.LayoutParams(w, h)
        container.elevation = 6f * density
        container.isClickable = true
        container.isFocusable = true
        container.tag = pin.id
        // A very short content-sized pin can be narrower than the modify
        // chip; let that temporary control overhang without enlarging the
        // pin's normal visual or hit bounds.
        container.clipChildren = false
        container.clipToPadding = false
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        // taps must land on the CONTAINER (the hit-test walks descendants
        // in reverse; a clickable child would steal the tap from it).
        content.isClickable = false
        content.isFocusable = false
        // Browser windows are the one content view we reuse across renders
        // (a WebView holds page state that must survive a re-layout), so it
        // arrives still attached to the container built last time. Every
        // other pin type builds fresh and has no parent.
        (content.parent as? ViewGroup)?.removeView(content)
        container.addView(content)

        container.setOnClickListener {
            if (modifyPinId != null) exitModifyMode() else openPin(pin)
        }

        // A browser window steps through its own size ladder, and the box
        // above was measured ONCE when the pin was built. Nothing told the
        // container about it afterwards — onWindowSizeChanged was declared,
        // fired, and listened to by nobody — so after a resize the container
        // still held the old dimensions. The window drew at its new size
        // inside a stale box, and the ✕ chip, which hangs off the
        // container's top-right by gravity, appeared away from the window's
        // actual corner: floating inside a shrunken window, or stranded
        // short of a grown one.
        if (content is BrowserWindowView) {
            content.onWindowSizeChanged = { newW, newH ->
                (container.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    if (lp.width != newW || lp.height != newH) {
                        lp.width = newW
                        lp.height = newH
                        // Growing near an edge would otherwise push the
                        // window off the board, taking the chip with it.
                        clampToZone(lp, newW, newH, lastZone ?: computeZone())
                        container.layoutParams = lp
                    }
                }
            }
        }
        return container
    }

    /**
     * Measure a wrap-content surface before it is attached to [board].
     * render() needs concrete positive dimensions immediately for flow,
     * collision avoidance, clamping, and move-centering, so leaving the
     * container itself as WRAP_CONTENT would feed -2 into that geometry.
     */
    private fun measureContent(content: View, maxWidthPx: Int): Pair<Int, Int> {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return content.measuredWidth.coerceAtLeast(1) to
            content.measuredHeight.coerceAtLeast(1)
    }

    private fun buildNoteContent(pin: HudPin): View {
        val tv = TextView(activity)
        tv.text = pin.payload.ifBlank { pin.label }
        tv.setTextColor(0xFF1B1B10.toInt())
        tv.textSize = 10.4f
        tv.maxLines = 4
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setPadding(dp(6), dp(5), dp(6), dp(5))
        // post-it yellow, near-opaque so the dark text survives outdoors
        tv.background = GradientDrawable().apply {
            setColor(0xF2FFEE58.toInt())
            cornerRadius = 2f * density
        }
        return tv
    }

    /**
     * Live card: dark chip. Header = accent label + last-update age;
     * body = the engine's latest text. Stale or never-refreshed cards
     * render dimmed.
     */
    private fun buildLiveContent(pin: HudPin): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(7), dp(3), dp(7), dp(4))
        col.background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = 3f * density
            if (pin.stale) setStroke(dp(1), 0x66FF5252)
        }

        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val label = TextView(activity)
        label.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        label.text = pin.label.uppercase(Locale.US)
        label.setTextColor(0xFF7FDBFF.toInt())
        label.textSize = 9.2f
        label.typeface = Typeface.DEFAULT_BOLD
        label.maxLines = 1
        // Cap so a long title still leaves the age beside it (not off-card).
        label.maxWidth = dp(150)
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        header.addView(label)
        // Beside the title — "WORLD CUP · 5m", or a status reason when the
        // card isn't updating: red for a dead/stale source, amber for an
        // informational state like "rate-limited" (throttled, not broken).
        val trailing = pin.statusNote ?: if (pin.stale) "stale" else ageText(pin.updatedAt)
        if (trailing.isNotBlank()) {
            val age = TextView(activity)
            age.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5); leftMargin = dp(5) }
            age.text = "·  $trailing"
            age.setTextColor(
                when {
                    pin.stale -> 0xFFFF5252.toInt()           // red — dead source
                    pin.statusNote != null -> 0xFFFFB347.toInt() // amber — throttled/info
                    else -> 0x80FFFFFF.toInt()                // dim — normal age
                }
            )
            age.textSize = 9.2f
            header.addView(age)
        }
        col.addView(header)

        val body = TextView(activity)
        // Clearer than a bare "…": a never-loaded card reads "updating…",
        // and one that's failed enough to be stale reads "unavailable".
        body.text = pin.content.ifBlank { if (pin.stale) "unavailable" else "updating…" }
        body.setTextColor(Color.WHITE)
        body.textSize = 10.4f
        body.maxLines = LiveCardEngine.MAX_CARD_LINES
        body.ellipsize = android.text.TextUtils.TruncateAt.END
        body.setLineSpacing(0f, 1.05f)
        col.addView(body)

        col.alpha = if (pin.stale || pin.content.isBlank()) 0.72f else 1f
        return col
    }

    /**
     * Countdown chip: same dark translucent slab as a live card, one row —
     * "drink water · 0:47". The time TextView is registered in
     * [countdownViews] so the ticker can rewrite it without a re-render.
     */
    private fun buildCountdownContent(pin: HudPin): View {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(7), dp(2), dp(7), dp(2))
        row.background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = 3f * density
        }

        val label = TextView(activity)
        label.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        label.text = pin.payload.ifBlank { pin.label }
        label.setTextColor(0xFF7FDBFF.toInt())
        label.textSize = 9.2f
        label.typeface = Typeface.DEFAULT_BOLD
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(label)

        // Separator is its own view so the ticker's text is JUST the clock.
        val dot = TextView(activity)
        dot.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4); leftMargin = dp(4) }
        dot.text = "·"
        dot.setTextColor(0x80FFFFFF.toInt())
        dot.textSize = 9.2f
        row.addView(dot)

        val time = TextView(activity)
        time.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(5); leftMargin = dp(5) }
        time.text = remainingText(pin.dueAtMs, System.currentTimeMillis())
        time.setTextColor(Color.WHITE)
        time.textSize = 9.6f
        time.typeface = Typeface.MONOSPACE // fixed width — digits don't jitter
        row.addView(time)

        countdownViews[pin.id] = time to pin.dueAtMs
        return row
    }

    private fun ageText(updatedAt: Long): String {
        if (updatedAt <= 0L) return ""
        val mins = (System.currentTimeMillis() - updatedAt) / 60_000L
        return when {
            mins < 1 -> "now"
            mins < 60 -> "${mins}m"
            else -> "${mins / 60}h"
        }
    }

    private fun buildPictureContent(pin: HudPin): View {
        val iv = ImageView(activity)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.background = GradientDrawable().apply {
            setColor(0xFF10181E.toInt())
            setStroke(dp(1), 0xCCFFFFFF.toInt())
            cornerRadius = 2f * density
        }
        iv.setPadding(dp(1), dp(1), dp(1), dp(1))
        loadPinBitmap(pin) { bmp -> iv.setImageBitmap(bmp) }
        return iv
    }

    /**
     * Thumbnail with the page's name under it. The label matters more here
     * than on a picture pin: at 66px wide a shrunken web page is a coloured
     * smudge, and the title is what the wearer actually reads.
     */
    private fun buildBookmarkContent(pin: HudPin): View {
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        col.background = GradientDrawable().apply {
            setColor(0xFF10181E.toInt())
            setStroke(dp(1), 0xCC7FDBFF.toInt())
            cornerRadius = 2f * density
        }
        col.setPadding(dp(1), dp(1), dp(1), dp(1))

        val iv = ImageView(activity)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        loadPinBitmap(pin) { bmp -> iv.setImageBitmap(bmp) }
        col.addView(iv)

        val title = TextView(activity)
        title.text = pin.label
        title.setTextColor(0xFFDDEEFF.toInt())
        title.textSize = 9f
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        title.setPadding(dp(2), dp(1), dp(2), dp(1))
        title.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        col.addView(title)
        return col
    }

    /** Reopen a saved page in a browser window. */
    private fun openBookmark(pin: HudPin) {
        val url = pin.sourceUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            showToast("That bookmark has no address saved.")
            return
        }
        // Straight through the store, exactly as BrowserTool does — the
        // board's own observer inflates the WebView. A second route to a
        // browser window would be a second place for them to fall out of
        // sync.
        // HudPinStore.add dedupes on type+payload and keeps the EXISTING pin
        // — including its lastUrl. So a window already opened on this address
        // that the wearer has since browsed away from is silently reused and
        // shows wherever it wandered to, which reads as "the bookmark opened
        // the wrong page". Send it back to the bookmark explicitly.
        val existing = HudPinStore.all().firstOrNull {
            it.type == BrowserTool.TYPE_BROWSER && it.payload == url
        }
        val opened = HudPinStore.add(
            HudPinStore.HudPin(
                type = BrowserTool.TYPE_BROWSER,
                label = pin.label,
                payload = url
            )
        )
        if (!opened) {
            showToast("The HUD board is full — remove a pin first.")
            return
        }
        if (existing != null) {
            HudPinStore.updateBrowserResume(existing.id, url, null)
            browserWindows[existing.id]?.loadUrl(url)
        }
        forceCursorVisible()
    }

    private fun openPin(pin: HudPin) {
        when (pin.type) {
            HudPinStore.TYPE_PICTURE -> showFullscreenPicture(pin)
            // A bookmark you can only look at would be pointless — tapping
            // one reopens the page, which is the whole reason it was saved.
            HudPinStore.TYPE_BOOKMARK -> openBookmark(pin)
            HudPinStore.TYPE_LIVE -> {
                // No browser to open a source page in — tap = refresh now.
                HudPinStore.requestRefresh(pin.id)
                showToast("Refreshing \"${pin.label}\"…")
            }
            else -> {
                // Notes are inert surfaces — nothing to open on this build.
            }
        }
    }

    // ------------------------------------------------------------------
    // Fullscreen picture viewer (tap anywhere to dismiss)
    // ------------------------------------------------------------------

    private fun showFullscreenPicture(pin: HudPin) {
        dismissFullscreen()
        val overlayRoot = board.parent as? FrameLayout ?: return
        val frame = FrameLayout(activity)
        frame.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        frame.setBackgroundColor(0xE6000000.toInt())
        frame.elevation = 30f * density
        frame.isClickable = true
        frame.isFocusable = true

        val iv = ImageView(activity)
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(24), dp(20), dp(24), dp(28)) }
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        frame.addView(iv)

        if (pin.label.isNotBlank()) {
            val caption = TextView(activity)
            caption.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(6) }
            caption.text = pin.label
            caption.setTextColor(Color.WHITE)
            caption.textSize = 11f
            caption.setShadowLayer(3f * density, 0f, 1f, Color.BLACK)
            frame.addView(caption)
        }

        frame.setOnClickListener { dismissFullscreen() }
        overlayRoot.addView(frame)
        fullscreenView = frame
        forceCursorVisible()
        loadPinBitmap(pin) { bmp -> iv.setImageBitmap(bmp) }
    }

    fun dismissFullscreen(): Boolean {
        val v = fullscreenView ?: return false
        (v.parent as? FrameLayout)?.removeView(v)
        fullscreenView = null
        return true
    }

    fun isFullscreenShowing(): Boolean = fullscreenView != null

    private fun loadPinBitmap(pin: HudPin, onReady: (Bitmap) -> Unit) {
        bitmapCache.get(pin.id)?.let { onReady(it); return }
        Thread {
            val bmp: Bitmap? = try {
                val src = pin.payload
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    URL(src).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(src, opts)
                    val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1280)
                    BitmapFactory.decodeFile(
                        src, BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                bitmapCache.put(pin.id, bmp)
                uiHandler.post { onReady(bmp) }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Hud-modify mode (double-tap with the cursor over a pin)
    // ------------------------------------------------------------------

    /**
     * Double-tap hook. Returns true when the tap landed on a pin and
     * modify mode engaged (caller should consume the gesture and skip
     * the Gemini-toggle stage). Exiting on a second double-tap is the
     * caller's branch — it checks [isInModifyMode] first.
     */
    fun onDoubleTapAt(screenX: Float, screenY: Float): Boolean {
        // Topmost, always: the wearer is pointing at the card they can SEE.
        val hit = topPinAt(screenX, screenY) ?: return false
        enterModifyMode(hit.key)
        return true
    }

    private fun enterModifyMode(pinId: String) {
        if (modifyPinId != null && modifyPinId != pinId) exitModifyMode()
        val container = pinViews[pinId] ?: return
        modifyPinId = pinId
        forceCursorVisible()
        container.scaleX = 1.08f
        container.scaleY = 1.08f
        container.elevation = 12f * density

        // One chip only: ✕ deletes. Moving needs no chip — the NEXT tap
        // anywhere in the zone places the pin there and the mode exits
        // immediately.
        container.addView(buildChip("✕", 0xE6D32F2F.toInt(), Gravity.TOP or Gravity.END) {
            val id = modifyPinId ?: return@buildChip
            exitModifyMode()
            HudPinStore.remove(id)
            showToast("Pin removed")
        }.also { it.tag = CHIP_TAG })
    }

    private fun buildChip(
        glyph: String,
        color: Int,
        gravity: Int,
        onTap: () -> Unit
    ): TextView {
        val chip = TextView(activity)
        val size = dp(20)
        chip.layoutParams = FrameLayout.LayoutParams(size, size, gravity)
        chip.gravity = Gravity.CENTER
        chip.text = glyph
        chip.setTextColor(Color.WHITE)
        chip.textSize = 10f
        chip.typeface = Typeface.DEFAULT_BOLD
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), Color.WHITE)
        }
        chip.elevation = 14f * density
        chip.isClickable = true
        chip.setOnClickListener { onTap() }
        return chip
    }

    /**
     * Consumes the NEXT tap while modify mode is active — called by
     * MainActivity at the top of the overlay tap dispatch, before the
     * normal hit-test.
     *
     *   • Tap on the ✕ chip → DELETE the pin, exit modify mode.
     *   • Tap anywhere else → MOVE the pin there (centered on the tap,
     *     clamped inside the zone), exit modify mode.
     */
    fun onOverlayTapWhileModify(screenX: Float, screenY: Float): Boolean {
        val id = modifyPinId ?: return false
        val container = pinViews[id] ?: run {
            exitModifyMode()
            return false
        }
        val chip = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == CHIP_TAG }
        if (chip != null && viewContains(chip, screenX, screenY, slackPx = dp(6))) {
            exitModifyMode()
            HudPinStore.remove(id)
            showToast("Pin removed")
            return true
        }
        val boardLoc = IntArray(2)
        board.getLocationOnScreen(boardLoc)
        val w = container.width.takeIf { it > 0 } ?: container.layoutParams.width
        val h = container.height.takeIf { it > 0 } ?: container.layoutParams.height
        val lp = container.layoutParams as FrameLayout.LayoutParams
        val zone = lastZone ?: computeZone()
        // The SAME resolve the ghost just showed. Committing from the raw
        // cursor instead would put the pin somewhere the wearer had been
        // shown it would not go, which is worse than having no snapping.
        val (sx, sy) = resolveSnap(
            (screenX - boardLoc[0] - w / 2f).toInt(),
            (screenY - boardLoc[1] - h / 2f).toInt(),
            w, h, id, zone
        )
        lp.leftMargin = sx
        lp.topMargin = sy
        clampToZone(lp, w, h, zone)
        container.layoutParams = lp
        exitModifyMode()
        // Square up the same-size neighbours around the landing spot, then
        // persist the anchor AND the settled cluster as one store write —
        // one render, one visible motion.
        val settled = settleClusterAround(id, zone)
        HudPinStore.updatePositions(
            settled + (id to (lp.leftMargin to lp.topMargin))
        )
        return true
    }

    // ── Magnetic tiling ──────────────────────────────────────────────

    /** Total px this rect would cover of any hand-placed pin. */
    /**
     * Stacked is FINE, buried is not: a card drawn almost exactly over
     * another leaves the one behind invisible with no clue it exists. When
     * the placed card eclipses (or is eclipsed by) an already-placed one —
     * intersection at least [ECLIPSE_PERCENT] of the smaller card — it is
     * nudged down-right from that card's corner by [CASCADE_PX], the
     * classic window-manager cascade, so an L-strip of the card behind
     * stays visible and clickable. One step, not a search: the peek strip
     * is the goal, and a deterministic nudge keeps every render agreeing
     * with the last one. The clamp can eat the nudge at the zone's far
     * corner; a buried card there is the least-bad honest outcome.
     */
    private fun cascadeIfEclipsed(
        lp: FrameLayout.LayoutParams,
        w: Int,
        h: Int,
        rects: List<IntArray>,
        zone: Zone
    ) {
        for (r in rects) {
            val il = maxOf(lp.leftMargin, r[0])
            val it2 = maxOf(lp.topMargin, r[1])
            val ir = minOf(lp.leftMargin + w, r[2])
            val ib = minOf(lp.topMargin + h, r[3])
            if (ir <= il || ib <= it2) continue
            val inter = (ir - il).toLong() * (ib - it2)
            val areaA = w.toLong() * h
            val areaB = (r[2] - r[0]).toLong() * (r[3] - r[1])
            if (inter * 100 >= ECLIPSE_PERCENT * minOf(areaA, areaB)) {
                lp.leftMargin = r[0] + CASCADE_PX
                lp.topMargin = r[1] + CASCADE_PX
                clampToZone(lp, w, h, zone)
                return
            }
        }
    }

    private fun overlapArea(l: Int, t: Int, w: Int, h: Int, rects: List<IntArray>): Long {
        var sum = 0L
        for (r in rects) {
            val ox = minOf(l + w, r[2]) - maxOf(l, r[0])
            val oy = minOf(t + h, r[3]) - maxOf(t, r[1])
            if (ox > 0 && oy > 0) sum += ox.toLong() * oy
        }
        return sum
    }

    // ── Cluster settle ───────────────────────────────────────────────

    /**
     * After a pin is PLACED, square up its same-size neighbours around it.
     *
     * The drag magnets align the pin in the wearer's hand; this aligns the
     * pins already on the board. The trigger is deliberate: placement is
     * the one moment where there is an unambiguous anchor, an unambiguous
     * intent ("this cluster matters to me right now"), and a wearer who is
     * WATCHING — a board that rearranges itself at render time behind the
     * wearer's back is the version that was built once and thrown away.
     *
     * The rules are the standard ones from design-tool tidy-up:
     *
     *  ANCHOR IS LAW. The pin the wearer just placed does not move again —
     *  neighbours conform to it, never the reverse. Moving the thing the
     *  wearer just deliberately positioned is the cardinal sin of every
     *  auto-layout that gets turned off.
     *
     *  SAME SIZE ONLY. Two 170x226 windows beside each other are a row and
     *  everyone can see how it should look. A 66x96 bookmark beside a
     *  226-tall window has no such answer, and guessing produced last
     *  night's 3px-into-the-neighbour bug. Exact width AND height match.
     *
     *  ADJACENT MEANS ADJACENT. Strict perpendicular overlap (a stacked
     *  card is not "beside" a window — measured, that mistake pulled
     *  windows toward a card in a different row), and the facing edges
     *  within [SETTLE_DP]. Alignment: shared top for a row, shared left
     *  for a column, and the gap normalised to exactly [GAP_DP].
     *
     *  IT CHAINS. Each settled neighbour anchors ITS neighbours, so
     *  placing one window squares the whole row, not just the pair. BFS
     *  with a visited set; already-perfect neighbours still propagate.
     *
     *  NEVER INTO A COLLISION. A move that would land a neighbour within
     *  [GAP_DP] of anything else, or that the zone clamp would bend, is
     *  skipped — that pin stays put and does not propagate. Skipping
     *  beats "mostly aligned but now overlapping".
     *
     * Returns id -> (x, y) for every pin that should move; caller batches
     * the store write so the settle is one render, not one per pin.
     */
    private fun settleClusterAround(anchorId: String, zone: Zone): Map<String, Pair<Int, Int>> {
        val gap = dp(GAP_DP)
        val reach = dp(SETTLE_DP)

        data class Box(
            val id: String,
            var l: Int,
            var t: Int,
            val w: Int,
            val h: Int,
            /**
             * May the settle move this pin? Flow-placed pins say no: settling
             * one would write it a custom position, silently freezing it out
             * of the auto-layout forever — it would never reflow again and
             * the wearer never chose that. They still block, they just do
             * not follow. (Review finding.)
             */
            val movable: Boolean
        )

        val flowIds = pinsSnapshot
            .filter { it.customX < 0 || it.customY < 0 }
            .mapTo(mutableSetOf()) { it.id }
        val boxes = ArrayList<Box>(pinViews.size + 1)
        for ((id, v) in pinViews) {
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            val w = lp.width.takeIf { it > 0 } ?: v.width
            val h = lp.height.takeIf { it > 0 } ?: v.height
            if (w <= 0 || h <= 0) continue
            boxes += Box(id, lp.leftMargin, lp.topMargin, w, h, id !in flowIds)
        }
        // The live camera preview shares the zone. render() already refuses
        // to tile grid pins under it; the settle gets the same rule via a
        // pseudo-box that can never move and never chain. (Review finding.)
        activity.findViewById<View?>(R.id.unipanelCameraPreviewFrame)?.let { cam ->
            if (cam.visibility == View.VISIBLE && cam.width > 0) {
                boxes += Box(
                    " camera", boardLocalX(cam), boardLocalY(cam),
                    cam.width, cam.height, movable = false
                )
            }
        }
        val anchor = boxes.firstOrNull { it.id == anchorId } ?: return emptyMap()

        val moves = LinkedHashMap<String, Pair<Int, Int>>()
        val visited = mutableSetOf(anchorId)
        val queue = ArrayDeque<Box>()
        queue += anchor

        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            for (b in boxes) {
                if (b.id in visited) continue
                if (!b.movable) continue
                if (b.w != a.w || b.h != a.h) continue
                // The perpendicular offset is bounded by the same reach as
                // the facing gap. Bare overlap is NOT enough: two 226-tall
                // windows in a 430-tall zone ALWAYS overlap vertically, so
                // without the bound a window parked at the top of the zone
                // could be yanked 204px down to join a row it was never
                // part of — the moved-what-the-wearer-placed failure class
                // that got the render-time pass deleted. (Review finding.)
                val offV = kotlin.math.abs(b.t - a.t)
                val offH = kotlin.math.abs(b.l - a.l)
                val overlapV = b.t < a.t + a.h && b.t + b.h > a.t && offV <= reach
                val overlapH = b.l < a.l + a.w && b.l + b.w > a.l && offH <= reach
                // Facing-edge distance on the packing axis, negative = overlap.
                val dxRight = b.l - (a.l + a.w)
                val dxLeft = a.l - (b.l + b.w)
                val dyBelow = b.t - (a.t + a.h)
                val dyAbove = a.t - (b.t + b.h)
                var tx = b.l
                var ty = b.t
                when {
                    overlapV && dxRight > -gap && dxRight <= reach -> {
                        tx = a.l + a.w + gap; ty = a.t
                    }
                    overlapV && dxLeft > -gap && dxLeft <= reach -> {
                        tx = a.l - gap - b.w; ty = a.t
                    }
                    overlapH && dyBelow > -gap && dyBelow <= reach -> {
                        ty = a.t + a.h + gap; tx = a.l
                    }
                    overlapH && dyAbove > -gap && dyAbove <= reach -> {
                        ty = a.t - gap - b.h; tx = a.l
                    }
                    else -> continue
                }
                // Already perfect: no move to guard, but it still anchors
                // the rest of its row — a live card that has grown into the
                // gap NEXT TO it must not sever the chain. (Review finding.)
                if (tx == b.l && ty == b.t) {
                    visited += b.id
                    queue += b
                    continue
                }
                // In-zone, un-bent by the clamp?
                val probe = FrameLayout.LayoutParams(b.w, b.h)
                    .apply { leftMargin = tx; topMargin = ty }
                clampToZone(probe, b.w, b.h, zone)
                if (probe.leftMargin != tx || probe.topMargin != ty) continue
                // Colliders are judged against PLANNED positions (boxes are
                // mutated as moves are accepted). A collider that is itself
                // the next link of the chain — same size, not yet settled,
                // and adjacent to where b is GOING — does not veto the move:
                // it will be pushed onward when b is processed as an anchor.
                // Without this a row of three could never settle, because
                // squaring the middle window put its edge exactly against
                // the third, and the guard refused. Anything else in the
                // way is a hard stop for this neighbour.
                var hardCollision = false
                for (o in boxes) {
                    if (o.id == b.id) continue
                    val tooClose =
                        tx < o.l + o.w + gap && tx + b.w + gap > o.l &&
                            ty < o.t + o.h + gap && ty + b.h + gap > o.t
                    if (!tooClose) continue
                    val chainable = o.id !in visited && o.movable &&
                        o.w == b.w && o.h == b.h &&
                        // adjacent to b's TARGET, not to its old spot
                        (o.l - (tx + b.w)) <= reach && (tx - (o.l + o.w)) <= reach &&
                        (o.t - (ty + b.h)) <= reach && (ty - (o.t + o.h)) <= reach
                    if (!chainable) { hardCollision = true; break }
                }
                if (hardCollision) continue
                visited += b.id
                if (tx != b.l || ty != b.t) {
                    b.l = tx
                    b.t = ty
                    moves[b.id] = tx to ty
                }
                queue += b
            }
        }

        // TRANSACTIONAL: a chain is only as good as its last link. If a
        // deferred collider never got pushed (its own move was refused by
        // the zone or by something solid), the planned board holds a moved
        // pin closer than the gap to something — possibly overlapping,
        // possibly merely touching, both worse than the board the wearer
        // arranged. The per-move guard only ever waives clearance for chain
        // candidates, and a candidate that DID move sits at exactly the gap
        // by construction, so any violation here is precisely a failed
        // link. Settle nothing rather than persist it.
        val bad = moves.keys.any { id ->
            val m = boxes.first { it.id == id }
            boxes.any { o ->
                o.id != id &&
                    m.l < o.l + o.w + gap && m.l + m.w + gap > o.l &&
                    m.t < o.t + o.h + gap && m.t + m.h + gap > o.t
            }
        }
        return if (bad) emptyMap() else moves
    }

    /** Does this rect land within [gap] of any hand-placed pin? */
    private fun collidesWithCustom(
        lp: FrameLayout.LayoutParams,
        w: Int,
        h: Int,
        customRects: List<IntArray>,
        gap: Int
    ): Boolean = customRects.any { r ->
        lp.leftMargin < r[2] + gap && lp.leftMargin + w + gap > r[0] &&
            lp.topMargin < r[3] + gap && lp.topMargin + h + gap > r[1]
    }

    /** The snapped edge chosen on each axis last frame, for hysteresis. */
    private var snappedX: Int? = null
    private var snappedY: Int? = null

    /** Whether the last resolve actually locked onto something. */
    private var snapEngaged = false

    /**
     * Pull a dragged pin onto a neighbouring window's edge, if it is close.
     *
     * Snapping to OTHER WINDOWS rather than to a fixed lattice is the whole
     * point: a lattice quantises everywhere, so every position becomes
     * somebody else's idea of correct and open space stops being free. Edge
     * snapping only pulls where tiling is actually meaningful, which is what
     * lets "drop it roughly there" and "tile it exactly" be the same gesture.
     *
     * The candidates per axis are the four that matter — butt against the
     * neighbour's far side, butt against its near side, or align with either
     * of its edges — plus the zone's own edges. Because the size ladder has
     * fixed rungs, two same-rung windows butted together are perfectly
     * tiled by construction; nothing here has to know that.
     *
     * There is no stickiness to escape from. Both the ghost and the commit
     * resolve from the CURRENT cursor position every time, so nothing
     * accumulates: move a few px past the threshold and the pull is simply
     * gone next frame. That is the failure this avoids — a snap that tracks
     * its own output drifts behind the finger and reads as the app fighting
     * you.
     *
     * EVERY pin is a magnet, not just the windows. Restricting it to
     * windows was the cautious first cut and it left the real raggedness
     * untouched: three bookmarks that were plainly meant to be a row sat at
     * y=143, 146 and 150, because nothing could pull them onto each other.
     * The competing-targets worry that motivated the restriction is handled
     * where it belongs — nearest-wins plus hysteresis — rather than by
     * making most of the board unalignable.
     */
    private fun resolveSnap(
        rawX: Int,
        rawY: Int,
        w: Int,
        h: Int,
        movingId: String?,
        zone: Zone
    ): Pair<Int, Int> {
        val gap = dp(GAP_DP)
        val threshold = dp(SNAP_DP)
        val xs = ArrayList<Int>(48)
        val ys = ArrayList<Int>(48)

        // A pin is only a magnet for the axis it is actually BESIDE you on.
        // Without this the nearest edge in the whole board wins regardless of
        // where it is: dragging a window into the row at y=248 snapped its
        // left to 230, the right edge of a news card living at y=44..232 —
        // two things that never touch. It read as the window landing
        // somewhere arbitrary, and it pulled AWAY from the window it was
        // being placed next to. Requiring overlap on the perpendicular axis
        // is what makes the pull mean "put it beside that".
        for ((id, v) in pinViews) {
            if (id == movingId) continue
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            val ow = lp.width
            val oh = lp.height
            if (ow <= 0 || oh <= 0) continue
            val l = lp.leftMargin
            val t = lp.topMargin
            // STRICT overlap, no slack. Slack was the first attempt and it
            // let a stacked pin count as a side-by-side one: a news card
            // ending at y=232 was treated as beside a window starting at
            // y=251, so the window kept snapping its left edge to the card's
            // right edge — two objects that are above and below each other,
            // not next to each other. If the spans do not actually cross,
            // there is no shared row to tile into.
            val besideVertically = rawY < t + oh && rawY + h > t
            val besideHorizontally = rawX < l + ow && rawX + w > l
            if (besideVertically) {
                xs += l + ow + gap  // sit to its right
                xs += l - w - gap   // sit to its left
                xs += l             // left edges flush
                xs += l + ow - w    // right edges flush
            }
            if (besideHorizontally) {
                ys += t + oh + gap  // sit below it
                ys += t - h - gap   // sit above it
                ys += t             // top edges flush
                ys += t + oh - h    // bottom edges flush
            }
        }
        // The zone's own edges, so a row can be squared off against the
        // display rather than floating a few px inside it.
        xs += zone.left
        xs += zone.right - w
        ys += zone.top
        ys += zone.bottom - h

        snappedX = nearest(rawX, xs, threshold, snappedX)
        snappedY = nearest(rawY, ys, threshold, snappedY)
        snapEngaged = snappedX != null || snappedY != null
        return (snappedX ?: rawX) to (snappedY ?: rawY)
    }

    /**
     * Nearest candidate within [threshold], preferring the one already held.
     *
     * The preference is hysteresis, and it is not optional: with several
     * windows on the board two candidates land within a few px of each other
     * often, and without it the ghost flickers between them while the hand
     * is holding still.
     */
    private fun nearest(v: Int, cands: List<Int>, threshold: Int, held: Int?): Int? {
        var best: Int? = null
        var bestD = threshold + 1
        for (c in cands) {
            val d = kotlin.math.abs(c - v)
            if (d <= threshold && d < bestD) { best = c; bestD = d }
        }
        if (held != null && kotlin.math.abs(held - v) <= threshold) {
            // Only give up a held edge for one that is clearly nearer.
            if (best == null || bestD + dp(4) >= kotlin.math.abs(held - v)) return held
        }
        return best
    }

    private fun clearSnap() {
        snappedX = null
        snappedY = null
        snapEngaged = false
    }

    // ── Move preview ─────────────────────────────────────────────────

    private var movePreview: View? = null

    /**
     * Show where the pin would land, as a dashed outline at the cursor.
     *
     * Moving a pin used to be blind: the wearer entered modify mode, tapped
     * a spot, and only then saw whether they had put it where they meant —
     * on a display where a pin is 66px wide and the cursor is a small arrow,
     * that is a guess. The ghost is the same size as the pin and clamped the
     * same way the commit is, so what it shows is exactly what will happen.
     */
    fun updateMovePreview(screenX: Float, screenY: Float) {
        val id = modifyPinId
        val container = if (id != null) pinViews[id] else null
        if (container == null) { hideMovePreview(); return }

        val w = container.layoutParams.width
        val h = container.layoutParams.height
        val boardLoc = IntArray(2)
        board.getLocationOnScreen(boardLoc)
        val lp = (movePreview?.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(w, h)
        lp.width = w
        lp.height = h
        val zone = lastZone ?: computeZone()
        val rawX = (screenX - boardLoc[0] - w / 2f).toInt()
        val rawY = (screenY - boardLoc[1] - h / 2f).toInt()
        val (sx, sy) = resolveSnap(rawX, rawY, w, h, id, zone)
        lp.leftMargin = sx
        lp.topMargin = sy
        clampToZone(lp, w, h, zone)

        val view = movePreview ?: View(activity).also { v ->
            v.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                // Dashed, and amber to match the modify border — a solid
                // outline would read as another pin rather than a target.
                setStroke(dp(1), 0xFFFFB347.toInt(), 6f * density, 4f * density)
                cornerRadius = 2f * density
            }
            v.elevation = 8f * density
            v.isClickable = false
            v.isFocusable = false
            board.addView(v)
            movePreview = v
        }
        view.layoutParams = lp
        // Dashed while free, solid while locked onto a neighbour. The border
        // is already the thing the wearer is watching, so it can answer
        // "will this land where I think" without adding any new chrome —
        // and on a 66px pin at arm's length, a state you have to infer from
        // a few px of position change is a state you cannot see.
        (view.background as? GradientDrawable)?.setStroke(
            dp(if (snapEngaged) 2 else 1),
            if (snapEngaged) 0xFF7FDBFF.toInt() else 0xFFFFB347.toInt(),
            if (snapEngaged) 0f else 6f * density,
            if (snapEngaged) 0f else 4f * density
        )
        view.visibility = View.VISIBLE
    }

    private fun hideMovePreview() {
        // Held edges belong to the drag that is ending; carrying them into
        // the next one would snap the next pin to a magnet it was never
        // near, from the first frame.
        clearSnap()
        val v = movePreview ?: return
        movePreview = null
        runCatching { board.removeView(v) }
    }

    fun isInModifyMode(): Boolean = modifyPinId != null

    fun exitModifyMode() {
        hideMovePreview()
        val container = pinViews[modifyPinId] ?: run {
            modifyPinId = null
            return
        }
        modifyPinId = null
        container.scaleX = 1f
        container.scaleY = 1f
        container.elevation = 6f * density
        removeChips(container)
    }

    private fun removeChips(container: FrameLayout) {
        val chips = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filter { it.tag == CHIP_TAG }
        chips.forEach { container.removeView(it) }
    }

    private fun viewContains(v: View, screenX: Float, screenY: Float, slackPx: Int = 0): Boolean {
        if (v.visibility != View.VISIBLE || v.width == 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return screenX >= loc[0] - slackPx && screenX < loc[0] + v.width + slackPx &&
            screenY >= loc[1] - slackPx && screenY < loc[1] + v.height + slackPx
    }

    companion object {
        private const val CHIP_TAG = "hud_pin_chip"

        /**
         * How near an edge has to be, in dp, before the pin is pulled onto
         * it. Wide enough to catch a cursor driven by a temple pad, which is
         * a good deal shakier than a mouse; well short of the 40px tap slop,
         * so aiming at a gap between two windows still lands in the gap.
         *
         * Started at 14 and it was reported as "I'm not noticing it" — 14px
         * on a shaky cursor means you have to already be placing the window
         * correctly for the magnet to help, which is the one case where help
         * is worth nothing. Safe to widen now that a magnet only pulls
         * toward pins you are genuinely beside.
         */
        private const val SNAP_DP = 26

        /**
         * Breathing room between any two pins, used by BOTH the flow grid
         * and the magnets so a hand-placed pin sits on the same rhythm as an
         * auto-placed one. At the old 6px, tiles read as one seam rather
         * than as separate objects — a gap has to survive being looked at
         * through a waveguide at arm's length, where a few px of dark
         * between two dark panels is not a gap at all.
         *
         * Three windows at the 170px rung still fit a 628px zone with room
         * to spare: 3*170 + 2*12 = 534.
         */
        private const val GAP_DP = 12

        /**
         * Cascade step for a card that would bury another — big enough
         * that the strip left showing is a comfortable tap target on this
         * cursor, small enough that the stack still reads as one pile.
         */
        private const val CASCADE_PX = 26

        /** Intersection over the SMALLER card that counts as buried. */
        private const val ECLIPSE_PERCENT = 80L

        /**
         * How far apart two same-size pins' facing edges may be and still
         * count as "meant to be adjacent" when a placement settles the
         * cluster. Wider than the drag threshold: by the time the wearer
         * has placed something, a near-miss of a few dozen px is clutter,
         * not a decision.
         */
        private const val SETTLE_DP = 40

        // Content-sized notes/live cards retain their former widths as caps.
        private const val NOTE_MAX_WIDTH_DP = 100
        private const val LIVE_MAX_WIDTH_DP = 240

        /**
         * The under-HUD content zone in the overlay's LOGICAL 640×480
         * coordinate space. Top = 44 (2px margin + 36px HUD strip + gap),
         * matching the top line of TapInsight's calibrated shelf; the
         * rest of the viewport belongs to pins / camera preview. Raw px
         * on purpose — the calibration space is overlay units, not dp.
         */
        val UNDER_HUD_ZONE = android.graphics.Rect(6, 44, 634, 474)
    }
}
