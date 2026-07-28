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

    /** Current page URL, or null before the first load. */
    val currentUrl: String? get() = webView.url

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

    private fun injectInputHooks() {
        runCatching { webView.evaluateJavascript(PageInputBridge.JS, null) }
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
                injectInputHooks()
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
                injectInputHooks()
                restoreScrollAfterResize()
                if (BuildConfig.DEBUG) {
                    view?.evaluateJavascript(
                        "JSON.stringify({iw:innerWidth,sw:document.documentElement.scrollWidth," +
                            "dpr:devicePixelRatio,vs:(visualViewport?visualViewport.scale:-1)})"
                    ) { Log.i(TAG, "viewport $it (view=${width}x$height)") }
                }
                runCatching { CookieManager.getInstance().flush() }
                onPageFinishedListener?.invoke(url)
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

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    /** One single click on the window. Idempotent. */
    fun activate() {
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
        if (on) isActive = false
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
        wv.evaluateJavascript("(window.scrollY|0)") { value ->
            pendingScrollCssY = value?.trim()?.toFloatOrNull()?.toInt()
            // loadUrl, NOT reload: a reload restores the page's previous
            // zoom state and the new initial scale is ignored, which is
            // exactly the bug this exists to fix. A fresh navigation is
            // what makes the engine adopt it.
            wv.loadUrl(url)
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
