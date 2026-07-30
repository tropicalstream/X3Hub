package com.x3hub.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import com.x3hub.app.BuildConfig
import com.x3hub.app.core.web.AdBlock
import com.x3hub.app.core.web.LocalPages
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * A browser window: a live WebView living inside a HUD pin, plus the
 * activation state that decides whether the wearer's gestures belong to
 * the page or to x3hub.
 *
 * The state machine is the point of this class:
 *
 *   INERT   the cursor crosses the window freely and touches are NOT
 *           forwarded to the page. A window the cursor merely drifts over
 *           must not start eating swipes — with eight windows on the board
 *           that would make the HUD unusable.
 *   ACTIVE  one single click got us here; touches now go to the page.
 *   MODIFY  the pin board's double-tap edit mode (it draws the highlight
 *           and the delete chip itself); this view adds resize on top.
 *
 * MainActivity owns gesture classification — it is the only place that
 * can tell a single click from a double or a triple tap, because taps
 * arrive as KEY events on the temple, not as touches on this view. So
 * everything here is driven from outside: [activate], [deactivate],
 * [setModify], [resizeStep], [forwardTouch].
 *
 * Deliberately NOT carried over from SmartView: its voice layer, the
 * page-agent, the on-screen keyboard and TTS. x3hub has an assistant of
 * its own and those responsibilities are now its.
 */
class BrowserWindowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class WindowState { INERT, ACTIVE, MODIFY }

    /**
     * A WebView that will admit how far it can actually pan.
     *
     * The engine keeps that number behind `protected
     * computeHorizontalScrollRange()`, and the only public way to ask —
     * `canScrollHorizontally()` — is a boolean derived from the CONTAINER
     * view's own scroll offset rather than from the renderer's. Those two
     * are not the same thing, and the difference is why this subclass has
     * to exist: WebView does not override `View.scrollTo`, and unlike
     * ScrollView that inherited implementation writes mScrollX blind, with
     * no clamping at all. The renderer meanwhile clamps what it accepts.
     *
     * Measured on device: `scrollBy(400, 0)` against a real range of 220
     * parked the container at 400 while the page stopped at 220, after
     * which `canScrollHorizontally(1)` reported "nothing to the right" and
     * the next 180px of LEFTWARD input moved nothing at all — the container
     * was spending it walking back to a position the page had never left.
     * Nothing repairs that afterwards; the native->container correction is
     * suppressed by an equality check that the phantom offset never trips.
     * So the pan has to clamp against the true maximum BEFORE every write,
     * and that maximum has to be readable.
     */
    private class PannableWebView(context: Context) : WebView(context) {
        /**
         * Device px of pan available below and to the right of the origin.
         *
         * Read fresh every time rather than cached: the renderer publishes
         * these asynchronously and they keep growing while images load and
         * the page reflows, so a maximum captured while a document was half
         * parsed would strand the wearer part way down a page that had since
         * got longer.
         */
        val maxPanX: Int
            // WebView overrides computeHorizontalScrollRange but NOT
            // computeHorizontalScrollExtent, so the extent is still View's
            // getWidth() — which is exactly the container width the range is
            // built from, making the subtraction the renderer's own maximum.
            get() = (computeHorizontalScrollRange() - computeHorizontalScrollExtent())
                .coerceAtLeast(0)

        val maxPanY: Int
            get() = (computeVerticalScrollRange() - computeVerticalScrollExtent())
                .coerceAtLeast(0)
    }

    private val webView = PannableWebView(context)

    /**
     * Border drawables are built once and swapped by reference. A state
     * change can happen on every tap, and rebuilding a GradientDrawable
     * there would allocate on the touch path for no gain.
     */
    private val borderInert = GradientDrawable().apply {
        setStroke(1, 0x59FFFFFF)
        setColor(Color.TRANSPARENT)
        cornerRadius = 2f
    }
    private val borderActive = GradientDrawable().apply {
        // Same cyan the HUD uses for live-card labels, so "this window has
        // the input" reads as an x3hub state and not as page chrome.
        setStroke(2, 0xFF7FDBFF.toInt())
        setColor(Color.TRANSPARENT)
        cornerRadius = 2f
    }
    private val borderModify = GradientDrawable().apply {
        // Amber = "you are changing something", matching the HUD's amber
        // status convention. Distinct from ACTIVE cyan at a glance, which
        // matters because the two modes take opposite gestures.
        setStroke(2, 0xFFFFB347.toInt())
        setColor(Color.TRANSPARENT)
        cornerRadius = 2f
    }

    /** Scratch for [containsScreenPoint] — hit-tested once per tap. */
    private val locationScratch = IntArray(2)

    var isActive: Boolean = false
        private set

    var isModifying: Boolean = false
        private set

    /**
     * When the wearer last chose this window — opened it, clicked it, or
     * moved it. NOT the same question as [isActive], and separating the two
     * is the point.
     *
     * [isActive] answers "which window receives swipes and taps", so it is
     * exclusive and it is dropped deliberately: entering MODIFY clears it so
     * a resize gesture does not scroll the article, and leaving MODIFY lands
     * in INERT rather than ACTIVE. Both are right for input.
     *
     * But the voice tools were asking [isActive] a different question —
     * "which page does the wearer MEAN" — and with two windows open and
     * neither active they got null and answered "you have no page open" to a
     * wearer looking straight at one. Every window draws a border (INERT is
     * a faint white stroke, ACTIVE cyan), so from the glass the window still
     * looks picked; only the code disagreed.
     *
     * Recency answers the second question and survives the first: a window
     * you just positioned is still the one you mean, even though it
     * correctly stopped taking your swipes.
     */
    var lastFocusMs: Long = SystemClock.uptimeMillis()
        private set

    val state: WindowState
        get() = when {
            isModifying -> WindowState.MODIFY
            isActive -> WindowState.ACTIVE
            else -> WindowState.INERT
        }

    /** Index into [SIZE_LADDER]; [BASE_STEP] is the 170×226 default. */
    var sizeStep: Int = BASE_STEP
        private set

    val windowWidth: Int get() = SIZE_LADDER[sizeStep][0]
    val windowHeight: Int get() = SIZE_LADDER[sizeStep][1]

    /**
     * Current page URL, or null before the first load.
     *
     * While this window is showing a resume still its WebView has loaded
     * nothing and reports "about:blank" — but the window plainly IS a page
     * as far as the wearer is concerned, and callers that ask what it shows
     * must be told the truth. Otherwise a bookmark of a restored window
     * saves about:blank.
     */
    val currentUrl: String?
        get() = deferredUrl ?: webView.url?.takeIf { it.isNotBlank() && it != "about:blank" }

    /**
     * Fired by [requestExit] — the triple-tap-inside-an-active-window
     * path. The activity uses it to bring the x3hub cursor back.
     */
    var onExitRequested: (() -> Unit)? = null

    /**
     * Fired after a [resizeStep] with the new logical px size. This view
     * updates its OWN layoutParams, which is right when it is a direct
     * child of the pin board; a host that wraps it in a pin container has
     * to resize that container, and this is how it learns to.
     */
    var onWindowSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    /**
     * The largest (width, height) this window may grow to, in logical px.
     * The host supplies it because only the host knows the board's free
     * zone; without it the ladder can only be measured against the pin
     * container, which is always exactly the window's current size.
     */
    var maxSizeProvider: (() -> Pair<Int, Int>)? = null

    /** Scroll offset in CSS px, held across a resize reload. */
    private var pendingScrollCssY: Int? = null

    /** Last engine scale reported by onScaleChanged; 0 until the first page. */
    var currentScale: Float = 0f
        private set

    /**
     * Probe: apply an immediate engine zoom and report what the page sees.
     * Exists to settle whether visionOS-style "content scales with the
     * window" can be had without the re-navigation that resize does today.
     */
    /**
     * Service-worker requests never reach a WebViewClient, so a site that
     * fetches its ads through one would sail straight past
     * [requestInterceptor]. The controller is process-global — installing it
     * per window is harmless and idempotent, and it means no window can be
     * created without it.
     */
    private fun installServiceWorkerFilter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(
                object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(
                        request: WebResourceRequest
                    ): WebResourceResponse? = AdBlock.intercept(request)
                }
            )
        }
    }

    // ── Navigation the AGENT cannot do ────────────────────────────────
    // page-agent has no navigate tool and its prompt tells it to stay put,
    // so browsing has to be the app's job.

    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() { webView.reload() }

    /**
     * Scroll by JS rather than by synthesising a drag: a drag lands on
     * whatever is under the cursor, and plenty of pages put their content in
     * an inner scrolling pane where the document itself never moves. A huge
     * delta saturates whichever box actually scrolls, which is what makes
     * "go to the bottom" work on those pages.
     */
    fun scrollByJs(dy: Int) {
        webView.evaluateJavascript(
            """
            (function(dy){
              function scrollable(el){
                if (!el) return false;
                var s = getComputedStyle(el);
                return /auto|scroll/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 4;
              }
              var best = null, bestArea = 0;
              var all = document.querySelectorAll('*');
              for (var i = 0; i < all.length && i < 4000; i++){
                var e = all[i];
                if (!scrollable(e)) continue;
                var r = e.getBoundingClientRect();
                var a = r.width * r.height;
                if (a > bestArea){ bestArea = a; best = e; }
              }
              var docH = document.documentElement.scrollHeight;
              if (best && bestArea > (innerWidth * innerHeight * 0.4) &&
                  docH <= innerHeight + 4) { best.scrollBy(0, dy); }
              else { window.scrollBy(0, dy); }
            })($dy);
            """.trimIndent(),
            null
        )
    }

    // ── Edge scrolling ───────────────────────────────────────────────
    //
    // The wearer parks the cursor near a window's top or bottom rim and the
    // page scrolls until they move away, so one scroll can last many seconds.
    // Driving that from the host would cost a JS bridge call every frame, so
    // instead the host sets a VELOCITY and the document animates itself: one
    // call when the speed changes, one to stop. Picking which box actually
    // scrolls is a DOM sweep, so it happens once per scroll, not per frame.

    private var edgeScrollVx = 0f
    private var edgeScrollVy = 0f

    /**
     * The cached velocity above is a guess about what the PAGE is doing, and
     * it is only worth deduping against while that guess still holds. Set
     * whenever the two are known to have diverged — a new document, the page
     * telling us it stopped by itself, or an axis changing hands between the
     * page and the view-level pan below — so the very next request is
     * delivered whatever its value.
     */
    private var edgeScrollStale = true

    /**
     * What the HOST last asked for, before [panOwnsX] / [panOwnsY] withhold
     * their axis from the page.
     *
     * Kept apart from the pair above, which is what the PAGE was last told,
     * because the two now differ and both are needed. The hand-off is
     * triggered by the page, asynchronously, and the wearer is quite likely
     * holding the cursor perfectly still at that moment: MainActivity's
     * updateEdgeScroll() runs on cursor MOTION and nothing polls, so without
     * a remembered request the take-over would sit there doing nothing until
     * the wearer twitched.
     */
    private var edgeReqVx = 0f
    private var edgeReqVy = 0f

    /** px/second; positive scrolls right/down. Both 0 stops. */
    fun setEdgeScrollVelocity(vx: Float, vy: Float) {
        edgeReqVx = vx
        edgeReqVy = vy
        if (vx == 0f && vy == 0f) {
            // Leaving the band ends the park, and the page gets first refusal
            // again next time. It has earned it: a sideways-scrolling table
            // the wearer has scrolled into view since is a different answer
            // to the same question, and only the page can see it.
            panOwnsX = false
            panOwnsY = false
        }
        setViewPanVelocity(if (panOwnsX) vx else 0f, if (panOwnsY) vy else 0f)
        // An axis the page has given up on is withheld from it. Sending it
        // anyway would double-drive: the container view's scroll offset is a
        // MIRROR of the engine's total (visual + layout) viewport offset, so
        // a host pan and a window.scrollBy on the same axis in the same frame
        // both land on one number and simply add up.
        val jsVx = if (panOwnsX) 0f else vx
        val jsVy = if (panOwnsY) 0f else vy
        if (!edgeScrollStale && jsVx == edgeScrollVx && jsVy == edgeScrollVy) return
        edgeScrollVx = jsVx
        edgeScrollVy = jsVy
        edgeScrollStale = false
        webView.evaluateJavascript(
            "window.__x3EdgeScroll && window.__x3EdgeScroll($jsVx,$jsVy);", null
        )
    }

    /**
     * A new document has none of our JS state, so the velocity we believe we
     * sent is stale. Without this the first scroll after a navigation is
     * deduped away and the page sits still.
     *
     * The stale flag rather than a pair of zeroes, because zeroes are a real
     * velocity: this runs on page START as well as finish, so a wearer parked
     * in a band while a page loads left the host at (0,0) and the old document
     * still animating, and the stop that should have caught it was deduped
     * away for matching.
     */
    private fun resetEdgeScrollState() {
        edgeScrollVx = 0f
        edgeScrollVy = 0f
        edgeReqVx = 0f
        edgeReqVy = 0f
        edgeScrollStale = true
        // A new document is a new answer to "can anything scroll this way",
        // and the pan offsets the loop was clamping against belonged to the
        // old one. Handing both axes back costs nothing — the page is about
        // to be asked again from scratch anyway.
        panOwnsX = false
        panOwnsY = false
        setViewPanVelocity(0f, 0f)
    }

    // ── View-level pan: the travel the page cannot reach ──────────────
    //
    // window.scrollBy only ever moves Blink's LAYOUT viewport. On a page the
    // engine has zoomed out to fit — which useWideViewPort plus the ~53%
    // initial scale of a base window means nearly all of them — most of the
    // travel lives in the VISUAL viewport instead, and the only handle on
    // that from outside the renderer is the container view's own scroll
    // offset. So there is a whole second scroller stacked on the first, and
    // JavaScript can neither see it nor drive it.
    //
    // Measured on kunstderfuge.com/bach/canons.htm in a 170px window: 416 CSS
    // px of horizontal travel in total, of which window.scrollBy reaches 57.
    // The wearer parked in the right band, watched the page twitch and stop,
    // and reported that pages "never scroll right". The same split hides at
    // the bottom of every long page: on that document the last 478 CSS px —
    // more than one whole window height — was unreachable the same way, which
    // is why "vertical works" was only ever true mid-page.
    //
    // This is a FALLBACK and never a replacement. The page is asked first and
    // keeps every axis it can still move, because a container pan cannot
    // scroll an inner overflow-x:auto element at all — the compositor's
    // viewport touches only the inner and outer viewport scroll nodes, so a
    // wide table inside an article would silently stop scrolling and the
    // whole page would slide sideways instead. The hand-off happens only when
    // the page says it has finished with an axis, through the
    // onEdgeScrollIdle callback it was already sending and the host was
    // already throwing away.

    private var panOwnsX = false
    private var panOwnsY = false
    private var panVx = 0f
    private var panVy = 0f
    private var panLastNs = 0L

    /**
     * Sub-pixel carry. The slowest band speed is 25 px/s, which is under half
     * a device px per frame at the page scales these windows load at — round
     * that away every frame and the gentlest end of the ramp is simply a dead
     * axis.
     */
    private var panAccX = 0f
    private var panAccY = 0f
    private var panRunning = false

    /**
     * The page has run an axis out and told us so.
     *
     * Take it over ONLY if there is view-level travel left in the direction
     * the wearer is actually asking for. That condition is what keeps the
     * page in charge: a document that fits its window has no pan range, so
     * declining leaves the axis behaving exactly as it does today — including
     * the page-side deadX/deadY memory, which is keyed on scroll position and
     * is precisely what lets a wearer reach a sideways-scrolling table by
     * first scrolling DOWN to it. Claiming an axis we cannot move would latch
     * that away for the rest of the park and trade one silent failure for
     * another.
     */
    private fun onEdgeAxisExhausted(horiz: Boolean) {
        // Unconditional and first, because this half was already load-bearing
        // before there was anything to hand over to: the dedupe above assumes
        // the page is still running what it was last sent, so once the page
        // stops itself, the wearer nudging back to the same depth in the band
        // produces the same quantised number and the request is dropped
        // before it reaches the bridge.
        edgeScrollStale = true
        val v = if (horiz) edgeReqVx else edgeReqVy
        // A snapshot window is a still bitmap laid over a WebView showing
        // something else entirely; panning what is underneath would move
        // nothing the wearer can see.
        if (v == 0f || isShowingSnapshot) return
        if (!canPanView(horiz, v)) return
        if (horiz) panOwnsX = true else panOwnsY = true
        // Deliver the velocity the host already asked for rather than waiting
        // to be told it again — see [edgeReqVx].
        setEdgeScrollVelocity(edgeReqVx, edgeReqVy)
    }

    /**
     * The pan has reached its clamp, so give the axis back.
     *
     * Handing it straight back instead of keeping it is what stops the
     * take-over from becoming a one-way trap. Panning re-splits the offset
     * between the engine's two viewports, so a document scroller that was
     * maxed out can have room again; and the wearer has moved, so an element
     * that could not be picked before may now be on screen. If the page has
     * nothing either it will report idle once more and [onEdgeAxisExhausted]
     * will decline, which is the honest terminal state: at the true edge of
     * the content, with the page's own dead-axis memory holding the line.
     */
    private fun releasePanAxis(horiz: Boolean) {
        if (horiz) panOwnsX = false else panOwnsY = false
        edgeScrollStale = true
        setEdgeScrollVelocity(edgeReqVx, edgeReqVy)
    }

    /** Is there container travel left in the direction [v] is asking for? */
    private fun canPanView(horiz: Boolean, v: Float): Boolean {
        // canScrollHorizontally() is the obvious question and the wrong one:
        // it reads the container's own offset and it stops one pixel early.
        // The real maximum is exact, is what the loop clamps against anyway,
        // and cannot disagree with itself.
        val max = if (horiz) webView.maxPanX else webView.maxPanY
        val cur = if (horiz) webView.scrollX else webView.scrollY
        return if (v > 0f) cur < max else cur > 0
    }

    private fun setViewPanVelocity(vx: Float, vy: Float) {
        panVx = vx
        panVy = vy
        if (vx == 0f && vy == 0f) {
            panRunning = false
            removeCallbacks(panRunnable)
            panAccX = 0f
            panAccY = 0f
            return
        }
        if (!panRunning) {
            panRunning = true
            panLastNs = System.nanoTime()
            postOnAnimation(panRunnable)
        }
    }

    /**
     * One frame of pan. On the UI thread by construction, which is not
     * optional and is not enforced anywhere else: WebView's thread check
     * covers its own API surface, and scrollTo is inherited from View, so
     * driving this from a background thread would corrupt the container's
     * offset silently rather than throwing.
     */
    private val panRunnable = object : Runnable {
        override fun run() {
            if (!panRunning) return
            val now = System.nanoTime()
            var dt = (now - panLastNs) / 1_000_000_000f
            panLastNs = now
            // The same 64ms clamp the page-side loop uses: a window that was
            // off-screen, or a stall long enough to matter, must not teleport
            // the page when the frames resume.
            if (dt > 0.064f) dt = 0.064f
            if (dt <= 0f) { postOnAnimation(this); return }

            // The band velocity is in CSS px/s because that is what the
            // page-side loop consumes, and the hand-off has to be invisible.
            // At a 0.53 page scale the identical number applied to device px
            // would make the content lurch to nearly twice the speed the
            // instant the page gave up, which reads as a glitch rather than
            // as the same scroll continuing.
            val scale = if (currentScale > 0f) currentScale else 1f
            panAccX += panVx * scale * dt
            panAccY += panVy * scale * dt
            val stepX = panAccX.toInt()
            val stepY = panAccY.toInt()
            panAccX -= stepX
            panAccY -= stepY

            // Read both offsets fresh and write the axis we do NOT own back
            // unchanged. While the host pans sideways the page may still be
            // scrolling itself vertically, and the container offset mirrors
            // that as it happens — writing back a value captured any earlier
            // would drag the page's own scroll backwards a frame at a time.
            val curX = webView.scrollX
            val curY = webView.scrollY
            val toX = if (panVx != 0f) (curX + stepX).coerceIn(0, webView.maxPanX) else curX
            val toY = if (panVy != 0f) (curY + stepY).coerceIn(0, webView.maxPanY) else curY
            // scrollTo rather than scrollBy, and clamped: see PannableWebView
            // for what one unclamped overshoot costs.
            if (toX != curX || toY != curY) webView.scrollTo(toX, toY)

            // Per axis, for the same reason the page-side loop counts per
            // axis: a corner park whose vertical half is still moving would
            // otherwise keep a finished horizontal half alive indefinitely.
            if (panVx != 0f && !canPanView(true, panVx)) releasePanAxis(true)
            if (panVy != 0f && !canPanView(false, panVy)) releasePanAxis(false)
            // releasePanAxis can stop the loop outright, and it is also the
            // only thing that can restart it, so re-posting unconditionally
            // here would leave two callbacks in flight.
            if (panRunning) postOnAnimation(this)
        }
    }

    // ── Resume-from-snapshot ─────────────────────────────────────────
    //
    // A restarted app used to reload every window from the network, so the
    // board came back as a row of empty frames that filled in one by one
    // over several seconds — and any page the wearer had navigated to was
    // replaced by whatever the window was first opened on. Showing the last
    // still instead makes the restart look like nothing happened, and the
    // real page is fetched only when they actually use the window.

    private var snapshotView: android.widget.ImageView? = null

    /** URL to load when this window is first activated, if deferred. */
    private var deferredUrl: String? = null

    val isShowingSnapshot: Boolean get() = snapshotView != null

    /**
     * Put [path] on screen as this window's contents and DON'T load [url]
     * until the wearer touches the window. Falls back to loading straight
     * away if the still cannot be decoded — a missing file must not leave a
     * permanently blank window.
     */
    fun showSnapshotUntilUsed(path: String, url: String) {
        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        if (bmp == null) {
            Log.w(TAG, "snapshot unreadable, loading live: $path")
            loadUrl(url)
            return
        }
        // A still saved before uniform captures were rejected — YouTube and
        // anything else on a video surface produced solid black. Showing it
        // hands the wearer a black box where their window was.
        if (isBlank(bmp)) {
            Log.i(TAG, "snapshot is blank, loading live instead: $url")
            bmp.recycle()
            runCatching { java.io.File(path).delete() }
            loadUrl(url)
            return
        }
        deferredUrl = url
        val iv = android.widget.ImageView(context)
        iv.scaleType = android.widget.ImageView.ScaleType.FIT_START
        iv.setImageBitmap(bmp)
        iv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // Not clickable: the tap must reach the window as usual so the
        // normal activate path runs and swaps this out.
        iv.isClickable = false
        addView(iv)
        snapshotView = iv
    }

    /** Load the real page and take the still down once it has painted. */
    private fun wakeFromSnapshot(onReady: ((Boolean) -> Unit)? = null) {
        val url = deferredUrl
        if (url == null) { onReady?.invoke(true); return }
        deferredUrl = null
        Log.i(TAG, "waking window from snapshot -> $url")
        // Drop the still only when the page has actually rendered, or the
        // window flashes empty between the two.
        val previous = onPageFinishedListener
        var settled = false
        onPageFinishedListener = { finishedUrl ->
            clearSnapshot()
            onPageFinishedListener = previous
            previous?.invoke(finishedUrl)
            if (!settled) { settled = true; onReady?.invoke(true) }
        }
        // A page that never finishes must not leave a tool waiting forever;
        // partial content beats an answer that never comes.
        if (onReady != null) {
            postDelayed({ if (!settled) { settled = true; onReady(false) } }, WAKE_TIMEOUT_MS)
        }
        loadUrl(url)
    }

    /**
     * Guarantee a live page before something reads or acts on it.
     *
     * A window restored from a still holds no document at all — it is
     * about:blank with no text and no title — so every page tool that ran
     * against one got nothing and the assistant answered from thin air.
     * Anything that needs the PAGE, rather than the picture, waits here
     * first.
     */
    fun ensureLoaded(onReady: (Boolean) -> Unit) {
        if (!isShowingSnapshot) { onReady(true); return }
        wakeFromSnapshot(onReady)
    }

    private fun clearSnapshot() {
        val iv = snapshotView ?: return
        snapshotView = null
        runCatching {
            removeView(iv)
            (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
        }
    }

    /**
     * The address the page itself believes it is at.
     *
     * Not the same as WebView.getUrl() on a single-page app. Tapping a video
     * on YouTube, or a day in Google Calendar, changes the document through
     * the history API, so a bookmark must read location.href.
     *
     * A YouTube feed needs one more distinction: the captured still shows a
     * particular video card, but the feed URL is dynamic and can show a
     * completely different card when reopened. For feed/list pages the
     * largest visible video thumbnail is what the wearer is looking at, so
     * its watch/short/live link becomes the bookmark target. Content pages
     * and every non-YouTube page retain their own URL.
     */
    fun resolveBookmarkUrl(callback: (String?) -> Unit) {
        deferredUrl?.let { callback(it); return }
        val fallback = webView.url?.takeIf { it.isNotBlank() && it != "about:blank" }
        runCatching {
            webView.evaluateJavascript(BOOKMARK_TARGET_JS) { raw ->
                // evaluateJavascript returns a JSON string literal. Decoding
                // it matters for URLs whose query contains escaped text.
                val fromPage = runCatching {
                    if (raw == null || raw == "null") null
                    else org.json.JSONTokener(raw).nextValue() as? String
                }.getOrNull()?.takeIf {
                    it.isNotBlank() && it != "about:blank"
                }
                val resolved = fromPage ?: fallback
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "bookmark target webView=$fallback resolved=$resolved")
                }
                callback(resolved)
            }
        }.onFailure { callback(fallback) }
    }

    /** The page's own title, or null before the first load completes. */
    val pageTitle: String? get() = webView.title?.takeIf { it.isNotBlank() }

    /**
     * A still of the page, for a bookmark thumbnail. Main thread only.
     *
     * Two things make this less obvious than drawToBitmap would be.
     *
     * The WebView is pinned to LAYER_TYPE_HARDWARE because the SBS
     * compositor draws the whole viewport twice per frame and Chromium's
     * draw functor only runs once — but a view on a hardware layer draws
     * that cached layer, not its content, into a software Canvas, and the
     * result is blank. So the layer is dropped for the duration of the
     * capture and put back immediately; anything else silently produces an
     * empty thumbnail.
     *
     * And the page renders on BLACK, which is transparent on the waveguide.
     * A dark thumbnail would project as very nearly nothing, so the capture
     * is composited over a light backdrop first — the thumbnail has to read
     * as an object on the HUD, not as a faint smudge.
     */
    fun captureThumbnail(maxWidth: Int = THUMB_MAX_WIDTH): android.graphics.Bitmap? {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        val previousLayer = webView.layerType
        return runCatching {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val full = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888
            )
            android.graphics.Canvas(full).also { c ->
                c.drawColor(THUMB_BACKDROP)
                webView.draw(c)
            }
            // A page whose pixels are all one colour carried no information.
            // Video is the usual cause: YouTube composites its player on a
            // hardware surface the software canvas cannot see, so the capture
            // comes back solid black — and pinning that gives the wearer a
            // black box where their window used to be. Better no still at all;
            // the caller then loads the page instead.
            if (isBlank(full)) {
                Log.i(TAG, "capture discarded: uniform (likely video surface)")
                full.recycle()
                return@runCatching null
            }
            if (w <= maxWidth) return@runCatching full
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                full, maxWidth, (h.toFloat() * maxWidth / w).toInt().coerceAtLeast(1), true
            )
            if (scaled !== full) full.recycle()
            scaled
        }.onFailure {
            Log.w(TAG, "thumbnail capture failed: ${it.message}")
        }.also {
            // Restore before anything can draw a frame, or the second eye
            // goes blank for as long as the layer is wrong.
            runCatching { webView.setLayerType(previousLayer, null) }
        }.getOrNull()
    }

    /**
     * True when a capture holds essentially one colour.
     *
     * Sampled on a coarse grid rather than per-pixel: this runs on the main
     * thread while the app is being closed, and a full scan of a 170x226
     * bitmap to answer a yes/no question is work the wearer would feel.
     */
    private fun isBlank(bmp: android.graphics.Bitmap): Boolean {
        val stepX = (bmp.width / 12).coerceAtLeast(1)
        val stepY = (bmp.height / 12).coerceAtLeast(1)
        var first: Int? = null
        var x = 0
        while (x < bmp.width) {
            var y = 0
            while (y < bmp.height) {
                val c = bmp.getPixel(x, y)
                if (first == null) first = c
                else if (differs(first, c)) return false
                y += stepY
            }
            x += stepX
        }
        return true
    }

    /** Tolerant of JPEG-ish noise; anything a wearer could SEE differs. */
    private fun differs(a: Int, b: Int): Boolean {
        val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return dr > 12 || dg > 12 || db > 12
    }

    /** Host callbacks for page text fields; set by the activity. */
    var onPageInputFocus: ((String) -> Unit)? = null
    var onPageInputBlur: (() -> Unit)? = null

    /**
     * Register the page-input bridge. Called by the host right where the
     * agent's bridge is attached — after construction, before the first
     * load, because addJavascriptInterface only takes effect on the next
     * navigation.
     */
    fun installInputBridge() {
        webView.addJavascriptInterface(InputBridge(), PageInputBridge.NAME)
    }

    private inner class InputBridge {
        @android.webkit.JavascriptInterface
        fun onInputFocus(value: String) {
            post { onPageInputFocus?.invoke(value) }
        }

        @android.webkit.JavascriptInterface
        fun onInputBlur() {
            post { onPageInputBlur?.invoke() }
        }

        /**
         * The edge-scroll loop gave up on an axis — there is no box in the
         * document that can move that way, or the one it chose refused to.
         *
         * WHICH axis used to be thrown away here, and throwing it away was
         * the reason "the page never scrolls right" could not be answered
         * from the host side: the report arrived, said nothing useful, and
         * the only travel left was travel the page cannot reach. It is now
         * the trigger for the view-level pan.
         */
        @android.webkit.JavascriptInterface
        fun onEdgeScrollIdle(horiz: Boolean) {
            // Hopped to the UI thread before anything is touched: this
            // arrives on the WebView's JavaScript thread, and everything it
            // reaches — the cached velocities, the pan loop, scrollTo on the
            // container — is main-thread state with no lock of its own.
            post { onEdgeAxisExhausted(horiz) }
        }

        /**
         * The page's fixed chrome CHANGED — a control bar appeared or went
         * away. The load-time read alone left the inset frozen at whatever
         * the page first declared: the podcast player claims a bottom strip
         * for its playbar, but the bar only exists once something plays, so
         * before that the band was dead over plain content — edge scroll
         * refusing to start in a strip where nothing but the list was
         * showing.
         */
        @android.webkit.JavascriptInterface
        fun onEdgeInsets(topCss: Int, bottomCss: Int) {
            edgeInsetTopCss = topCss.coerceAtLeast(0)
            edgeInsetBottomCss = bottomCss.coerceAtLeast(0)
        }
    }

    /**
     * Edge-band insets the CURRENT PAGE declared. A page sets
     * `window.__x3EdgeInsets = {top: cssPx, bottom: cssPx}` to say a strip of
     * its window is fixed chrome — a control bar, a pinned search field —
     * where a resting cursor means "I am about to click", never "scroll";
     * a page whose chrome comes and goes pushes updates through
     * X3Input.onEdgeInsets. Zero for every page that does not declare,
     * which is all of the web; re-read on each load so the values die with
     * the page that set them.
     *
     * Stored in CSS px and converted on demand, because a window RESIZE
     * changes the page scale — a device-px snapshot taken at load would be
     * wrong for the rest of the window's life after one trip up the ladder.
     */
    @Volatile private var edgeInsetTopCss: Int = 0
    @Volatile private var edgeInsetBottomCss: Int = 0

    val edgeInsetTopPx: Int
        get() = (edgeInsetTopCss * (if (currentScale > 0f) currentScale else 1f)).toInt()
    val edgeInsetBottomPx: Int
        get() = (edgeInsetBottomCss * (if (currentScale > 0f) currentScale else 1f)).toInt()

    private fun readEdgeInsets() {
        runCatching {
            webView.evaluateJavascript(
                "(function(){var i=window.__x3EdgeInsets||{};" +
                    "return (i.top||0)+','+(i.bottom||0);})()"
            ) { raw ->
                val parts = raw.trim('"').split(',')
                edgeInsetTopCss = (parts.getOrNull(0)?.toFloatOrNull() ?: 0f).toInt()
                edgeInsetBottomCss = (parts.getOrNull(1)?.toFloatOrNull() ?: 0f).toInt()
            }
        }
    }

    private fun injectImageFit() {
        runCatching { webView.evaluateJavascript(IMAGE_FIT_JS, null) }
        runCatching { webView.evaluateJavascript(VIEWPORT_FIT_JS, null) }
        runCatching { webView.evaluateJavascript(CANVAS_FIX_JS, null) }
        readEdgeInsets()
    }

    private fun injectInputHooks() {
        runCatching { webView.evaluateJavascript(PageInputBridge.JS, null) }
        runCatching { webView.evaluateJavascript(EDGE_SCROLL_JS, null) }
        resetEdgeScrollState()
    }

    /** Type into whatever field the page currently has focused. */
    fun insertText(text: String) =
        webView.evaluateJavascript("window.__x3Insert && window.__x3Insert(${org.json.JSONObject.quote(text)})", null)

    fun backspace() = webView.evaluateJavascript("window.__x3Backspace && window.__x3Backspace()", null)
    fun clearField() = webView.evaluateJavascript("window.__x3Clear && window.__x3Clear()", null)
    fun moveCaret(d: Int) = webView.evaluateJavascript("window.__x3MoveCaret && window.__x3MoveCaret($d)", null)
    fun submitField() = webView.evaluateJavascript("window.__x3Enter && window.__x3Enter()", null)
    fun defocusField() = webView.evaluateJavascript("window.__x3Defocus && window.__x3Defocus()", null)

    /** Probe: run arbitrary JS in the page and log the result. */
    /**
     * Android-side scroll metrics — the view's own pan range, not the DOM's.
     *
     * Reports the true maxima, because the earlier version of this probe
     * lied and its answer was believed: it panned with an unclamped
     * scrollBy and then read the offset back, so writing 400 into a range of
     * 220 reported "400" and enshrined a phantom ~400px pan range as ground
     * truth. Anything measured through here must be read against maxPan, and
     * the pan itself is clamped so the two cannot drift apart.
     */
    fun debugScrollInfo(panDx: Int): String {
        val beforeX = webView.scrollX
        val beforeY = webView.scrollY
        if (panDx != 0) {
            webView.scrollTo((beforeX + panDx).coerceIn(0, webView.maxPanX), beforeY)
        }
        return "viewScrollX $beforeX -> ${webView.scrollX}/${webView.maxPanX} " +
            "viewScrollY $beforeY -> ${webView.scrollY}/${webView.maxPanY} " +
            "canRight=${webView.canScrollHorizontally(1)} " +
            "canLeft=${webView.canScrollHorizontally(-1)} " +
            "canDown=${webView.canScrollVertically(1)} " +
            "canUp=${webView.canScrollVertically(-1)} " +
            "contentH=${webView.contentHeight} " +
            "scale=$currentScale width=$width"
    }

    fun debugEval(js: String) {
        webView.evaluateJavascript(js) { Log.i(TAG, "eval -> $it") }
    }

    // ── Page-agent host API ───────────────────────────────────────────
    //
    // The agent lives inside the document, so it needs to run script in
    // this window specifically and to be told when the document under it
    // is replaced. With three windows there is no single "the WebView" to
    // call back into, which is why these are instance methods rather than
    // the activity-level singletons SmartView could get away with.

    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null) {
        webView.evaluateJavascript(script) { r -> callback?.invoke(r) }
    }

    @SuppressLint("JavascriptInterface")
    fun addAgentBridge(bridge: Any, name: String) {
        webView.addJavascriptInterface(bridge, name)
    }

    /** Navigation started — the agent instance in the old document is gone. */
    var onPageStartedListener: ((String?) -> Unit)? = null

    /** Document ready — the moment to re-inject and resume. */
    var onPageFinishedListener: ((String?) -> Unit)? = null

    private val pageFinishedOnce = mutableListOf<(String?) -> Unit>()

    /**
     * Run [block] once, after the next page finishes loading.
     *
     * For "navigate somewhere, then act on what arrives" — the agent has to
     * be started on the results page, not on the page the wearer was
     * standing on when they spoke.
     */
    fun runAfterNextPageFinish(block: (String?) -> Unit) {
        pageFinishedOnce.add(block)
    }

    /**
     * The page's readable text, for the page agent. innerText rather than
     * textContent on purpose: it respects display:none and collapses the
     * whitespace, so navigation furniture and hidden menus do not drown the
     * article in a window this small.
     */
    fun extractVisibleText(callback: (String?) -> Unit) {
        webView.evaluateJavascript(
            "(function(){try{" +
                "var m=document.querySelector('main,article,[role=main]');" +
                "return ((m||document.body).innerText||'').slice(0,20000);" +
                "}catch(e){return ''}})()"
        ) { raw ->
            // evaluateJavascript hands back a JSON string literal.
            val decoded = runCatching {
                if (raw == null || raw == "null") null
                else org.json.JSONTokener(raw).nextValue() as? String
            }.getOrNull()
            callback(decoded)
        }
    }

    fun debugZoomBy(factor: Float) {
        Log.i(TAG, "debugZoomBy($factor) from scale=$currentScale " +
            "supportZoom=${webView.settings.supportZoom()} " +
            "builtIn=${webView.settings.builtInZoomControls}")
        webView.zoomBy(factor)
        postDelayed({
            webView.evaluateJavascript(
                "JSON.stringify({iw:innerWidth,sw:document.documentElement.scrollWidth," +
                    "vs:(visualViewport?visualViewport.scale:-1)," +
                    "vw:(visualViewport?Math.round(visualViewport.width):-1)})"
            ) { Log.i(TAG, "afterZoom $it (view=${width}x$height scale=$currentScale)") }
        }, 700)
    }

    /**
     * Ad/tracker filter hook, called on the network thread for every
     * subresource. Return a stub response to block, null to allow.
     *
     * A callback rather than a direct call because there is no AdBlock
     * class in com.x3hub.app yet — when one lands, the activity wires
     * `requestInterceptor = { AdBlock.intercept(it) }`. Service-worker
     * requests never reach a WebViewClient, so the activity must also
     * install a process-global ServiceWorkerClient; that is deliberately
     * not done here because it is one-per-process and this view is
     * one-per-window.
     */
    // Volatile because it is read on the network thread and written on the
    // main one; without it a request can be filtered against a stale null.
    @Volatile
    var requestInterceptor: ((WebResourceRequest?) -> WebResourceResponse?)? = null

    init {
        // The pin board positions us; the size is ours. Params are set up
        // front so a host can simply addView() and get the 170×226 default.
        layoutParams = FrameLayout.LayoutParams(windowWidth, windowHeight)
        clipChildren = true
        // Not clickable on purpose. MainActivity's overlay hit-test treats a
        // clickable view as interactive and dispatches a synthetic DOWN+UP
        // into it — which would hand the page every tap that lands on an
        // INERT window, the exact thing the state machine exists to prevent.
        isClickable = false
        isFocusable = false

        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Every window filters. An ad slot is merely annoying on a phone; in
        // a 170px window it can BE the page, and each banner is one more
        // clickable thing the page agent has to reason about. App pages are
        // answered first — x3hub.local resolves nowhere, so a request that
        // slipped past the interceptor would only produce a DNS error.
        requestInterceptor = { req ->
            LocalPages.serve(context, req) ?: AdBlock.intercept(req)
        }
        installServiceWorkerFilter()
        // Before configureWebView, and therefore before any load:
        // addJavascriptInterface only takes effect on the next navigation.
        configureWebView(webView)
        addView(webView)

        // The border is a FOREGROUND, not padding: it draws over the
        // WebView, so the WebView stays exactly coincident with this view
        // and [forwardTouch] needs no coordinate offset at all.
        foregroundGravity = Gravity.FILL
        foreground = borderInert

        applyPageScale(windowWidth)
    }

    // ------------------------------------------------------------------
    // WebView configuration — the parts of SmartView that make real sites
    // work, and nothing else.
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView) {
        // REQUIRED, not an optimisation: BinocularSbsLayout draws the whole
        // logical viewport twice per frame, and WebView's draw functor only
        // runs once per frame — the second eye would come out blank. With a
        // hardware layer the page is rasterised once and the layer is
        // composited twice, which is what makes SBS work at all.
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // Black is transparent on the waveguide, so an unloaded window is
        // just its border floating in the world.
        wv.setBackgroundColor(Color.BLACK)
        // A scrollbar inside a 170px-wide window costs several percent of
        // the readable width to say something the wearer already knows.
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        // See the note on isClickable above — the overlay hit-test walks
        // descendants, so the WebView must not advertise itself either.
        wv.isClickable = false

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            // Pages that autoplay are the ones worth having in a window on
            // your face; a gesture requirement we have no gesture for would
            // simply make them dead.
            mediaPlaybackRequiresUserGesture = false
            // A window can sit off to the side of where the wearer is
            // looking for minutes at a time. Keeping it rasterised means it
            // is already correct when they look back.
            setOffscreenPreRaster(true)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // A paragraph at the base window size lands at 8.5 device px
            // (see applyPageScale) — noticeably smaller than the HUD's own
            // 10.4px body text, which is already near the legibility floor
            // on the waveguide. 125% brings it to ~10.6px and matches. The
            // cost is that fixed-height page furniture can clip; that is the
            // right trade when the alternative is text you cannot read.
            textZoom = 125
            // The device's System WebView is Chrome 95 (2021). Cloudflare
            // distrusts an engine that old and loops the "verify human"
            // challenge forever, and there is no way to satisfy it on
            // glasses. A current mobile-Chrome identity gets through, and
            // being a MOBILE identity is also what makes sites serve the
            // narrow layout this window is sized for.
            userAgentString = MODERN_UA
        }

        // Darken in the engine. White is maximum projector output on a
        // waveguide: a white page is a lamp in the wearer's eye, and black
        // is simply transparent. NOTE this call is a no-op from Android 13
        // upward at targetSdk 33+, where the app theme decides instead —
        // the X3 Pro runs Android 12, so it does apply on the target device.
        // TRUE, not `darkMode`. This runs from init, and the darkMode
        // property is declared further down the class — Kotlin runs
        // initializers in declaration order, so its backing field still
        // reads FALSE here. Reading it turned force-dark off at
        // construction, the initializer then quietly set the field to true,
        // and the setter's own no-change guard swallowed every later
        // attempt to switch dark on: each window rendered light forever,
        // whatever anyone asked for. Dark is the default, so it is applied
        // literally; the setter owns every change after construction.
        applyForceDark(wv, true)

        runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true) }

        // The system IME renders into the raw 1280x480 framebuffer, so it
        // spans BOTH eyes at the wrong scale and cannot be dismissed by
        // looking at it. Text entry goes through the glasses' own keyboard
        // instead. Reflective because setShowSoftInputOnFocus is public on
        // TextView but hidden on WebView — and not sufficient on its own,
        // which is why the activity also suppresses actively.
        runCatching {
            WebView::class.java
                .getMethod("setShowSoftInputOnFocus", java.lang.Boolean.TYPE)
                .invoke(wv, false)
        }
        wv.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) runCatching {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }

        // Process-global and idempotent; called here so a window works
        // whether or not the activity remembered to.
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = requestInterceptor?.invoke(request)

            /**
             * Keep every navigation inside the window. A page that fires an
             * intent:// or market:// URL would otherwise throw the wearer
             * out of x3hub into the Play Store, mid-sentence.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase() ?: return false
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                injectPolyfills()
                injectSiteChromeFilters()
                injectInputHooks()
                injectImageFit()
                injectMediaAutoplay()
                onPageStartedListener?.invoke(url)
            }

            /**
             * The secure attempt failed — go back to the address as given.
             *
             * MAIN FRAME ONLY. A missing tracker or a broken image on an
             * otherwise fine HTTPS page also arrives here, and reloading the
             * whole document as cleartext because one asset 404'd would
             * downgrade a working secure page for no reason.
             */
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                val fallback = cleartextFallbackUrl ?: return
                val failed = request.url?.toString().orEmpty()
                // Only when it is OUR upgrade that failed, not some later
                // navigation the wearer made from the page.
                val upgraded = "https://" + fallback.substring("http://".length)
                if (!failed.equals(upgraded, ignoreCase = true)) return
                cleartextFallbackUrl = null
                Log.i(TAG, "https failed (${error?.description}) — falling back to $fallback")
                view?.loadUrl(fallback)
            }

            /**
             * A certificate that does not cover the bare domain is the
             * signature failure of an older site: radio4all.net's cert names
             * only www.radio4all.net, so the handshake dies and the wearer
             * gets a silently BLACK window — an SSL failure paints nothing
             * and says nothing. When the mismatch looks exactly like that,
             * retry once on the www host. The bad certificate is never
             * accepted; the navigation moves to the host the cert is
             * actually for. Everything else stays cancelled.
             */
            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                if (error?.primaryError != android.net.http.SslError.SSL_IDMISMATCH) return
                val u = runCatching { android.net.Uri.parse(error.url) }.getOrNull() ?: return
                val host = u.host ?: return
                if (host.startsWith("www.") || host.count { it == '.' } != 1) return
                val www = u.buildUpon().authority("www.$host").build().toString()
                Log.i(TAG, "cert for $host mismatched — retrying as $www")
                view?.post { view.loadUrl(www) }
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                currentScale = newScale
                // A scale change rewrites the pan range AND how the engine
                // splits the offset between its layout and visual viewports,
                // so a document scroller that had nothing left a moment ago
                // can have room again. Hand the axes back and let the page
                // re-answer, rather than keep panning against a maximum that
                // was measured at the old scale.
                if (panOwnsX || panOwnsY) {
                    panOwnsX = false
                    panOwnsY = false
                    edgeScrollStale = true
                    setEdgeScrollVelocity(edgeReqVx, edgeReqVy)
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "scale $oldScale -> $newScale")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Twice on purpose: at start so a script running during
                // parse finds the shims, and at finish for anything the
                // document replaced in between.
                injectPolyfills()
                injectSiteChromeFilters()
                injectInputHooks()
                injectImageFit()
                injectMediaAutoplay()
                restoreScrollAfterResize()
                if (BuildConfig.DEBUG) {
                    view?.evaluateJavascript(
                        "JSON.stringify({iw:innerWidth,sw:document.documentElement.scrollWidth," +
                            "dpr:devicePixelRatio,vs:(visualViewport?visualViewport.scale:-1)})"
                    ) { Log.i(TAG, "viewport $it (view=${width}x$height)") }
                }
                runCatching { CookieManager.getInstance().flush() }
                onPageFinishedListener?.invoke(url)
                // Drained AFTER the listener, and kept separate from it on
                // purpose: onPageFinishedListener is a single slot that the
                // page agent claims for every window, and wakeFromSnapshot
                // swaps it out and back. Anything assigning it to wait for one
                // navigation would silently kill the agent's own resume
                // machinery, so one-shot waiters get their own queue.
                if (pageFinishedOnce.isNotEmpty()) {
                    val due = pageFinishedOnce.toList()
                    pageFinishedOnce.clear()
                    due.forEach { runCatching { it(url) } }
                }
            }
        }
    }

    /**
     * Shims for the gap between Chrome 95 and what current bundles assume,
     * plus the two JS-visible signals that have to agree with the spoofed
     * UA or the spoof is worse than useless (a modern UA on an engine that
     * still reports navigator.webdriver-era brands reads as automation).
     *
     * Not carried over from SmartView: its Trusted Types default policy.
     * That existed so page-agent could assign innerHTML on sites that
     * forbid it; this file injects no DOM, so relaxing a defence the page
     * asked for would buy nothing.
     */
    private fun injectPolyfills() {
        runCatching { webView.evaluateJavascript(POLYFILL_JS, null) }
    }

    /** Remove site-owned install promos that obscure most of a glasses window. */
    private fun injectSiteChromeFilters() {
        runCatching { webView.evaluateJavascript(SITE_CHROME_FILTER_JS, null) }
    }

    /**
     * Whether this window may unmute media on its own.
     *
     * False for a window restored from a previous session until the wearer
     * touches it — see HudPinStore.wasRestoredFromDisk. Turning it on makes
     * the page catch up immediately rather than waiting for a navigation,
     * because a wearer who just clicked a silent video expects sound now.
     */
    var autoplayWithSound: Boolean = true
        set(value) {
            val was = field
            field = value
            if (value && !was) injectMediaAutoplay()
        }

    /**
     * Darken in the engine, or leave the page's own colours alone.
     *
     * White is maximum projector output on a waveguide — a white page is a
     * lamp in the wearer's eye and black is simply transparent — so this is
     * ON by default. But it is a RENDERER transform applied after layout,
     * not a stylesheet, so when it mishandles a site there is nothing in the
     * page to explain the result and nothing per-site we can do about it.
     * Hence the switch.
     */
    private fun applyForceDark(wv: WebView, on: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            wv.settings.forceDark =
                if (on) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { wv.settings.isAlgorithmicDarkeningAllowed = on }
        }
    }

    /**
     * Whether THIS window is darkened, set per window rather than globally.
     *
     * Dark is right for nearly everything on a waveguide, but it is a paint
     * transform the page knows nothing about, and on a site it mishandles
     * the text can come out the colour of its own background — Slashdot
     * measured a perfect 21:1 in the DOM while being unreadable on the
     * glass. Which pages need the exception is a judgement about the page
     * in front of you, so it belongs to the window, and the wearer sets it
     * by saying so when they open it.
     */
    var darkMode: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyForceDark(webView, value)
            // The transform runs at paint time, so an already-rendered page
            // keeps its old colours until it is laid out again.
            runCatching { webView.reload() }
        }

    /**
     * Make a page that is MEANT to be playing actually play — see
     * [MEDIA_AUTOPLAY_JS] for YouTube and [RADIO_GARDEN_START_JS] for Radio
     * Garden. Each script scopes itself by host, so both are safe to run on
     * any page; neither does anything away from its own site.
     */
    private fun injectMediaAutoplay() {
        if (!autoplayWithSound || mediaHeldForMic) return
        runCatching { webView.evaluateJavascript(MEDIA_AUTOPLAY_JS, null) }
        runCatching { webView.evaluateJavascript(RADIO_GARDEN_START_JS, null) }
    }

    /** True while the host is holding this window quiet for the microphone. */
    private var mediaHeldForMic = false

    /**
     * Hold page media while the microphone is open — see [MEDIA_SUSPEND_JS].
     * Safe to call on a window that is playing nothing.
     *
     * The flag matters as much as the pause. A window CREATED during a live
     * session — which is the ordinary way a video window is born, the wearer
     * says "open that video" while the assistant is listening — has no media
     * to pause yet, so pausing alone does nothing and the page then unmutes
     * itself a second later straight into the open microphone. Holding the
     * flag means such a window never unmutes in the first place, and gets
     * its voice when the mic closes.
     */
    fun setMediaSuspended(suspended: Boolean) {
        mediaHeldForMic = suspended
        runCatching {
            webView.evaluateJavascript(
                if (suspended) MEDIA_SUSPEND_JS else MEDIA_RESUME_JS,
                null
            )
        }
        // Catch up on release: the page skipped its unmute while held.
        if (!suspended) injectMediaAutoplay()
    }

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    /** One single click on the window. Idempotent. */
    fun activate() {
        // Before the early return below: re-clicking the window you are
        // already in is still you saying "this one", and it is exactly how a
        // wearer re-picks a window after talking to Gemini.
        lastFocusMs = SystemClock.uptimeMillis()
        // Even if already active — a wearer clicking a still expects it to
        // come alive, and an early return would leave a dead picture.
        wakeFromSnapshot()
        // Choosing a window is the wearer asking for it, so a restored one
        // is allowed its sound from here on. Set before the early return:
        // the click that grants it is often on a window that is ALREADY
        // active, and returning first would leave it mute forever.
        autoplayWithSound = true
        if (isActive && !isModifying) return
        isModifying = false
        isActive = true
        foreground = borderActive
    }

    /** Back to INERT: the page stops receiving anything. Idempotent. */
    fun deactivate() {
        if (!isActive && !isModifying) return
        isActive = false
        isModifying = false
        foreground = borderInert
        // An edge scroll outlives the cursor that started it otherwise: the
        // page animates itself, so nothing stops when the window loses the
        // input and the wearer watches a window they no longer own scroll on.
        setEdgeScrollVelocity(0f, 0f)
    }

    /**
     * Pin-board modify mode (double-tap). Turning it ON drops ACTIVE:
     * forwarding swipes to the page while the wearer is trying to resize
     * the window would scroll the article instead of growing the frame.
     * Turning it OFF lands in INERT, not back in ACTIVE — after moving a
     * window you are looking at it, not reading it, and one click is a
     * cheap way back in.
     */
    fun setModify(on: Boolean) {
        if (isModifying == on) return
        isModifying = on
        if (on) {
            // Moving or resizing a window is the loudest possible statement
            // that it is the one you care about, even though it is about to
            // stop being the one that takes your swipes.
            lastFocusMs = SystemClock.uptimeMillis()
            isActive = false
            setEdgeScrollVelocity(0f, 0f)
        }
        foreground = if (on) borderModify else borderInert
    }

    /**
     * Deactivate AND tell the host, so it can bring the cursor back. The
     * triple-tap-inside-an-active-window path. Always fires the callback:
     * a silent no-op here would leave the wearer with no cursor.
     */
    fun requestExit() {
        deactivate()
        onExitRequested?.invoke()
    }

    /**
     * Forward a touch to the page. [ev] is in THIS view's local
     * coordinates; the WebView is coincident with this view (the border is
     * a foreground, not padding) so no offset is applied, and FrameLayout
     * dispatches an identity-matrix child without copying the event.
     *
     * Returns false when the window is not ACTIVE, so the caller can fall
     * through to its own handling.
     */
    fun forwardTouch(ev: MotionEvent): Boolean {
        if (!isActive) return false
        // super, not this: dispatchTouchEvent below is the gate for touches
        // arriving the ordinary way, and this call has already passed it.
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The gate for touches that reach this view by any route other than
     * [forwardTouch] — a stray synthetic dispatch from the overlay
     * hit-test, say. An INERT window consumes them rather than passing
     * them to the page.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isActive) return true
        return super.dispatchTouchEvent(ev)
    }

    // scrollPageBy(dx, dy) lived here and had no callers in the app. It was
    // a bare webView.scrollBy, i.e. the one unclamped view-level scroll this
    // class exposed, and wiring it up would have reproduced the container /
    // renderer desync described on PannableWebView. Anything that needs to
    // pan the view goes through the clamped loop in the edge-scroll section.

    /** @return true when there was history to go back to. */
    fun goBack(): Boolean {
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    // ------------------------------------------------------------------
    // Sizing
    // ------------------------------------------------------------------

    /**
     * Step up (+1) or down (-1) the size ladder. A ladder rather than free
     * scaling for two reasons: the 3:4 portrait ratio can never drift out
     * from under the layout, and every size the wearer can reach is one the
     * page scale was checked at.
     */
    fun resizeStep(direction: Int) {
        if (direction == 0) return
        val requested = (sizeStep + if (direction > 0) 1 else -1)
            .coerceIn(0, SIZE_LADDER.lastIndex)
        val next = largestStepThatFits(requested)
        if (next == sizeStep) return
        commitSize(next)
    }

    /**
     * Jump straight to a ladder index (restoring a persisted window).
     * Not named setSizeStep: that is the JVM signature of [sizeStep]'s
     * private setter, and the two would clash.
     */
    fun applySizeStep(step: Int) {
        val next = largestStepThatFits(step.coerceIn(0, SIZE_LADDER.lastIndex))
        if (next == sizeStep) return
        commitSize(next)
    }

    /**
     * The top of the ladder already fits the under-HUD zone of a 640×480
     * viewport, so this only bites when a host gives the window a smaller
     * container than the whole zone.
     */
    private fun largestStepThatFits(requested: Int): Int {
        // Deliberately NOT `parent`: the host builds a container sized to the
        // window's *current* size, so measuring against it would mean the
        // window only ever "fits" the size it already is — every grow step
        // would silently no-op. [maxSizeProvider] is the board's free zone.
        val bounds = maxSizeProvider?.invoke()
        val maxW = bounds?.first ?: (parent as? View)?.width ?: return requested
        val maxH = bounds?.second ?: (parent as? View)?.height ?: return requested
        if (maxW <= 0 || maxH <= 0) return requested
        var i = requested
        while (i > 0 && (SIZE_LADDER[i][0] > maxW || SIZE_LADDER[i][1] > maxH)) i--
        return i
    }

    private fun commitSize(step: Int) {
        sizeStep = step
        val w = windowWidth
        val h = windowHeight
        layoutParams?.let { lp ->
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                layoutParams = lp
            }
        }
        applyPageScale(w)
        reloadAtNewScale()
        Log.i(TAG, "resize → step $step (${w}x$h), page scale ${pageScalePercent(w)}%")
        onWindowSizeChanged?.invoke(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The host may be smaller than the default window; find out now
        // rather than the first time the wearer tries to resize.
        val fitted = largestStepThatFits(sizeStep)
        if (fitted != sizeStep) commitSize(fitted) else applyPageScale(windowWidth)
    }

    /**
     * The single decision that makes these windows usable or not.
     *
     * The viewport is 640×480 logical px with density forced to MEDIUM, so
     * 1 CSS px == 1 device px == 1 dp. Left alone, a 170px-wide WebView
     * reports device-width = 170 to the page — narrower than any site is
     * designed or tested for, and responsive layouts collapse into a
     * column of overlapping boxes below about 300.
     *
     * So the layout width is pinned instead: an explicit initial scale of
     * windowWidth/320 tells the engine the screen is 320 CSS px across, the
     * narrow-phone width every responsive site is built for, and the page
     * is then rasterised down to fit. Crucially this is an ENGINE scale,
     * not a View scale — text is rendered at the final size and stays
     * crisp, where scaleX on the view would resample a composited bitmap
     * and smear it.
     *
     * The ladder is the other half of the answer: 40% / 53% / 74% / 100%.
     * At the largest step the page is at 1:1 with a 320px phone, which is
     * what the wearer resizes up to in order to actually read something.
     *
     * A non-zero initial scale takes precedence over loadWithOverviewMode,
     * deliberately: a 980px desktop page fit into 170px lands at 17% and is
     * unreadable at any window size, so it is better to render it at the
     * mobile scale and let the wearer pan. The mobile UA means few sites
     * take that path.
     *
     * On resize this is re-applied and the engine recomputes page scale the
     * way it does for a rotation. If a resized window is ever seen keeping
     * its old scale until the next navigation, the certain fix is to reload
     * — not done here because it would throw away the page the wearer is
     * reading to fix its zoom.
     */
    private fun applyPageScale(widthPx: Int) {
        webView.setInitialScale(pageScalePercent(widthPx))
    }

    /**
     * Re-render the current page at a newly applied scale.
     *
     * The engine adopts an initial scale at navigation time and not before,
     * so a resized window keeps the scale it loaded at while its viewport
     * has changed underneath it — which is what makes lines run off the
     * right edge instead of re-wrapping. A reload is the mechanism that
     * actually exists for this.
     *
     * The cost of a reload is losing the wearer's place, so the scroll
     * offset is carried across. It is read in CSS px, which is
     * scale-independent by definition, so the same offset means the same
     * paragraph at 40% and at 100%.
     */
    private fun reloadAtNewScale() {
        val wv = webView
        if (wv.url.isNullOrBlank()) return
        val url = wv.url ?: return
        // Never throw away a page that is PLAYING something. The reload
        // below exists to re-wrap text at the new scale — and a video has no
        // text to re-wrap: the player fills the window and follows the
        // viewport on its own. Reloading a video window cost the wearer
        // their place in it, restarted playback from zero, re-buffered, and
        // on an ad-bearing page played the pre-roll again — all to fix a
        // line-wrap problem that page does not have. Resizing a video is
        // exactly when someone is settling in to watch it.
        wv.evaluateJavascript(MEDIA_PLAYING_JS) { playing ->
            if (playing?.trim() == "true") {
                Log.i(TAG, "resize: media playing — keeping the page, no reload")
                return@evaluateJavascript
            }
            wv.evaluateJavascript("(window.scrollY|0)") { value ->
                pendingScrollCssY = value?.trim()?.toFloatOrNull()?.toInt()
                // loadUrl, NOT reload: a reload restores the page's previous
                // zoom state and the new initial scale is ignored, which is
                // exactly the bug this exists to fix. A fresh navigation is
                // what makes the engine adopt it.
                wv.loadUrl(url)
            }
        }
    }

    private fun restoreScrollAfterResize() {
        val y = pendingScrollCssY ?: return
        pendingScrollCssY = null
        if (y <= 0) return
        webView.evaluateJavascript("window.scrollTo(0,$y)", null)
    }

    private fun pageScalePercent(widthPx: Int): Int =
        (widthPx * 100 / MOBILE_LAYOUT_CSS_WIDTH).coerceAtLeast(1)

    // ------------------------------------------------------------------
    // Content + lifecycle
    // ------------------------------------------------------------------

    /**
     * Load [url]. A bare host ("wikipedia.org") gets https:// prefixed,
     * because that is what a voice tool hands over. Anything else is
     * passed through untouched — turning a failed load into a search is
     * the caller's policy, not this window's.
     */
    /**
     * The http:// address this window is currently trying as https://, so a
     * failure can fall back to the address the wearer actually gave.
     */
    private var cleartextFallbackUrl: String? = null

    fun loadUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val full = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        // Try HTTPS even when told http://. The assistant writes URLs from
        // memory and often writes the old one — asked for kvhs.com it opened
        // http://www.kvhs.com/, which Android refuses outright, so the wearer
        // got "cleartext not permitted" for a site that serves HTTPS
        // perfectly well. Upgrading first means the secure version is used
        // whenever it exists, and the fallback below covers the sites where
        // it genuinely does not.
        if (full.startsWith("http://", ignoreCase = true)) {
            cleartextFallbackUrl = full
            val secure = "https://" + full.substring("http://".length)
            Log.i(TAG, "upgrading to https: $secure")
            webView.loadUrl(secure)
            return
        }
        cleartextFallbackUrl = null
        webView.loadUrl(full)
    }

    /** Forward the host activity's onPause. */
    fun onHostPause() {
        // onPause only, never pauseTimers: that one is process-global and
        // would freeze every other browser window on the board.
        webView.onPause()
    }

    /** Forward the host activity's onResume. */
    fun onHostResume() {
        webView.onResume()
    }

    /**
     * Tear the window down. A WebView left behind keeps its renderer, its
     * timers and any audio it was playing.
     */
    fun destroy() {
        deactivate()
        // deactivate() early-returns on a window that was already inert, so
        // the pan loop needs its own stop: a frame callback that outlived
        // webView.destroy() would be calling scrollTo on a dead renderer.
        setViewPanVelocity(0f, 0f)
        onExitRequested = null
        onWindowSizeChanged = null
        requestInterceptor = null
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            removeView(webView)
            webView.destroy()
        }
    }

    /** Hit-test in absolute screen coordinates — the space MainActivity's
     *  cursor works in. */
    @JvmOverloads
    fun containsScreenPoint(
        screenX: Float,
        screenY: Float,
        slackPx: Float = 0f
    ): Boolean {
        if (visibility != VISIBLE || width == 0) return false
        getLocationOnScreen(locationScratch)
        val left = locationScratch[0] - slackPx
        val top = locationScratch[1] - slackPx
        return screenX >= left && screenX < left + width + 2 * slackPx &&
            screenY >= top && screenY < top + height + 2 * slackPx
    }

    companion object {
        private const val TAG = "X3HubBrowserWindow"

        /** How long a tool waits for a woken page before using what it has. */
        private const val WAKE_TIMEOUT_MS = 8_000L

        /**
         * Return the URL represented by a bookmark still.
         *
         * Most stills represent the document and return location.href. A
         * YouTube home/search/feed still instead represents the dominant
         * video card visible inside that document. Saving only the feed URL
         * makes the thumbnail lie as soon as YouTube refreshes its feed.
         *
         * The selection is deliberately narrow:
         *   - only YouTube hosts;
         *   - only anchors to watch/shorts/live content;
         *   - only image pixels actually intersecting the viewport.
         *
         * Therefore an article containing a linked image, or even a YouTube
         * watch page containing recommendations, keeps its own page URL.
         */
        private val BOOKMARK_TARGET_JS = """
            (function(){
              try {
                var page = location.href;
                var host = (location.hostname || '').toLowerCase();
                var youtube = host === 'youtube.com' ||
                              host.substring(Math.max(0, host.length - 12)) === '.youtube.com';
                if (!youtube) return page;

                var pagePath = (location.pathname || '').toLowerCase();
                if (/^\/(watch|shorts|live)(\/|${'$'})/.test(pagePath)) return page;

                var viewportW = Math.max(
                  window.innerWidth || 0,
                  document.documentElement ? document.documentElement.clientWidth : 0
                );
                var viewportH = Math.max(
                  window.innerHeight || 0,
                  document.documentElement ? document.documentElement.clientHeight : 0
                );
                var best = null, bestArea = 0;
                var images = document.querySelectorAll('a[href] img');
                for (var i = 0; i < images.length; i++) {
                  var img = images[i];
                  var anchor = img.closest ? img.closest('a[href]') : null;
                  if (!anchor) continue;

                  var target;
                  try { target = new URL(anchor.href, page); } catch (_) { continue; }
                  var targetHost = (target.hostname || '').toLowerCase();
                  var targetYoutube = targetHost === 'youtube.com' ||
                    targetHost.substring(Math.max(0, targetHost.length - 12)) === '.youtube.com';
                  if (!targetYoutube) continue;
                  var targetPath = (target.pathname || '').toLowerCase();
                  if (!/^\/(watch|shorts|live)(\/|${'$'})/.test(targetPath)) continue;

                  var style = getComputedStyle(img);
                  if (style.display === 'none' || style.visibility === 'hidden' ||
                      parseFloat(style.opacity || '1') === 0) continue;
                  var rect = img.getBoundingClientRect();
                  var visibleW = Math.max(
                    0, Math.min(rect.right, viewportW) - Math.max(rect.left, 0)
                  );
                  var visibleH = Math.max(
                    0, Math.min(rect.bottom, viewportH) - Math.max(rect.top, 0)
                  );
                  var area = visibleW * visibleH;
                  if (area > bestArea) {
                    bestArea = area;
                    best = target.href;
                  }
                }
                return best || page;
              } catch (_) {
                return location.href || null;
              }
            })()
        """.trimIndent()

        /**
         * Thumbnail width in px. Twice the ~72px the bookmark pin draws at,
         * so it stays crisp and still costs about 12KB as JPEG.
         */
        const val THUMB_MAX_WIDTH = 144

        /**
         * Backdrop composited under a captured page. Not black: black is
         * transparent on this waveguide, and a bookmark that projects as
         * nothing is not a bookmark. Light enough that dark page text lands
         * on it legibly.
         */
        const val THUMB_BACKDROP = 0xFFE8E8E8.toInt()

        /**
         * The page-side half of edge scrolling: a velocity the document
         * applies on its own animation frames.
         *
         * Two things it must get right. Time-based rather than per-frame
         * steps, so a busy page scrolls the same distance per second as an
         * idle one instead of crawling — with the delta clamped, because a
         * long stall (a GC pause, a heavy layout) would otherwise resume by
         * teleporting the page. And a sub-pixel accumulator, because handing
         * scrollBy a fraction every frame rounds to nothing on some engines
         * and the slowest speeds simply never move.
         *
         * It also gives up once the box stops moving: the wearer leaves the
         * cursor parked at the rim after hitting the end of the article, and
         * there is no reason to keep an animation callback alive for that.
         */
        /**
         * Frame a standalone image to the window.
         *
         * Following an image result out of a search lands on a bare image
         * document, which the engine lays out at the picture's NATIVE size.
         * On a 170px-wide window a 2000px photo arrives as a meaningless
         * crop of its top-left corner, and the wearer has no way to zoom
         * out. Fitting it to the viewport is what makes "open that image"
         * mean anything here.
         *
         * Scoped to documents that are ONLY an image — an article's photos
         * must keep the layout the page gave them.
         */
        /**
         * Give a page a phone-width layout when it has pinned itself to the
         * WINDOW's width instead.
         *
         * A page whose layout viewport ends up as narrow as the WINDOW has
         * nowhere to put a design drawn for a phone, and if its scale is
         * pinned nothing shrinks to compensate. Measured in a 170px window:
         *
         *   wikipedia.org  innerWidth 170  scale 1.00  initial-scale=1, no width
         *   duckduckgo.com innerWidth 170  scale 1.00  width=device-width, initial-scale=1
         *   archive.org    innerWidth 339  scale 0.53
         *   kunstderfuge   innerWidth 680  scale 0.53
         *
         * The two that look right lay out WIDE and are then scaled down. The
         * two that do not are pinned at 1:1 against a viewport narrower than
         * any phone ever made.
         *
         * The test is the measured width, deliberately, not the wording of
         * the meta. An earlier version keyed on "declares a scale but omits a
         * width", which described wikipedia exactly and therefore missed
         * duckduckgo — same collapse, reached by saying width=device-width on
         * a device whose "width" is 170px. Asking what the layout viewport
         * actually came out as catches both, and any third spelling of the
         * same problem.
         *
         * Only pages that came out NARROWER than a phone are touched. The
         * ones already laying out wide are left exactly alone — forcing a
         * width on every page is what made text come out scrunched when a
         * global width was tried before.
         */
        private val VIEWPORT_FIT_JS = """
            (function(){
              if (window.__x3ViewportFit) return 'already';
              var target = $MOBILE_LAYOUT_CSS_WIDTH;

              var owned = false;

              function apply(){
                try {
                  // Act when the page has collapsed narrower than a phone —
                  // or whenever we already own the meta, because the window
                  // itself resizes on this device and the scale we wrote is
                  // only right for the size it was written at. Growing a
                  // window 170 -> 238 left the page still painting into 170px
                  // of it, since "wide enough" was true and nothing
                  // recomputed.
                  if (window.innerWidth >= target && !owned) return false;
                  var vv = window.visualViewport;
                  // Device px across the window. Invariant under our own
                  // rescaling — width shrinks exactly as scale grows — which
                  // is what lets this be recomputed safely on every resize.
                  var deviceW = vv ? vv.width * vv.scale : window.innerWidth;
                  if (!(deviceW > 0)) return false;
                  var want = 'width=' + target +
                    ', initial-scale=' + (deviceW / target);
                  var m = document.querySelector('meta[name=viewport]');
                  if (!m) {
                    m = document.createElement('meta');
                    m.setAttribute('name', 'viewport');
                    (document.head || document.documentElement).appendChild(m);
                  }
                  // Writing only on a real change is the loop guard: the
                  // resize our own write provokes recomputes the same string
                  // and stops here.
                  if (m.getAttribute('content') === want) return false;
                  m.setAttribute('content', want);
                  owned = true;
                  return true;
                } catch (e) { return false; }
              }

              var first = apply();

              // Watch for the COLLAPSE, not just for the meta changing.
              //
              // On duckduckgo.com the one-shot rewrite did nothing while the
              // page still ended up 170px wide, and the reason was timing,
              // not the page fighting back: writing the meta by hand sticks
              // permanently, so nothing reverts it. At injection time the
              // layout viewport was still wide, so apply() correctly decided
              // there was nothing to fix — and the narrowing that followed,
              // when the page's own meta took effect, changed no attribute
              // for a mutation observer to see.
              //
              // A resize IS that narrowing. Re-checking there catches the
              // case whenever it happens, instead of hoping a fixed delay
              // lands after it. apply() no-ops once the layout is wide
              // enough, and applying makes it wide enough, so the resize this
              // causes settles on the next call rather than oscillating.
              try {
                window.addEventListener('resize', apply);
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', apply);
                }
                var head = document.head || document.documentElement;
                if (head && window.MutationObserver) {
                  new MutationObserver(function(){ apply(); }).observe(head, {
                    childList: true, subtree: true, attributes: true,
                    attributeFilter: ['content', 'name']
                  });
                }
                setTimeout(apply, 400);
                setTimeout(apply, 1500);
              } catch (e) {}

              window.__x3ViewportFit = true;
              return first ? 'widened' : 'watching';
            })();
        """.trimIndent()

        /**
         * Give the page the white canvas it is assuming, when it declares
         * none of its own.
         *
         * A site that sets no background on html or body inherits the
         * browser's — white, everywhere on the web, which is why so many
         * sites never bother stating it. This WebView's background is
         * transparent, because on a waveguide black IS transparent and that
         * is what makes a HUD window float. So those pages hand us dark text
         * with nothing behind it and it lands on the black of the room.
         *
         * listennotes.com is the measured case: 3.9k of text in the DOM,
         * every element correctly positioned, and NOTHING on the glass but
         * three links — links being the only thing carrying its own colour.
         * Turning dark mode off made it worse, not better, which is what
         * ruled out force-dark as the cause: the links dimmed too. What was
         * missing was never contrast, it was the canvas.
         *
         * Stating the white the page assumed puts force-dark back on solid
         * ground — it darkens that canvas and lifts the text, exactly as it
         * does for every site that declares its own background. Verified on
         * device: the same window went from three links to a full masthead,
         * search box and body copy, on a dark background.
         */
        private val CANVAS_FIX_JS = """
            (function(){
              if (window.__x3Canvas) return 'already';
              function transparent(c){
                return !c || c === 'transparent' || /rgba\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)/.test(c);
              }
              function fix(){
                try {
                  var b = document.body;
                  if (!b) return false;
                  // BOTH, because the engine propagates body's background to
                  // the canvas when html has none — a page that paints only
                  // body is already fine and must not be touched.
                  var hb = getComputedStyle(document.documentElement).backgroundColor;
                  var bb = getComputedStyle(b).backgroundColor;
                  if (!transparent(hb) || !transparent(bb)) return false;
                  var s = document.getElementById('x3-canvas');
                  if (!s) {
                    s = document.createElement('style');
                    s.id = 'x3-canvas';
                    s.textContent = 'html{background-color:#ffffff !important}';
                    (document.head || document.documentElement).appendChild(s);
                  }
                  return true;
                } catch (e) { return false; }
              }
              /**
               * Let a page COMPRESS to the width it was given.
               *
               * Listen Notes is told width=320 and lays out at 564 anyway,
               * because something inside it refuses to be narrower and the
               * engine grows the layout viewport to fit. The scale still
               * suits 320, so barely half the page is on the glass and the
               * wearer reads a column of chrome down one side — the "it
               * isn't rendering" and "the marketing is overwhelming" report
               * are the same measurement.
               *
               * These are the four things that usually refuse: a fixed-width
               * image, a table, a preformatted block, and an element with an
               * explicit min-width in px. Capping them costs nothing on a
               * page that already fits, since max-width only ever binds when
               * something is too wide.
               */
              function squeeze(){
                try {
                  if (document.getElementById('x3-fit')) return;
                  var s = document.createElement('style');
                  s.id = 'x3-fit';
                  // canvas and svg are deliberately NOT in this list. A
                  // canvas's CSS size is its rendering surface: height:auto
                  // collapsed radio.garden's WebGL globe to a 0x0 rect and
                  // the wearer heard a station playing over a blank blue
                  // window. Neither was ever a measured cause of runaway
                  // width — that was document layout, not drawing surfaces.
                  s.textContent =
                    'img,video,iframe,table,pre{max-width:100% !important;' +
                    'height:auto !important}' +
                    'pre,code{white-space:pre-wrap !important;word-break:break-word}' +
                    // Only the ones wide enough to be the culprit; a 40px
                    // min-width on an icon is not what stretched the page.
                    '[style*="min-width"]{min-width:0 !important}';
                  (document.head || document.documentElement).appendChild(s);
                } catch (e) {}
              }

              /**
               * Take down what a page pins over its own content.
               *
               * On a phone a sticky app-install bar or newsletter slab costs
               * a tenth of the screen. Here the whole window is 226px tall,
               * so one of them IS the window, and the wearer scrolls a page
               * they cannot see. Both podcast sites do it and it is what
               * "the marketing is overwhelming" describes.
               *
               * Measured by GEOMETRY, not by class name: only elements the
               * page has fixed or stuck to the viewport, and only those tall
               * enough to matter. Podchaser's classes are hashed — _3hmsj,
               * _txj152, _buvx5d — so a name-based rule would be broken by
               * their next deploy, while "pinned and eating a third of the
               * glass" stays true whatever it is called.
               *
               * A pinned bar SHORTER than the cutoff is left alone: that is
               * a normal header, and hiding those loses the nav and the
               * search box the wearer came for.
               */
              function unpin(){
                try {
                  var vh = window.innerHeight || 800;
                  // Unpinning exists to reclaim READING space: a promo slab
                  // stuck over a page the wearer is scrolling. A page that
                  // does not scroll has no reading space to reclaim — it is
                  // an app shell, and its fixed elements ARE the app. On
                  // radio.garden this hid the div holding the WebGL globe
                  // and five of the site's own panels, leaving audio over a
                  // blank blue window.
                  var sh = Math.max(
                    document.documentElement.scrollHeight,
                    document.body ? document.body.scrollHeight : 0);
                  if (sh < vh * 1.2) return 0;
                  var limit = vh * 0.28;
                  var all = document.body ? document.body.querySelectorAll('*') : [];
                  var n = 0;
                  for (var i = 0; i < all.length && n < 12; i++) {
                    var e = all[i];
                    var cs = getComputedStyle(e);
                    if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
                    var r = e.getBoundingClientRect();
                    if (r.height < limit || r.width < 80) continue;
                    // Near-viewport-sized is the app itself, not a bar over
                    // it; and anything drawing or playing is a surface the
                    // wearer is here FOR, whatever its geometry.
                    if (r.height > vh * 0.85) continue;
                    if (e.querySelector && e.querySelector('canvas,video,audio')) continue;
                    // Never the scroll container itself.
                    if (e === document.body || e === document.documentElement) continue;
                    e.style.setProperty('display', 'none', 'important');
                    n++;
                  }
                  return n;
                } catch (e) { return 0; }
              }

              // ONE tick for all three, re-run on a schedule. Each is
              // separately idempotent, and a single-page app re-rendering its
              // head throws our styles away with its own — measured: the
              // canvas survived the first load and the fit rule did not,
              // purely because only one of them was being re-checked.
              function tick(){ fix(); squeeze(); unpin(); }
              tick();
              setTimeout(tick, 700);
              setTimeout(tick, 1800);
              setTimeout(tick, 4000);
              // A single-page app can paint its background after first load,
              // and can also throw ours away with a re-render; re-checking is
              // cheap and fix() no-ops once anything opaque is in place.
              setTimeout(fix, 600);
              setTimeout(fix, 2000);
              window.__x3Canvas = true;
              return 'canvas checked';
            })();
        """.trimIndent()

        private val IMAGE_FIT_JS = """
            (function(){
              function isImageDocument(){
                var b = document.body;
                if (!b) return false;
                if (b.children.length !== 1) return false;
                var only = b.firstElementChild;
                return !!only && only.tagName === 'IMG';
              }
              function fit(){
                try {
                  if (!isImageDocument()) return;
                  var id = 'x3-image-fit';
                  if (document.getElementById(id)) return;
                  var st = document.createElement('style');
                  st.id = id;
                  st.textContent =
                    'html,body{margin:0!important;padding:0!important;height:100%!important;' +
                    'overflow:hidden!important;background:#000!important;}' +
                    'body{display:flex!important;align-items:center!important;' +
                    'justify-content:center!important;}' +
                    'img{max-width:100%!important;max-height:100vh!important;' +
                    'width:auto!important;height:auto!important;object-fit:contain!important;}';
                  (document.head || document.documentElement).appendChild(st);
                } catch (e) {}
              }
              fit();
              // The <img> is not in the DOM yet at page-start injection, and
              // the engine also re-lays-out once the real dimensions arrive.
              document.addEventListener('DOMContentLoaded', fit);
              window.addEventListener('load', fit);
              setTimeout(fit, 300);
              setTimeout(fit, 1200);

              // Tapping a search-result thumbnail should show the PICTURE.
              // DuckDuckGo's grid links to its own images app instead — a
              // JS lightbox built for a phone screen, which on a 170px
              // window is unusable and never yields a plain image. But it
              // proxies every thumbnail through /iu/?u=<real url>, so the
              // actual picture is right there in the markup: take it and
              // navigate to it, and the fit above frames it.
              //
              // Keyed on the proxy host, so this cannot fire on an ordinary
              // site where clicking a picture is meant to follow a link.
              function proxiedImage(img){
                try {
                  var s = img.currentSrc || img.src || '';
                  if (s.indexOf('external-content.duckduckgo.com') === -1) return null;
                  var m = s.match(/[?&]u=([^&]+)/);
                  return m ? decodeURIComponent(m[1]) : null;
                } catch (e) { return null; }
              }
              if (document.documentElement && !window.__x3ImgOpen) {
                window.__x3ImgOpen = true;
                document.addEventListener('click', function(e){
                  var t = e.target;
                  if (!t) return;
                  var img = t.tagName === 'IMG' ? t : (t.closest ? t.closest('img') : null);
                  if (!img) return;
                  var full = proxiedImage(img);
                  if (!full) return;
                  // Capture phase + stopPropagation, or the site's own
                  // handler still runs and navigates to its gallery app.
                  e.preventDefault();
                  e.stopPropagation();
                  location.href = full;
                }, true);
              }
            })();
        """.trimIndent()

        private val EDGE_SCROLL_JS = """
            (function(){
              if (window.__x3EdgeScroll) return;
              var E = { vx:0, vy:0, raf:0, last:0, accX:0, accY:0,
                        tx:null, ty:null, stillX:0, stillY:0,
                        lastX:null, lastY:null, deadX:null, deadY:null };

              // Where the page is sitting, as one comparable value, so
              // "has anything changed under an axis we already found
              // nothing on" costs a string compare instead of a DOM sweep.
              function key(){ return (window.pageYOffset|0) + ',' + (window.pageXOffset|0); }

              // Per-axis, because the box that scrolls sideways is often not
              // the one that scrolls down — a wide table inside an article
              // is the usual case.
              //
              // NULL is a real answer here: "nothing on this axis can move".
              // Returning `window` instead was the defect behind "web pages
              // never edge scroll to the right". useWideViewPort makes the
              // engine shrink the page until the whole content width fits the
              // window, so the document's horizontal scroll range is zero on
              // essentially every site — and handing the scroll to `window`
              // anyway meant the loop spent 41 frames pushing a scroller that
              // the line above had just PROVEN dead, cached it in E.tx for the
              // rest of the park so the wearer could not get a second opinion
              // after scrolling a wide table into view, and then stopped
              // without telling the host or the wearer anything at all.
              function pick(horiz){
                try {
                  // The document first, measured against innerWidth /
                  // innerHeight — the VISUAL viewport in CSS px, i.e. what is
                  // actually on screen at the current page scale.
                  //
                  // NOT documentElement.clientWidth. That is the initial
                  // containing block (170 CSS px in a base window), so
                  // scrollWidth > clientWidth reads TRUE on the root of almost
                  // every page while the root cannot move a pixel: the engine
                  // absorbed that overflow into zoom-out, not into scroll
                  // range. Swapping clientWidth in here looks like a units fix
                  // and would break the one case that works today — a 980px
                  // desktop page, whose content really is wider than the
                  // visual viewport and really does pan.
                  var docCan = horiz
                    ? (document.documentElement.scrollWidth > innerWidth + 4)
                    : (document.documentElement.scrollHeight > innerHeight + 4);
                  if (docCan) return window;

                  // What the wearer can see, in client coordinates. Once the
                  // page has been zoomed out to fit, the visual viewport is
                  // WIDER than the containing block, so the larger of the two
                  // is the honest bound.
                  var vw = Math.max(innerWidth, document.documentElement.clientWidth);
                  var vh = Math.max(innerHeight, document.documentElement.clientHeight);

                  var best = null, bestScore = 0, all = document.querySelectorAll('*');
                  for (var i = 0; i < all.length && i < 4000; i++){
                    var e = all[i];
                    // html and body ARE the document, which docCan just
                    // answered, and their clientWidth is the containing block
                    // rather than what is on screen — so they measure as
                    // scrollable and then refuse to move. A site that puts
                    // overflow-x:auto on body would otherwise win this sweep
                    // outright and hand back a dead target through the branch
                    // this function treats as success.
                    if (e === document.documentElement || e === document.body) continue;
                    var s = getComputedStyle(e);
                    if (!/auto|scroll/.test(horiz ? s.overflowX : s.overflowY)) continue;
                    var can = horiz ? (e.scrollWidth > e.clientWidth + 4)
                                    : (e.scrollHeight > e.clientHeight + 4);
                    if (!can) continue;
                    var r = e.getBoundingClientRect();
                    // A scroller the wearer cannot see is the wrong answer
                    // even when it is the biggest one in the document. The
                    // sweep is document-wide, so a carousel in the footer of a
                    // long article used to win and then scroll silently, off
                    // screen — which is exactly what "sometimes sections
                    // scroll" looks like from outside the window.
                    var visW = Math.min(r.right, vw) - Math.max(r.left, 0);
                    var visH = Math.min(r.bottom, vh) - Math.max(r.top, 0);
                    if (visW <= 0 || visH <= 0) continue;
                    // Sideways scrollers are STRIPS, and a strip's height says
                    // nothing about whether it is the thing the wearer means.
                    // Scoring horizontal by area asked a 30px tab bar to be as
                    // tall as an article pane: the old 0.10-of-viewport-area
                    // gate works out at 0.10 * viewportHeight * (viewportWidth
                    // / stripWidth) of required HEIGHT, so a full-bleed rail
                    // needed 32 CSS px but an inset half-width one needed 65.
                    // Width is the measurement that actually separates a
                    // horizontal scroller from a decoration.
                    if (horiz){
                      if (visW > bestScore){ bestScore = visW; best = e; }
                    } else {
                      var a = visW * visH;
                      if (a > bestScore){ bestScore = a; best = e; }
                    }
                  }
                  if (!best) return null;
                  // A sliver is not a scroller. Half the visible width is low
                  // enough for a code block sitting inside an article's
                  // padding and high enough to skip the little rails sites
                  // hang in sidebars. Vertical keeps the area rule, where a
                  // content pane genuinely is the biggest box on the page.
                  if (horiz && bestScore < vw * 0.5) return null;
                  if (!horiz && bestScore < vw * vh * 0.40) return null;
                  return best;
                } catch (err) { return null; }
              }
              function posOf(t, horiz){
                if (!t) return 0;
                if (t === window) return horiz
                  ? (window.pageXOffset || document.documentElement.scrollLeft || 0)
                  : (window.pageYOffset || document.documentElement.scrollTop || 0);
                return horiz ? t.scrollLeft : t.scrollTop;
              }
              function apply(t, dx, dy){
                if (!t) return;
                if (t === window) window.scrollBy(dx, dy);
                else { if (dx) t.scrollLeft += dx; if (dy) t.scrollTop += dy; }
              }
              // Is the DOCUMENT scroller sitting on its limit right now?
              //
              // Worth asking separately from the still-counter below because
              // the answer is knowable and the wait is expensive: the host
              // cannot begin the view-level pan that covers the REST of the
              // page until this axis reports in, and 40 frames is two thirds
              // of a second of a page that has visibly stopped dead. That
              // pause, in the middle of a scroll the wearer is holding, is
              // most of what "it moves a bit and then jams" was.
              //
              // Only for `window`. The root's ceiling is scrollWidth minus
              // innerWidth — the very pair docCan is built on, so trusting it
              // here adds no new assumption — while an element's dimensions
              // can be mid-layout and there is no second opinion to be had.
              // A page using scroll-behavior:smooth is safe: this compares
              // positions, so an animation still in flight is simply not at
              // the end yet.
              function atEnd(t, horiz, pos, v){
                if (t !== window) return false;
                if (v < 0) return pos <= 0;
                var max = horiz
                  ? document.documentElement.scrollWidth - innerWidth
                  : document.documentElement.scrollHeight - innerHeight;
                return pos >= max - 1;
              }
              // Tell the host an axis has nothing to scroll. Two reasons, and
              // both of them are why this bug survived so long.
              //
              // The host dedupes velocities so a cursor resting at one depth
              // costs one bridge call and not one per motion event — but that
              // assumes the page is still running what it was last sent. When
              // the page stops on its own the two disagree, and the wearer
              // nudging back to the same depth produces the same quantised
              // number, which the dedupe then drops: the axis can never be
              // restarted from the host side.
              //
              // And silence is the complaint itself. "It never scrolls right"
              // and "there is nothing to the right" look identical from inside
              // a 170px window.
              function idle(horiz){
                try {
                  if (window.X3Input && X3Input.onEdgeScrollIdle) X3Input.onEdgeScrollIdle(!!horiz);
                } catch (e) {}
              }
              function step(ts){
                if (!E.vx && !E.vy){ E.raf = 0; return; }
                if (!E.last) E.last = ts;
                var dt = ts - E.last; E.last = ts;
                if (dt > 64) dt = 64;
                if (dt < 0) dt = 0;
                // The first frame after any restart has dt 0 by construction —
                // E.last is seeded here, not when the velocity arrived — so it
                // cannot move anything. Scoring it as "no progress" is what
                // used to kill a retry on frame ONE, because the give-up
                // counter survived from the previous attempt.
                if (!dt){ E.raf = requestAnimationFrame(step); return; }
                E.accX += E.vx * dt / 1000;
                E.accY += E.vy * dt / 1000;
                var wx = E.accX > 0 ? Math.floor(E.accX) : Math.ceil(E.accX);
                var wy = E.accY > 0 ? Math.floor(E.accY) : Math.ceil(E.accY);
                if (wx){ E.accX -= wx; apply(E.tx, wx, 0); }
                if (wy){ E.accY -= wy; apply(E.ty, 0, wy); }
                // Progress is per axis now. One shared counter plus an OR
                // meant a corner gesture whose vertical half was working
                // reported "moving" every frame, so a dead horizontal half
                // never registered at all: the page scrolled down for as long
                // as the wearer waited for it to go right.
                if (E.vx){
                  var px = posOf(E.tx, true);
                  if (atEnd(E.tx, true, px, E.vx) ||
                      (E.lastX !== null && px === E.lastX && ++E.stillX > 40)){
                    E.vx = 0; E.accX = 0; E.tx = null; E.lastX = null;
                    E.stillX = 0; E.deadX = key(); idle(true);
                  } else {
                    if (E.lastX === null || px !== E.lastX) E.stillX = 0;
                    E.lastX = px;
                  }
                }
                if (E.vy){
                  var py = posOf(E.ty, false);
                  if (atEnd(E.ty, false, py, E.vy) ||
                      (E.lastY !== null && py === E.lastY && ++E.stillY > 40)){
                    E.vy = 0; E.accY = 0; E.ty = null; E.lastY = null;
                    E.stillY = 0; E.deadY = key(); idle(false);
                  } else {
                    if (E.lastY === null || py !== E.lastY) E.stillY = 0;
                    E.lastY = py;
                  }
                }
                if (!E.vx && !E.vy){ E.raf = 0; return; }
                E.raf = requestAnimationFrame(step);
              }
              window.__x3EdgeScroll = function(vx, vy){
                vy = vy || 0;
                E.vx = vx || 0; E.vy = vy;
                if (!E.vx && !E.vy){
                  if (E.raf) cancelAnimationFrame(E.raf);
                  E.raf = 0; E.tx = null; E.ty = null;
                  E.accX = 0; E.accY = 0; E.lastX = null; E.lastY = null;
                  E.stillX = 0; E.stillY = 0; E.deadX = null; E.deadY = null;
                  return;
                }
                // Resolve each axis lazily: the DOM sweep is the expensive
                // part and a vertical-only scroll must not pay for it twice.
                //
                // An axis that came back empty is remembered against the
                // page's scroll position rather than latched. Latching was the
                // old bug — nothing could restart it for the rest of the park
                // — but re-running a 4000-element getComputedStyle sweep on
                // every nudge inside a 22px band is not free either, and the
                // way a wearer reaches something that CAN go sideways is by
                // scrolling down to it.
                if (E.vx && !E.tx){
                  if (E.deadX === key()) E.vx = 0;
                  else {
                    E.tx = pick(true); E.lastX = null; E.stillX = 0;
                    if (!E.tx){ E.vx = 0; E.deadX = key(); idle(true); }
                  }
                }
                if (E.vy && !E.ty){
                  if (E.deadY === key()) E.vy = 0;
                  else {
                    E.ty = pick(false); E.lastY = null; E.stillY = 0;
                    if (!E.ty){ E.vy = 0; E.deadY = key(); idle(false); }
                  }
                }
                if (!E.vx && !E.vy) return;
                if (!E.raf){ E.last = 0; E.raf = requestAnimationFrame(step); }
              };
            })();
        """.trimIndent()

        /**
         * PORTRAIT 3:4 sizes in logical px, roughly 0.75× / 1× / 1.4× /
         * 1.9× of the 170×226 base. The top step is 430 tall because that
         * is exactly the height of the under-HUD zone (44…474), so the
         * ladder itself is the clamp against overflowing the viewport.
         */
        private val SIZE_LADDER = arrayOf(
            intArrayOf(128, 170),
            intArrayOf(170, 226),
            intArrayOf(238, 317),
            intArrayOf(323, 430)
        )

        /** Index of the 170×226 default in [SIZE_LADDER]. */
        const val BASE_STEP = 1

        /** Narrow-phone width every responsive site is built for. */
        private const val MOBILE_LAYOUT_CSS_WIDTH = 320

        private const val MODERN_UA =
            "Mozilla/5.0 (Linux; Android 12; RayNeo X3 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

        /**
         * "Get our app" interstitials, killed on sight.
         *
         * These are a nuisance on a phone and fatal here: a window is 170-323
         * logical px wide, so a banner sized for a phone screen covers the
         * entire page and there is often no reachable dismiss button — the ✕
         * sits outside the window's edge. The wearer is left looking at an
         * advert with no way out.
         *
         * Two mechanisms, because one is not enough. A stylesheet of known
         * selectors lands before the banner ever paints, but the class names
         * that identify them are increasingly build-hashed and unusable. So a
         * heuristic sweep backs it up, and it samples with elementsFromPoint
         * rather than walking the DOM: whatever is covering the middle of the
         * viewport is the thing in the way, which is O(1) instead of a
         * getComputedStyle over every node on the page.
         */
        /**
         * Make YouTube play with the sound on.
         *
         * A video opened in a window autoplays perfectly well —
         * mediaPlaybackRequiresUserGesture is already false, so Chromium
         * permits it — but it arrives MUTED, and on glasses there is no
         * comfortable way to hit a small unmute button in a 170px window.
         *
         * The cause is not the WebView policy. Probing a real video on the
         * device showed navigator.userActivation.hasBeenActive === false:
         * nothing in a HUD window is ever "clicked" the way a page expects,
         * so YouTube's own player takes its no-user-activation branch and
         * starts muted defensively, exactly as it would in a browser that
         * forbids unmuted autoplay. Chromium is allowing sound; YouTube is
         * declining to make any.
         *
         * So the fix has to talk to the PLAYER, not the platform:
         * #movie_player.unMute() + setVolume(100). Measured on device, this
         * takes hold and stays — still unmuted, still playing, seconds later
         * — and dumpsys shows a real started USAGE_MEDIA track, so this is
         * audible output and not just a DOM flag flipped in the dark.
         *
         * Two things were tried first and are deliberately NOT here:
         *
         *  - Seeding localStorage 'yt-player-volume' with muted:false before
         *    the player boots. The seed was written and survived the
         *    navigation intact — and the next video still came up muted.
         *    The decision is driven by user activation, not by the wearer's
         *    remembered volume, so this layer is pure dead weight.
         *  - Re-arming on 'yt-navigate-finish'. It never fires on
         *    m.youtube.com; a spy on six candidate yt-* events caught none
         *    of them across a real in-page navigation. Only a plain
         *    location.href poll saw it. The desktop advice is wrong here.
         *
         * Scoped to YouTube on purpose, and within YouTube to pages that
         * ARE a video (/watch, /shorts, /live, /embed). A general "unmute
         * anything that autoplays" would hand every news site's pre-roll ad
         * a speaker on the wearer's temple, and unmuting the feed's inline
         * previews would do the same thing one scroll at a time.
         *
         * The latch is per-URL: unmute once, then stop and stay stopped. If
         * the wearer mutes the video themselves, nothing fights them back.
         * A new URL is new intent, so it re-arms.
         */
        private val MEDIA_AUTOPLAY_JS = """
            (function(){
              var H = location.hostname || '';
              if (!/(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)${'$'}/.test(H)) return;

              // Injected at BOTH page-start and page-finish, and the document
              // survives a soft navigation, so re-entry is the normal case.
              if (window.__x3Media) { window.__x3Media.rearm(); return; }

              var armedFor = null, deadline = 0, timer = 0, nudged = 0;

              // Which video the URL is ASKING for. '' for the feed, search,
              // a channel — anywhere without a player of its own. Feed
              // previews are muted deliberately and unmuting them would be
              // both a surprise and a fight, since that is the one context
              // where YouTube re-applies mute on its own.
              function wantedId(){
                var path = location.pathname || '';
                var m = path.match(/^\/(shorts|live|embed)\/([\w-]{6,})/);
                if (m) return m[2];
                if (path === '/watch') {
                  var q = (location.search || '').match(/[?&]v=([\w-]{6,})/);
                  return q ? q[1] : '';
                }
                return '';
              }

              function playing(p){
                try {
                  var d = p && p.getVideoData && p.getVideoData();
                  return (d && d.video_id) || '';
                } catch (e) { return ''; }
              }

              function attempt(id){
                var v = document.querySelector('video');
                if (!v) return false;
                // The other half of this patch pauses page media while the
                // microphone is open. Playing it again here would hand the
                // wearer's own video straight back to the recorder — and
                // this is not a failed attempt, so it must not burn a try.
                //
                // __x3Hold is the document-wide form, for a loop that was
                // already spinning when the hold arrived: the per-element
                // mark only lands on media that existed at that moment, and
                // a video that starts DURING a session would carry none.
                if (v.__x3Held || window.__x3Hold) return false;
                var p = document.querySelector('#movie_player');
                // Act only once the player is on the video the URL names.
                // The <video> NODE IS REUSED across a soft navigation, so a
                // previous clip's healthy state would otherwise read as
                // success for a new one that has not even loaded yet.
                if (!p || !p.unMute || playing(p) !== id) return false;
                // The player owns the mute state; writing the element
                // behind its back leaves its UI disagreeing with the sound,
                // and it pushes its own value back at the next state change.
                try { p.unMute(); } catch (e) {}
                // Lift the level only off the floor. Setting 100 every time
                // would stamp on a volume the wearer had just chosen, every
                // time they moved to the next video.
                try { if (p.getVolume() === 0) p.setVolume(100); } catch (e) {}
                // At most ONE nudge per video. Autoplay is already permitted
                // here, so a second is never what starts a video — but it IS
                // what undoes a wearer who reached up and paused it. The
                // loop's business is the mute state, not the transport.
                if (v.paused && !nudged) { nudged = 1; try { p.playVideo(); } catch (e) {} }
                // Ask the PLAYER whether it took. Reading v.muted back after
                // writing v.muted = false is a tautology — it would report
                // success on the very first tick, including ticks where the
                // player had not booted yet, and tear down the retry loop
                // that was the entire point.
                var ok = false;
                try { ok = (p.isMuted() === false); } catch (e) { ok = false; }
                return ok && !v.paused;
              }

              function stop(){ if (timer) { clearInterval(timer); timer = 0; } }

              // The player boots asynchronously and is usually absent when
              // the page reports finished, so one shot at load is a coin
              // flip.
              //
              // Bounded by WALL CLOCK, not by a tick count. A pre-roll ad is
              // a different video as far as the player is concerned — its own
              // id sits in getVideoData() for the ad's whole duration — so
              // every tick during an ad is a legitimate "not yet", and a
              // 60-tick budget would be spent by a 30s ad and torn down at
              // the exact moment the real content starts. Two minutes leaves
              // room for an ad and then some; past that the page is simply
              // not going to produce this video, and an unbounded timer on a
              // window that may sit on the HUD for an hour is litter.
              function rearm(){
                var id = wantedId();
                if (!id) { armedFor = null; stop(); return; }
                if (id === armedFor) return;
                armedFor = id;
                nudged = 0;
                deadline = Date.now() + 120000;
                stop();
                if (attempt(id)) return;
                timer = setInterval(function(){
                  if (attempt(id) || Date.now() > deadline) stop();
                }, 350);
              }

              window.__x3Media = { rearm: rearm };
              rearm();
              // Keyed on the VIDEO ID rather than the href. YouTube rewrites
              // its own query string under a stationary video (&t=, &pp=),
              // and re-arming on that would shove the volume back to 100 in
              // the middle of watching something. The id is what actually
              // changes when the video does. Cost is a string compare at 2Hz
              // — no yt-* event fires here to hang this on.
              setInterval(function(){ if (wantedId() !== armedFor) rearm(); }, 500);
            })();
        """

        /**
         * Silence page media while the microphone is open, and put it back.
         *
         * This is the other half of unmuting YouTube, and without it the
         * feature quietly breaks the app's main interface. The speakers sit
         * on the temples, centimetres from the mic. A video that is finally
         * audible is therefore also audible TO US: double-tapping a playing
         * window to give the page agent a spoken task would record the video
         * instead of the wearer, and Whisper would faithfully transcribe it.
         * The voice pipeline's barge-in watcher would fare worse — it treats
         * incoming sound as the wearer interrupting, so a video playing
         * under a Gemini reply would cut that reply off again and again.
         *
         * Pause rather than mute: a muted video keeps running and the wearer
         * loses whatever it said while they were talking. Pausing holds the
         * place, which is what someone who just turned away to speak wants.
         * Only what WE paused is resumed, so a video the wearer had already
         * paused stays paused.
         */
        /**
         * Open Radio Garden's front door whenever a station page arrives.
         *
         * Radio Garden loads behind a full-window "Start Radio Garden" cover
         * and plays nothing at all until it is dismissed. Tuning by voice
         * already clicks it, but a station reached ANY OTHER WAY sat silent:
         * the wearer pinned a playing station to the HUD, tapped the pin, and
         * got the right page with no sound — the bookmark was correct, the
         * cover was simply back. Same for a link, a restored window, or the
         * back button. Dismissing it on arrival is what makes the pin mean
         * what it looks like it means.
         *
         * Only on a STATION path. On the bare globe the cover is a real
         * choice — dismissing it there would start playing whatever the
         * planet happened to be pointing at, which nobody asked for.
         *
         * Whether this worked cannot be read from the DOM: Radio Garden
         * plays through Web Audio, so there is no <audio> element even while
         * sound is coming out. Verified on device as a live USAGE_MEDIA
         * track in dumpsys alongside nAudio 0.
         */
        private val RADIO_GARDEN_START_JS = """
            (function(){
              if (!/(^|\.)radio\.garden${'$'}/.test(location.hostname || '')) return;
              if (!/^\/(listen|visit)\//.test(location.pathname || '')) return;
              if (window.__x3RgStart) return;
              window.__x3RgStart = 1;
              var tries = 0;
              var iv = setInterval(function(){
                var gate = document.querySelector('[aria-label="Start Radio Garden"]');
                if (gate) { try { gate.click(); } catch (e) {} }
                if (++tries > 50) clearInterval(iv);
              }, 400);
            })();
        """

        /** True while anything on the page is actually making sound/motion. */
        private val MEDIA_PLAYING_JS = """
            (function(){
              var m = document.querySelectorAll('video,audio');
              for (var i = 0; i < m.length; i++) {
                if (!m[i].paused && !m[i].ended) return true;
              }
              return false;
            })();
        """

        private val MEDIA_SUSPEND_JS = """
            (function(){
              window.__x3Hold = 1;
              var m = document.querySelectorAll('video,audio');
              for (var i = 0; i < m.length; i++) {
                if (!m[i].paused) { try { m[i].pause(); m[i].__x3Held = 1; } catch(e){} }
              }
            })();
        """

        private val MEDIA_RESUME_JS = """
            (function(){
              window.__x3Hold = 0;
              var m = document.querySelectorAll('video,audio');
              for (var i = 0; i < m.length; i++) {
                if (m[i].__x3Held) { m[i].__x3Held = 0; try { m[i].play(); } catch(e){} }
              }
            })();
        """

        private val SITE_CHROME_FILTER_JS = """
            (function(){
              var STYLE_ID = 'x3hub-site-chrome-filter';
              if (!document.getElementById(STYLE_ID)){
                var style = document.createElement('style');
                style.id = STYLE_ID;
                style.textContent = [
                  // DuckDuckGo: the full-page "Get DuckDuckGo Browser" sheet,
                  // plus the persistent SERP promo in both templates.
                  '[data-testid="mobile-app-banner"]',
                  '#react-browser-update-info',
                  '[data-testid="serp-atb-btn"]',
                  // The cross-site conventions for the same banner.
                  '.smartbanner', '[id*="smartbanner" i]', '[class*="smartbanner" i]',
                  '#branch-banner-iframe', '.branch-banner-iframe', '.branch-journeys-top',
                  '[class*="app-banner" i]', '[id*="app-banner" i]', '[class*="appbanner" i]',
                  '[class*="app-promo" i]', '[class*="apppromo" i]',
                  '[class*="open-in-app" i]', '[class*="openinapp" i]',
                  '[class*="install-app" i]', '[class*="get-the-app" i]'
                ].join(',') + '{display:none!important}';
                (document.head || document.documentElement).appendChild(style);
              }

              if (window.__x3PromoSweep) return;
              window.__x3PromoSweep = true;

              // An action verb close to "app"/"browser" — "Get DuckDuckGo
              // Browser", "Open in app", "Continue in the app". Kept to one
              // sentence so an article that merely discusses apps does not
              // trip it.
              var PROMO = /\b(get|open|download|install|try|use|continue)\b[^.!?]{0,40}\b(app|browser)\b|add to home screen/i;

              function isOverlay(el){
                try {
                  if (!el || el === document.body || el === document.documentElement) return false;
                  var s = getComputedStyle(el);
                  // Only fixed/sticky: page content scrolls away on its own,
                  // and hiding something merely absolutely-positioned is how
                  // a filter starts eating real articles.
                  if (s.position !== 'fixed' && s.position !== 'sticky') return false;
                  if (s.display === 'none' || s.visibility === 'hidden') return false;
                  if (parseFloat(s.opacity) === 0) return false;
                  var r = el.getBoundingClientRect();
                  if (r.width < 1 || r.height < 1) return false;
                  if ((r.width * r.height) / Math.max(1, innerWidth * innerHeight) < 0.25) return false;
                  return PROMO.test((el.innerText || '').slice(0, 400));
                } catch (e) { return false; }
              }

              function unlock(){
                // These sheets lock the page behind them; leaving that in
                // place would mean the banner is gone and nothing scrolls.
                [document.documentElement, document.body].forEach(function(n){
                  try {
                    var s = getComputedStyle(n);
                    if (s.overflow === 'hidden' || s.overflowY === 'hidden'){
                      n.style.setProperty('overflow', 'auto', 'important');
                    }
                  } catch (e) {}
                });
              }

              function sweep(){
                try {
                  var pts = [
                    [innerWidth / 2, innerHeight / 2],
                    [innerWidth / 2, innerHeight * 0.85],
                    [innerWidth / 2, innerHeight * 0.15]
                  ];
                  var hid = false;
                  for (var p = 0; p < pts.length; p++){
                    var stack = document.elementsFromPoint(pts[p][0], pts[p][1]) || [];
                    for (var i = 0; i < stack.length && i < 12; i++){
                      if (isOverlay(stack[i])){
                        stack[i].style.setProperty('display', 'none', 'important');
                        hid = true;
                        break;
                      }
                    }
                  }
                  if (hid) unlock();
                } catch (e) {}
              }

              sweep();
              // Most of these arrive a beat after load, some after a scroll.
              var ticks = 0;
              var iv = setInterval(function(){ sweep(); if (++ticks > 20) clearInterval(iv); }, 500);
              try {
                var pending = false;
                new MutationObserver(function(){
                  if (pending) return;
                  pending = true;
                  setTimeout(function(){ pending = false; sweep(); }, 400);
                }).observe(document.documentElement, { childList: true, subtree: true });
              } catch (e) {}
            })();
        """.trimIndent()

        private val POLYFILL_JS = """
            (function(){
              if (window.__x3hubPoly) return; window.__x3hubPoly = true;
              // Trusted Types pass-through. This was deliberately left out
              // while nothing injected DOM — relaxing a defence the page
              // asked for buys nothing if you are not going to use it. The
              // page agent DOES assign innerHTML, so on any site sending
              // require-trusted-types-for 'script' it dies at init without
              // this. Kept as narrow as it can be: forwards the string
              // unchanged, and never runs if the page installed its own.
              try{
                if (window.trustedTypes && window.trustedTypes.createPolicy && !window.trustedTypes.defaultPolicy){
                  window.trustedTypes.createPolicy('default', {
                    createHTML: function(s){ return s; },
                    createScript: function(s){ return s; },
                    createScriptURL: function(s){ return s; }
                  });
                }
              }catch(e){}
              function def(o,n,f){ try{ if(!o[n]) Object.defineProperty(o,n,{value:f,writable:true,configurable:true}); }catch(e){} }
              try{ Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true}); }catch(e){}
              try{
                if (navigator.userAgentData){
                  var brands=[{brand:'Chromium',version:'125'},{brand:'Google Chrome',version:'125'},{brand:'Not.A/Brand',version:'24'}];
                  Object.defineProperty(navigator.userAgentData,'brands',{get:function(){return brands;},configurable:true});
                }
              }catch(e){}
              def(Array.prototype,'at',function(i){ i=Math.trunc(i)||0; if(i<0) i+=this.length; return (i<0||i>=this.length)?undefined:this[i]; });
              def(String.prototype,'at',function(i){ i=Math.trunc(i)||0; if(i<0) i+=this.length; return (i<0||i>=this.length)?undefined:this[i]; });
              def(Array.prototype,'findLast',function(cb,th){ for(var i=this.length-1;i>=0;i--){ if(cb.call(th,this[i],i,this)) return this[i]; } });
              def(Array.prototype,'findLastIndex',function(cb,th){ for(var i=this.length-1;i>=0;i--){ if(cb.call(th,this[i],i,this)) return i; } return -1; });
              def(Array.prototype,'toSorted',function(c){ return this.slice().sort(c); });
              def(Array.prototype,'toReversed',function(){ return this.slice().reverse(); });
              def(Array.prototype,'with',function(i,v){ var a=this.slice(); a[i<0?a.length+i:i]=v; return a; });
              def(Promise,'withResolvers',function(){ var res,rej,p=new Promise(function(a,b){res=a;rej=b;}); return {promise:p,resolve:res,reject:rej}; });
              def(Object,'groupBy',function(items,cb){ var o=Object.create(null),i=0; [].slice.call(items).forEach(function(it){ var k=cb(it,i++); (o[k]=o[k]||[]).push(it); }); return o; });
              if (typeof structuredClone!=='function'){ window.structuredClone=function sc(v,seen){ seen=seen||new Map(); if(v===null||typeof v!=='object') return v; if(seen.has(v)) return seen.get(v); if(v instanceof Date) return new Date(v.getTime()); if(v instanceof RegExp) return new RegExp(v.source,v.flags); if(v instanceof Map){var m=new Map();seen.set(v,m);v.forEach(function(val,k){m.set(sc(k,seen),sc(val,seen));});return m;} if(v instanceof Set){var s=new Set();seen.set(v,s);v.forEach(function(val){s.add(sc(val,seen));});return s;} if(Array.isArray(v)){var a=[];seen.set(v,a);for(var i=0;i<v.length;i++)a[i]=sc(v[i],seen);return a;} var o={};seen.set(v,o);Object.keys(v).forEach(function(k){o[k]=sc(v[k],seen);});return o; }; }
              if (typeof reportError!=='function'){ try{ window.reportError=function(e){ try{console.error(e);}catch(_){} }; }catch(e){} }
              if (typeof AbortSignal!=='undefined'){
                if (!AbortSignal.prototype.throwIfAborted){
                  Object.defineProperty(AbortSignal.prototype,'throwIfAborted',{value:function(){ if(this.aborted) throw (this.reason||new DOMException('Aborted','AbortError')); },writable:true,configurable:true});
                }
                def(AbortSignal,'timeout',function(ms){ var c=new AbortController(); setTimeout(function(){ try{ c.abort(new DOMException('TimeoutError','TimeoutError')); }catch(e){ c.abort(); } },ms); return c.signal; });
                def(AbortSignal,'any',function(sigs){ var c=new AbortController(); [].slice.call(sigs).forEach(function(s){ if(s.aborted){ try{ c.abort(s.reason); }catch(e){ c.abort(); } } else s.addEventListener('abort',function(){ try{ c.abort(s.reason); }catch(e){ c.abort(); } }); }); return c.signal; });
              }
            })();
        """.trimIndent()
    }
}
