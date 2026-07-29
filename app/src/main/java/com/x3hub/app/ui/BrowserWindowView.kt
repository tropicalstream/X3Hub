package com.x3hub.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import com.x3hub.app.BuildConfig
import com.x3hub.app.core.web.AdBlock
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

    private val webView = WebView(context)

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

    /** px/second; positive scrolls right/down. Both 0 stops. */
    fun setEdgeScrollVelocity(vx: Float, vy: Float) {
        if (vx == edgeScrollVx && vy == edgeScrollVy) return
        edgeScrollVx = vx
        edgeScrollVy = vy
        webView.evaluateJavascript(
            "window.__x3EdgeScroll && window.__x3EdgeScroll($vx,$vy);", null
        )
    }

    /**
     * A new document has none of our JS state, so the velocity we believe we
     * sent is stale. Without this the first scroll after a navigation is
     * deduped away and the page sits still.
     */
    private fun resetEdgeScrollState() {
        edgeScrollVx = 0f
        edgeScrollVy = 0f
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
    }

    private fun injectImageFit() {
        runCatching { webView.evaluateJavascript(IMAGE_FIT_JS, null) }
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
        // clickable thing the page agent has to reason about.
        requestInterceptor = { req -> AdBlock.intercept(req) }
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
        runCatching {
            @Suppress("DEPRECATION")
            wv.settings.forceDark = WebSettings.FORCE_DARK_ON
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { wv.settings.isAlgorithmicDarkeningAllowed = true }
        }

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

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                currentScale = newScale
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

    /** Give YouTube its sound back — see [MEDIA_AUTOPLAY_JS]. */
    private fun injectMediaAutoplay() {
        if (!autoplayWithSound || mediaHeldForMic) return
        runCatching { webView.evaluateJavascript(MEDIA_AUTOPLAY_JS, null) }
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

    /** Scroll the page directly, for hosts that turn trackpad deltas into
     *  scrolling rather than synthesising a drag. */
    fun scrollPageBy(dx: Int, dy: Int) {
        webView.scrollBy(dx, dy)
    }

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
    fun loadUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val full = if (trimmed.contains("://")) trimmed else "https://$trimmed"
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
    fun containsScreenPoint(screenX: Float, screenY: Float): Boolean {
        if (visibility != VISIBLE || width == 0) return false
        getLocationOnScreen(locationScratch)
        val left = locationScratch[0]
        val top = locationScratch[1]
        return screenX >= left && screenX < left + width &&
            screenY >= top && screenY < top + height
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
                        tx:null, ty:null, still:0, lastX:null, lastY:null };

              // Per-axis, because the box that scrolls sideways is often not
              // the one that scrolls down — a wide table inside an article
              // is the usual case.
              function pick(horiz){
                try {
                  var best = null, bestArea = 0, all = document.querySelectorAll('*');
                  for (var i = 0; i < all.length && i < 4000; i++){
                    var e = all[i];
                    var s = getComputedStyle(e);
                    if (!/auto|scroll/.test(horiz ? s.overflowX : s.overflowY)) continue;
                    var can = horiz ? (e.scrollWidth > e.clientWidth + 4)
                                    : (e.scrollHeight > e.clientHeight + 4);
                    if (!can) continue;
                    var r = e.getBoundingClientRect(), a = r.width * r.height;
                    if (a > bestArea){ bestArea = a; best = e; }
                  }
                  // Only trust an inner pane when the document itself cannot
                  // scroll on that axis — same rule the agent's scroll uses.
                  var docCan = horiz
                    ? (document.documentElement.scrollWidth > innerWidth + 4)
                    : (document.documentElement.scrollHeight > innerHeight + 4);
                  // Sideways scrollers are shapes, not panes — a wide table or
                  // a carousel is a strip that covers far less of the viewport
                  // than a vertical content pane does. Holding horizontal to
                  // the vertical threshold means never finding them.
                  var minArea = horiz ? 0.10 : 0.40;
                  if (best && bestArea > innerWidth * innerHeight * minArea && !docCan) return best;
                } catch (err) {}
                return window;
              }
              function posOf(t, horiz){
                if (!t) return 0;
                if (t === window) return horiz
                  ? (window.pageXOffset || document.documentElement.scrollLeft || 0)
                  : (window.pageYOffset || document.documentElement.scrollTop || 0);
                return horiz ? t.scrollLeft : t.scrollTop;
              }
              function apply(t, dx, dy){
                if (t === window) window.scrollBy(dx, dy);
                else { if (dx) t.scrollLeft += dx; if (dy) t.scrollTop += dy; }
              }
              function step(ts){
                if (!E.vx && !E.vy){ E.raf = 0; return; }
                if (!E.last) E.last = ts;
                var dt = ts - E.last; E.last = ts;
                if (dt > 64) dt = 64;
                if (dt < 0) dt = 0;
                E.accX += E.vx * dt / 1000;
                E.accY += E.vy * dt / 1000;
                var wx = E.accX > 0 ? Math.floor(E.accX) : Math.ceil(E.accX);
                var wy = E.accY > 0 ? Math.floor(E.accY) : Math.ceil(E.accY);
                if (wx){ E.accX -= wx; apply(E.tx, wx, 0); }
                if (wy){ E.accY -= wy; apply(E.ty, 0, wy); }
                var px = posOf(E.tx, true), py = posOf(E.ty, false);
                // Only an axis that is actually being driven counts as
                // progress, or a purely vertical scroll would look stuck
                // because x never changes.
                var moved = (E.vx && (E.lastX === null || px !== E.lastX)) ||
                            (E.vy && (E.lastY === null || py !== E.lastY));
                if (!moved){ if (++E.still > 40){ E.vx = 0; E.vy = 0; E.raf = 0; return; } }
                else E.still = 0;
                E.lastX = px; E.lastY = py;
                E.raf = requestAnimationFrame(step);
              }
              window.__x3EdgeScroll = function(vx, vy){
                vy = vy || 0;
                E.vx = vx || 0; E.vy = vy;
                if (!E.vx && !E.vy){
                  if (E.raf) cancelAnimationFrame(E.raf);
                  E.raf = 0; E.tx = null; E.ty = null;
                  E.accX = 0; E.accY = 0; E.lastX = null; E.lastY = null; E.still = 0;
                  return;
                }
                // Resolve each axis lazily: the DOM sweep is the expensive
                // part and a vertical-only scroll must not pay for it twice.
                if (E.vx && !E.tx){ E.tx = pick(true); E.lastX = null; E.still = 0; }
                if (E.vy && !E.ty){ E.ty = pick(false); E.lastY = null; E.still = 0; }
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
