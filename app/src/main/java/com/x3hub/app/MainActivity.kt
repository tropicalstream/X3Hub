package com.x3hub.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.x3hub.app.core.bridge.CameraStateBridge
import com.x3hub.app.core.bridge.ChatCardBridge
import com.x3hub.app.core.bridge.HudStateBridge
import com.x3hub.app.core.bridge.VoiceServiceApi
import androidx.lifecycle.lifecycleScope
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.x3hub.app.core.tools.BrowserTool
import com.x3hub.app.ui.BrowserWindowView
import com.x3hub.app.ui.CustomKeyboardView
import com.x3hub.app.ui.DimController
import com.x3hub.app.core.agent.AgentSpeech
import com.x3hub.app.core.agent.AgentTaskBridge
import com.x3hub.app.core.agent.AgentVoice
import com.x3hub.app.core.web.AdBlock
import com.x3hub.app.core.agent.PageAgentController
import com.x3hub.app.core.agent.PageCommands
import com.x3hub.app.ui.HubSettingsOverlay
import com.x3hub.app.ui.HudPinBoardController

/**
 * X3Gemini — a Gemini Live voice HUD for the RayNeo X3 Pro, carved out
 * of TapInsight's unipanel branch. One Activity, one Service, no
 * browser: under the HUD strip is black space (transparent on the
 * waveguide) shared by the pin board and the camera preview.
 *
 * Controls:
 *   • Right trackpad (cyttsp5_mt) — moves the cursor; the physical tap
 *     arrives as a KEY (KEYCODE_BUTTON_A / DPAD_CENTER). Single tap =
 *     click at cursor, and on empty space while idle it starts a
 *     session (tap anywhere to talk). DOUBLE tap = toggle the Gemini
 *     session (or pin modify mode when the cursor rests on a pin).
 *   • Left arm (cyttsp6_mt) — SINGLE tap toggles the camera preview.
 *   • Avatar orb tap — also toggles the Gemini session.
 */
class MainActivity : AppCompatActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())

    // ── Service binding ───────────────────────────────────────────────
    @Volatile private var voiceServiceApi: VoiceServiceApi? = null
    @Volatile private var pendingVoiceActivateUntilMs: Long = 0L
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            voiceServiceApi = service as? VoiceServiceApi
            Log.i(TAG, "Voice service connected (api=${voiceServiceApi != null})")
            installCameraPreviewProvider()
            if (SystemClock.uptimeMillis() < pendingVoiceActivateUntilMs) {
                pendingVoiceActivateUntilMs = 0L
                runCatching { voiceServiceApi?.activateVoice() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Voice service disconnected")
            voiceServiceApi = null
        }
    }

    // ── Cursor state ──────────────────────────────────────────────────
    private var cursorX = 320f
    private var cursorY = 240f
    private var isCursorVisible = false
    private var droppedFirstDelta = false
    private val cursorGain = 0.45f
    private val hideCursorRunnable = Runnable { setCursorVisible(false) }

    // ── Right-arm tap state ──────────────────────────────────────────
    // The guide says the right-arm click is a KEY event (KEYCODE_BUTTON_A
    // / DPAD_CENTER); on this unit it can instead arrive as a TOUCH tap
    // on cyttsp5 (down+up). Both paths funnel into onRightArmTapUp(),
    // which dedupes so one physical tap delivered on both paths counts
    // once.
    private var rightArmKeyDownMs: Long = 0L
    private var rightArmKeyTracking: Boolean = false
    private var rightArmKeyLastTapUpMs: Long = 0L
    private var pendingSingleTapClick: Runnable? = null
    /** How many taps deep the current chain is: 1 single, 2 double, 3 triple. */
    private var rightArmTapStreak = 0
    private var hubSettingsOverlay: HubSettingsOverlay? = null

    /**
     * One page agent per window. Keyed by the window instance because the
     * agent's state — the running task, the hop budget, the pending LLM
     * fetches — belongs to a document, and each window has its own.
     */
    private val pageAgents = HashMap<BrowserWindowView, PageAgentController>()

    // ── Spoken task capture for the page agent ───────────────────────
    private val agentRecorder by lazy { AgentVoice.Recorder(applicationContext) }
    private var agentTaskWindow: BrowserWindowView? = null
    private var geminiWasLiveBeforeTask = false
    private val autoStopAgentTask = Runnable { finishAgentTask() }

    /**
     * Open the mic for a page-agent task.
     *
     * The Gemini Live session holds an AudioRecord for as long as it is up,
     * and MediaRecorder cannot have the mic at the same time — so the
     * session is stood down for the capture and the wearer is told why it
     * went quiet. SmartView never had to share the microphone with anything.
     */
    private fun startAgentTask(window: BrowserWindowView) {
        if (!AgentVoice.hasKey(applicationContext)) {
            showNotice("No Groq key for speech — triple-tap for settings.")
            return
        }
        geminiWasLiveBeforeTask =
            HudStateBridge.current().phase != HudStateBridge.VoicePhase.IDLE
        if (geminiWasLiveBeforeTask) exitGeminiFully()
        AgentSpeech.stop()
        // The mic does not free instantly after the session lets go.
        uiHandler.postDelayed({
            if (!agentRecorder.start()) {
                showNotice("Microphone unavailable.")
                return@postDelayed
            }
            agentTaskWindow = window
            if (!window.isActive) {
                hudPinBoardController?.browserWindows()?.forEach {
                    if (it !== window) it.deactivate()
                }
                window.activate()
            }
            showNotice("Listening — double-tap again when you're done.")
            uiHandler.removeCallbacks(autoStopAgentTask)
            uiHandler.postDelayed(autoStopAgentTask, AgentVoice.MAX_RECORD_MS)
        }, if (geminiWasLiveBeforeTask) 450L else 0L)
    }

    /** Second double-tap (or the auto-stop): transcribe and dispatch. */
    private fun finishAgentTask() {
        uiHandler.removeCallbacks(autoStopAgentTask)
        val window = agentTaskWindow
        agentTaskWindow = null
        val audio = agentRecorder.stop()
        if (audio == null || window == null) {
            showNotice("Didn't catch that.")
            return
        }
        showNotice("Transcribing…")
        AgentVoice.transcribe(applicationContext, audio) { text, error ->
            if (text == null) {
                showNotice(error ?: "Didn't catch that.")
                return@transcribe
            }
            dispatchPageCommand(text, window)
        }
    }

    /**
     * Route a spoken instruction. Browsing, scrolling and plain search are
     * done by the app; only what is genuinely the agent's reaches it.
     *
     * This is the difference between "it opened archive.org but never played
     * anything" and it working: the agent has no navigate tool and its own
     * prompt tells it to stay on the page, so a browsing instruction sent to
     * it costs a slow model round trip and then fails.
     */
    private fun dispatchPageCommand(text: String, window: BrowserWindowView) {
        when (val outcome = PageCommands.route(text, window)) {
            is PageCommands.Outcome.Handled -> {
                showNotice(outcome.notice)
                releasePageCommandToCursor(window)
            }
            is PageCommands.Outcome.StopAgent -> {
                pageAgents[window]?.stop()
                AgentSpeech.stop()
                showNotice("Stopped.")
            }
            is PageCommands.Outcome.SearchInPage -> searchInPage(outcome.query, window, retried = false)
            is PageCommands.Outcome.ForAgent -> {
                showNotice("Agent: ${outcome.task.take(46)}")
                agentFor(window).run(outcome.task)
            }
        }
    }

    /**
     * A native page command is a finished, one-shot interaction. Leaving the
     * window ACTIVE after it completes makes the next physical pad slide a
     * page drag and looks exactly like a dead pointer. If a programmatic
     * in-page search briefly raised our keyboard, use its normal dismissal
     * path; otherwise return the touchpad directly to the hub cursor.
     */
    private fun releasePageCommandToCursor(window: BrowserWindowView) {
        if (keyboardOwner === window && keyboardView?.visibility == View.VISIBLE) {
            hideOnScreenKeyboard()
            return
        }
        window.deactivate()
        stopEdgeScroll()
        setCursorVisible(true)
        Log.i(TAG, "Page command complete — browser released and cursor owns touchpad")
    }

    /** Drive the page's own search box; fall back to the web if it has none. */
    private fun searchInPage(query: String, window: BrowserWindowView, retried: Boolean) {
        // This is about to call el.focus() on the page's own search box, and a
        // programmatic focus raises the system IME. We know the focus is ours,
        // so say so BEFORE it happens rather than reacting once the keyboard
        // is already across both eyes.
        suppressImeFor(2500L)
        window.evaluateJavascript(PageCommands.searchInPageJs(query)) { result ->
            when {
                result != null && result.contains("ok") -> {
                    showNotice("Searching this site for ${query.take(32)}")
                    releasePageCommandToCursor(window)
                }
                // The box was collapsed behind a magnifier and we just clicked
                // it open; give the DOM a moment, then fill it in.
                result != null && result.contains("opened") && !retried ->
                    uiHandler.postDelayed({ searchInPage(query, window, retried = true) }, 500L)
                else -> {
                    // No search box here — a web search beats doing nothing.
                    window.loadUrl(PageCommands.searchUrl(query, google = false))
                    showNotice("Searching the web for ${query.take(32)}")
                    releasePageCommandToCursor(window)
                }
            }
        }
    }

    // ── On-screen keyboard (the system IME is unusable on this display) ──
    private var keyboardView: CustomKeyboardView? = null
    private var keyboardOwner: BrowserWindowView? = null
    private var suppressImeUntilMs = 0L
    private val imeSuppressor = Handler(Looper.getMainLooper())
    private val keyboardHideRunnable = Runnable { hideOnScreenKeyboard() }

    /**
     * Hold the system IME down for a while.
     *
     * One hide() is not enough: the IME is raised by the WebView's own input
     * connection AFTER the focus that triggered it, so a single call races
     * and loses. Ticking for the duration is what actually keeps it off the
     * display — and on this hardware it must be kept off, because it renders
     * into the raw 1280x480 framebuffer, spans both eyes at the wrong scale,
     * and the wearer cannot dismiss it by looking at it.
     */
    private fun suppressImeFor(durationMs: Long) {
        suppressImeUntilMs = SystemClock.uptimeMillis() + durationMs
        imeSuppressor.removeCallbacksAndMessages(null)
        fun tick() {
            hideSystemKeyboard()
            if (SystemClock.uptimeMillis() < suppressImeUntilMs) {
                imeSuppressor.postDelayed({ tick() }, 90L)
            }
        }
        tick()
    }

    private fun hideSystemKeyboard() {
        val root = findViewById<View?>(R.id.mainContainer) ?: return
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(root.windowToken, 0)
        }
    }

    private fun showOnScreenKeyboard(window: BrowserWindowView) {
        stopEdgeScroll()
        suppressImeFor(1500L)
        keyboardOwner = window
        val overlay = findViewById<FrameLayout?>(R.id.unipanelOverlay) ?: return
        val kb = keyboardView ?: CustomKeyboardView(this).also { v ->
            v.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            v.setOnKeyboardActionListener(keyboardActions)
            overlay.addView(v)
            keyboardView = v
        }
        kb.visibility = View.VISIBLE
        kb.bringToFront()
        setCursorVisible(true)
        resetKeyboardHideTimer()
        Log.i(TAG, "Keyboard shown — cursor owns touchpad while page field stays focused")
    }

    /** Idle keyboards are just lost display; 20s of no key is idle. */
    private fun resetKeyboardHideTimer() {
        uiHandler.removeCallbacks(keyboardHideRunnable)
        uiHandler.postDelayed(keyboardHideRunnable, 20_000L)
    }

    private fun hideOnScreenKeyboard() {
        uiHandler.removeCallbacks(keyboardHideRunnable)
        val owner = keyboardOwner
        keyboardView?.visibility = View.GONE
        keyboardOwner = null
        owner?.defocusField()

        // The window deliberately stays ACTIVE: the cursor is free either
        // way now, and keeping it active means the wearer can read what they
        // just searched for by dropping straight to the bottom edge instead
        // of tapping the window awake again first.
        setCursorVisible(true)
        updateEdgeScroll()
        Log.i(TAG, "Keyboard hidden")
    }

    private val keyboardActions = object : CustomKeyboardView.OnKeyboardActionListener {
        override fun onKeyPressed(key: String) {
            resetKeyboardHideTimer(); suppressImeFor(900L); keyboardOwner?.insertText(key)
        }
        override fun onBackspacePressed() {
            resetKeyboardHideTimer(); suppressImeFor(900L); keyboardOwner?.backspace()
        }
        override fun onEnterPressed() {
            suppressImeFor(1500L); keyboardOwner?.submitField(); hideOnScreenKeyboard()
        }
        override fun onHideKeyboard() = hideOnScreenKeyboard()
        override fun onClearPressed() { resetKeyboardHideTimer(); keyboardOwner?.clearField() }
        override fun onMoveCursorLeft() { resetKeyboardHideTimer(); keyboardOwner?.moveCaret(-1) }
        override fun onMoveCursorRight() { resetKeyboardHideTimer(); keyboardOwner?.moveCaret(1) }
        override fun onMicrophonePressed() {
            // Dictation into the field, using the same capture the agent uses.
            val w = keyboardOwner ?: return
            resetKeyboardHideTimer()
            startFieldDictation(w)
        }
    }

    /** Speak into the focused field rather than tapping it out letter by letter. */
    private fun startFieldDictation(window: BrowserWindowView) {
        if (agentRecorder.isRecording) { finishFieldDictation(window); return }
        if (!AgentVoice.hasKey(applicationContext)) {
            showNotice("No Groq key for speech — triple-tap for settings.")
            return
        }
        if (HudStateBridge.current().phase != HudStateBridge.VoicePhase.IDLE) exitGeminiFully()
        uiHandler.postDelayed({
            if (agentRecorder.start()) {
                showNotice("Dictating — press the mic again when done.")
                uiHandler.postDelayed({ finishFieldDictation(window) }, AgentVoice.MAX_RECORD_MS)
            } else showNotice("Microphone unavailable.")
        }, 300L)
    }

    private fun finishFieldDictation(window: BrowserWindowView) {
        val audio = agentRecorder.stop() ?: run { showNotice("Didn't catch that."); return }
        AgentVoice.transcribe(applicationContext, audio) { text, error ->
            if (text == null) showNotice(error ?: "Didn't catch that.")
            else { window.insertText(text); resetKeyboardHideTimer() }
        }
    }

    private fun agentFor(window: BrowserWindowView): PageAgentController =
        pageAgents.getOrPut(window) {
            PageAgentController(applicationContext, window) { msg -> showNotice(msg) }
        }
    private var dimController: DimController? = null
    /** The window currently in modify mode, so a swipe knows what to resize. */
    private var modifyingWindow: BrowserWindowView? = null
    private var lastRightArmTapUpAcceptedMs: Long = 0L
    private var lastRightArmTapSource: TapSource? = null
    private var cursorAtTouchDownX = 0f
    private var cursorAtTouchDownY = 0f

    // Right-arm TOUCH tap detection (cyttsp5 down+up with little movement).
    private var rightArmTouchDownMs: Long = 0L
    private var rightArmTouchDownX: Float = 0f
    private var rightArmTouchDownY: Float = 0f
    private var rightArmTouchTracking: Boolean = false
    private var rightArmTouchMoved: Boolean = false

    // ── Left-arm double-tap state (touch path on cyttsp6_mt) ─────────
    private var leftArmTapDownTimeMs: Long = 0L
    private var leftArmTapDownX = 0f
    private var leftArmTapDownY = 0f
    private var leftArmTapTracking = false
    private var leftArmTapMovedTooFar = false

    // ── HUD subscriptions / receivers ─────────────────────────────────
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hudStateSubscription: AutoCloseable? = null
    private var chatCardSubscription: AutoCloseable? = null
    private var cameraStateSubscription: AutoCloseable? = null
    private var hudPinBoardController: HudPinBoardController? = null

    // ── Chat card state (same display + timeout behaviour as TapInsight:
    //    persists until replaced or dismissed; tap = expand/collapse) ──
    @Volatile private var assistantCardDismissedThroughMs: Long = 0L

    private var noticeClearRunnable: Runnable? = null

    // Tracks the last voice phase so we can detect a session END and hide
    // the chat card a short while after (rather than persisting it).
    private var lastVoicePhase: HudStateBridge.VoicePhase = HudStateBridge.VoicePhase.IDLE
    private val hideChatCardRunnable = Runnable {
        dismissAssistantCard()
    }

    /** Hide only the currently displayed reply. A later reply has a newer
     * timestamp and can still appear normally. */
    private fun dismissAssistantCard() {
        val scroll = findViewById<View?>(R.id.unipanelMiniCardScroll) ?: return
        if (scroll.visibility != View.VISIBLE) return
        val latestAssistantTimestamp = ChatCardBridge.current()
            .lastOrNull { !it.fromUser && it.text.isNotBlank() }
            ?.timestampMs
            ?: System.currentTimeMillis()
        assistantCardDismissedThroughMs = maxOf(
            assistantCardDismissedThroughMs,
            latestAssistantTimestamp
        )
        scroll.visibility = View.GONE
        findViewById<TextView?>(R.id.unipanelMiniCard1)?.text = ""
    }

    /**
     * Force 1dp == 1px for the 640×480 logical viewport. Set density
     * exactly ONCE via createConfigurationContext and never touch
     * widthPixels (gotcha #26 — metric mutation compounds).
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.densityDpi = DisplayMetrics.DENSITY_MEDIUM
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        setContentView(R.layout.activity_main)

        requestRuntimePermissions()

        startHudClockTicker()
        startBatteryReceiver()
        startNetworkObserver()
        setupVoiceOrb()
        startHudStateObserver()
        setupChatCard()
        startCameraStateObserver()
        setupHudPinBoard()

        bindVoiceService()

        // Experimental beta build — shown on every launch until this reaches a
        // stable release. Full warning lives in README.md; this is the on-glasses
        // reminder for testers who never open GitHub.
        uiHandler.postDelayed(
            { showNotice("⚠️ Experimental beta — not for critical/urgent use") },
            1_200L
        )
    }

    /**
     * Debug-only door to the browser tool, so a window can be opened without
     * speaking to the assistant:
     *
     *   adb shell am broadcast -a com.x3hub.app.DEBUG_OPEN_BROWSER \
     *       -e url https://example.com -p com.x3hub.app
     *
     * Voice is the real path, but it cannot be driven over adb — which means
     * without this, every test of the window, its activation, its resize and
     * its exit would need someone wearing the glasses and talking. Registered
     * only in debug builds so it is not an input into the shipped app.
     */
    private var debugBrowserReceiver: BroadcastReceiver? = null

    private fun registerDebugBrowserReceiver() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // `-e at window` parks the cursor on the first browser
                // window instead of a screen coordinate. The pin board flows
                // pins around whatever else is on the HUD, so a window's
                // position is not knowable from outside — an assistant card
                // appearing is enough to move it — and a test that aims at a
                // fixed point silently starts clicking empty space.
                intent?.getStringExtra("cursor")?.let { spec ->
                    placeCursorForDebug(spec)
                    return
                }
                intent?.getStringExtra("zoom")?.toFloatOrNull()?.let { f ->
                    hudPinBoardController?.browserWindows()?.firstOrNull()
                        ?.debugZoomBy(f)
                    return
                }
                intent?.getStringExtra("task")?.let { t ->
                    // Exercises the dispatch half without a microphone: the
                    // capture half needs real speech in the room, which a
                    // scripted run cannot produce.
                    val w = hudPinBoardController?.browserWindows()
                        ?.firstOrNull { it.isActive }
                        ?: hudPinBoardController?.browserWindows()?.firstOrNull()
                    Log.i(TAG, "DEBUG task window=${w != null}: $t")
                    if (w == null) showNotice("No window open.") else dispatchPageCommand(t, w)
                    return
                }
                if (intent?.getStringExtra("adblock") != null) {
                    Log.i(TAG, "AdBlock ready=${AdBlock.ready()} domains=${AdBlock.size()} " +
                        "blockedThisPage=${AdBlock.blockCount()} err=${AdBlock.loadError}")
                    return
                }
                intent?.getStringExtra("say")?.let { text ->
                    Log.i(TAG, "DEBUG say: ${text.take(80)}")
                    if (HudStateBridge.current().phase ==
                        HudStateBridge.VoicePhase.IDLE
                    ) {
                        toggleGeminiSession()
                        uiHandler.postDelayed(
                            { runCatching { voiceServiceApi?.sendDebugText(text) } }, 2500L
                        )
                    } else {
                        runCatching { voiceServiceApi?.sendDebugText(text) }
                    }
                    return
                }
                intent?.getStringExtra("voice")?.let { want ->
                    // Starting a session normally means tapping empty space,
                    // and "empty" depends on what is on the board — with the
                    // camera preview up there is no empty space at all. A
                    // scripted sequence needs a door that does not move.
                    val idle = HudStateBridge.current().phase ==
                        HudStateBridge.VoicePhase.IDLE
                    val target = when (want.lowercase()) {
                        "on" -> true
                        "off" -> false
                        else -> idle
                    }
                    Log.i(TAG, "DEBUG voice want=$want idle=$idle -> $target")
                    if (target && idle) toggleGeminiSession()
                    else if (!target && !idle) exitGeminiFully()
                    return
                }
                intent?.getStringExtra("camera")?.let { want ->
                    // The left pad is the real camera gesture, but injected
                    // touches only ever carry the name "Virtual" and the debug
                    // relaxation maps those to the RIGHT pad — so there is no
                    // way to reach the camera from adb without this.
                    //
                    // on/off rather than a bare toggle: a blind toggle makes a
                    // scripted sequence depend on what the last run left
                    // behind, and a second "camera on" silently turns it off.
                    val preview = findViewById<View?>(R.id.unipanelCameraPreviewFrame)
                    val isOn = preview?.visibility == View.VISIBLE
                    val target = when (want.lowercase()) {
                        "on" -> true
                        "off" -> false
                        else -> !isOn
                    }
                    Log.i(TAG, "DEBUG camera want=$want isOn=$isOn -> $target")
                    if (target != isOn) toggleCamera()
                    return
                }
                intent?.getStringExtra("js")?.let { js ->
                    hudPinBoardController?.browserWindows()?.firstOrNull()
                        ?.debugEval(js)
                    return
                }
                val url = intent?.getStringExtra("url")
                val query = intent?.getStringExtra("query")
                Log.i(TAG, "DEBUG_OPEN_BROWSER url=$url query=$query")
                lifecycleScope.launch {
                    val args = buildMap {
                        if (!url.isNullOrBlank()) put("url", url)
                        if (!query.isNullOrBlank()) put("query", query)
                    }
                    val result = BrowserTool(applicationContext).execute(args)
                    Log.i(TAG, "DEBUG_OPEN_BROWSER -> ${result.getOrElse { "failed: ${it.message}" }}")
                }
            }
        }
        debugBrowserReceiver = receiver
        val filter = IntentFilter("com.x3hub.app.DEBUG_OPEN_BROWSER")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause each page individually. WebView.pauseTimers() would be the
        // obvious call and is exactly wrong here: it is process-global, so one
        // window pausing would freeze every other window's JavaScript too.
        stopEdgeScroll()
        hudPinBoardController?.browserWindows()?.forEach { it.onHostPause() }
    }

    override fun onResume() {
        super.onResume()
        hudPinBoardController?.browserWindows()?.forEach { it.onHostResume() }
    }

    override fun onDestroy() {
        // WebViews hold a lot and do not go quietly: destroy them explicitly
        // rather than trusting the activity teardown to collect them.
        hudPinBoardController?.browserWindows()?.forEach { it.destroy() }
        modifyingWindow = null
        uiHandler.removeCallbacksAndMessages(null)
        runCatching { debugBrowserReceiver?.let { unregisterReceiver(it) } }
        debugBrowserReceiver = null
        runCatching { batteryReceiver?.let { unregisterReceiver(it) } }
        batteryReceiver = null
        runCatching {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        hudStateSubscription?.runCatching { close() }
        chatCardSubscription?.runCatching { close() }
        cameraStateSubscription?.runCatching { close() }
        hudPinBoardController?.stop()
        hudPinBoardController = null
        if (serviceBound) runCatching { unbindService(serviceConnection) }
        serviceBound = false
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun bindVoiceService() {
        val intent = Intent().setClassName(packageName, VoiceServiceApi.SERVICE_FQN)
        serviceBound = runCatching {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        Log.i(TAG, "bindVoiceService: bound=$serviceBound")
    }

    /** Hand the PreviewView's SurfaceProvider to the Service. COMPATIBLE
     *  (TextureView-backed) is REQUIRED: a SurfaceView draws to a layer
     *  BinocularSbsLayout's dispatchDraw can't duplicate (gotcha #26b). */
    private fun installCameraPreviewProvider() {
        val previewView = findViewById<androidx.camera.view.PreviewView?>(
            R.id.unipanelCameraPreviewView
        ) ?: return
        previewView.implementationMode =
            androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        runCatching {
            voiceServiceApi?.setCameraPreviewSurfaceProvider(previewView.surfaceProvider)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // HUD strip: clock / date / battery / network / G badge
    // ────────────────────────────────────────────────────────────────

    private fun startHudClockTicker() {
        val timeTv = findViewById<TextView?>(R.id.unipanelHudTime) ?: return
        val dateTv = findViewById<TextView?>(R.id.unipanelHudDate)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val dateFmt = java.text.SimpleDateFormat("EEE · MMM d", java.util.Locale.US)
        val ticker = object : Runnable {
            override fun run() {
                try {
                    val now = java.util.Date()
                    timeTv.text = timeFmt.format(now)
                    dateTv?.text = dateFmt.format(now)
                } catch (_: Exception) {}
                uiHandler.postDelayed(this, 30_000L)
            }
        }
        uiHandler.post(ticker)
    }

    private fun startBatteryReceiver() {
        val tv = findViewById<TextView?>(R.id.unipanelHudBattery) ?: return
        val render = { intent: Intent? ->
            try {
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    val chargePrefix = if (charging) "⚡ " else ""
                    tv.text = if (pct >= 0) "$chargePrefix$pct%" else "—%"
                }
            } catch (_: Exception) {}
        }
        // Seed with the sticky broadcast (null receiver returns it directly).
        runCatching {
            render(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                render(intent)
            }
        }
        runCatching {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = receiver
        }
    }

    /** Compact default-network indicator: Wi-Fi / cellular / offline. */
    private fun startNetworkObserver() {
        val tv = findViewById<TextView?>(R.id.unipanelHudNetwork) ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun render() {
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            val (text, color) = when {
                caps == null -> "⊘" to 0xFFFF5252.toInt()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    "Wi-Fi" to 0xFF9FE6B0.toInt()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    "Cell" to 0xFF7FDBFF.toInt()
                else -> "Net" to 0xFF9FE6B0.toInt()
            }
            uiHandler.post {
                tv.text = text
                tv.setTextColor(color)
            }
        }

        render()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = render()
            override fun onLost(network: Network) = render()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = render()
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Voice orb + HUD state (G badge tint, orb glow, notice line)
    // ────────────────────────────────────────────────────────────────

    private fun setupVoiceOrb() {
        val orb = findViewById<View?>(R.id.unipanelVoiceOrb) ?: return
        orb.setOnClickListener { toggleGeminiSession() }
        // Clip the avatar image to a clean circle so it always reads as a
        // round orb (and never a clipped square), matching TapInsight.
        findViewById<ImageView?>(R.id.unipanelVoiceOrbImage)?.apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
    }

    private fun startHudStateObserver() {
        hudStateSubscription?.runCatching { close() }
        hudStateSubscription = HudStateBridge.observe { state ->
            uiHandler.post {
                renderAiBadge(state)
                renderVoiceOrb(state)
                renderNotice(state)
                handleVoicePhaseChange(state.phase)
            }
        }
    }

    /**
     * When a session ENDS (phase → IDLE), keep the last chat card up for
     * [CHAT_CARD_LINGER_MS], then hide it. When a session is active, cancel
     * any pending hide so the card stays. Transition-driven so a final card
     * committed at end can't cancel the scheduled hide.
     */
    private fun handleVoicePhaseChange(phase: HudStateBridge.VoicePhase) {
        val wasActive = lastVoicePhase != HudStateBridge.VoicePhase.IDLE
        if (phase == HudStateBridge.VoicePhase.IDLE) {
            if (wasActive) {
                uiHandler.removeCallbacks(hideChatCardRunnable)
                uiHandler.postDelayed(hideChatCardRunnable, CHAT_CARD_LINGER_MS)
            }
        } else {
            uiHandler.removeCallbacks(hideChatCardRunnable)
        }
        lastVoicePhase = phase
    }

    private fun renderAiBadge(state: HudStateBridge.State) {
        val badge = findViewById<TextView?>(R.id.unipanelHudAiBadge) ?: return
        // The "G" reflects Gemini API health, not turn progress: green
        // whenever usable (including IDLE), amber connecting, red error.
        val tint = when (state.connection) {
            HudStateBridge.ConnectionStatus.CONNECTING -> 0xFFFFB347.toInt()
            HudStateBridge.ConnectionStatus.DEGRADED,
            HudStateBridge.ConnectionStatus.ERROR -> 0xFFE57373.toInt()
            else -> 0xFF34D399.toInt()
        }
        runCatching {
            badge.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        }
    }

    /** Orb glow: thin dim ring idle, bold red while listening, bold
     *  green while Gemini speaks; breathes with the oscilloscope level. */
    private fun renderVoiceOrb(state: HudStateBridge.State) {
        val glow = findViewById<View?>(R.id.unipanelVoiceOrbGlow) ?: return
        val phase = state.phase
        val idle = phase == HudStateBridge.VoicePhase.IDLE
        val listening = phase == HudStateBridge.VoicePhase.LISTENING ||
            phase == HudStateBridge.VoicePhase.FOLLOW_UP
        glow.setBackgroundResource(
            when {
                idle -> R.drawable.bg_unipanel_orb_ring_idle
                listening -> R.drawable.bg_unipanel_orb_ring_red
                else -> R.drawable.bg_unipanel_orb_ring_green
            }
        )
        val level = state.oscilloscopeLevel.coerceIn(0f, 1f)
        if (idle) {
            glow.alpha = 0.55f
            glow.scaleX = 1f
            glow.scaleY = 1f
        } else {
            glow.alpha = (0.85f + level * 0.15f).coerceIn(0f, 1f)
            val scale = 1f + level * 0.22f
            glow.scaleX = scale
            glow.scaleY = scale
        }
    }

    /** Transient one-line notice under the HUD strip; auto-clears. */
    private fun renderNotice(state: HudStateBridge.State) {
        val tv = findViewById<TextView?>(R.id.unipanelHudNotice) ?: return
        val text = state.notification?.trim().takeUnless { it.isNullOrBlank() }
        noticeClearRunnable?.let { uiHandler.removeCallbacks(it) }
        noticeClearRunnable = null
        if (text == null) {
            tv.visibility = View.GONE
            tv.text = ""
            return
        }
        tv.text = text
        tv.visibility = View.VISIBLE
        val clear = Runnable {
            noticeClearRunnable = null
            if (HudStateBridge.current().notification?.trim() == text) {
                HudStateBridge.update { it.copy(notification = null) }
            } else {
                tv.visibility = View.GONE
            }
        }
        noticeClearRunnable = clear
        uiHandler.postDelayed(clear, NOTICE_DISPLAY_MS)
    }

    /** Convenience for pin-board toasts etc. */
    private fun showNotice(msg: String) {
        HudStateBridge.update { it.copy(notification = msg) }
    }

    // ────────────────────────────────────────────────────────────────
    // Chat card (same display + timeout behaviour as TapInsight)
    // ────────────────────────────────────────────────────────────────

    private fun setupChatCard() {
        val card = findViewById<TextView?>(R.id.unipanelMiniCard1) ?: return
        val scroll = findViewById<View?>(R.id.unipanelMiniCardScroll) ?: return
        // The card is read-only — no tap-to-expand. It's an inert surface
        // (the ScrollView background makes the overlay hit-test consume
        // taps on it without doing anything).
        card.isClickable = false

        chatCardSubscription?.runCatching { close() }
        chatCardSubscription = ChatCardBridge.observe { cards ->
            uiHandler.post { renderAssistantCard(card, scroll, cards) }
        }
        scroll.post { repositionAssistantCard() }
    }

    /**
     * Pure render: show the most recent ASSISTANT card, auto-scroll to
     * the newest text as it streams. The card PERSISTS — no auto-hide;
     * it stays until a newer reply replaces it or a right-arm
     * double-tap dismisses it (exitGeminiFully).
     */
    private fun renderAssistantCard(
        card: TextView,
        scroll: View,
        cards: List<ChatCardBridge.Card>
    ) {
        val latestAssistant = cards.lastOrNull { !it.fromUser && it.text.isNotBlank() }
            ?.takeIf { it.timestampMs > assistantCardDismissedThroughMs }
            ?.text
        if (latestAssistant == null) {
            card.text = ""
            scroll.visibility = View.GONE
            return
        }
        card.text = latestAssistant
        scroll.visibility = View.VISIBLE
        repositionAssistantCard()
        // Keep the newest text in view as the reply streams in.
        scroll.post {
            repositionAssistantCard()
            (scroll as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Card lane: centered under the HUD strip, floating above the
     *  camera preview; expanded reader fills most of the viewport. */
    private fun repositionAssistantCard() {
        val cardView = findViewById<View?>(R.id.unipanelMiniCardScroll) ?: return
        val overlay = findViewById<ViewGroup?>(R.id.unipanelOverlay) ?: return
        if (overlay.width <= 0) return

        val width = 300.coerceAtMost(overlay.width - 16)
        val height = 76
        val left = ((overlay.width - width) / 2).coerceAtLeast(8)
        val top = HUD_CONTENT_TOP + 4

        val lp = cardView.layoutParams as? FrameLayout.LayoutParams ?: return
        var changed = false
        if (lp.leftMargin != left) { lp.leftMargin = left; changed = true }
        if (lp.topMargin != top) { lp.topMargin = top; changed = true }
        if (lp.width != width) { lp.width = width; changed = true }
        if (lp.height != height) { lp.height = height; changed = true }
        if (lp.gravity != (Gravity.TOP or Gravity.START)) {
            lp.gravity = Gravity.TOP or Gravity.START
            changed = true
        }
        if (changed) cardView.layoutParams = lp
    }

    // ────────────────────────────────────────────────────────────────
    // Camera preview (4:3, large, centered under the HUD)
    // ────────────────────────────────────────────────────────────────

    private fun startCameraStateObserver() {
        val dot = findViewById<View?>(R.id.unipanelVisionDot)
        val previewFrame = findViewById<View?>(R.id.unipanelCameraPreviewFrame)
        cameraStateSubscription?.runCatching { close() }
        cameraStateSubscription = CameraStateBridge.observe { on ->
            uiHandler.post {
                dot?.visibility = if (on) View.VISIBLE else View.GONE
                previewFrame?.visibility = if (on) View.VISIBLE else View.GONE
                if (on) {
                    repositionCameraPreview()
                    // Opening the camera preview auto-starts Gemini so the
                    // frames have somewhere to go. Guarded to IDLE so opening
                    // the camera mid-session doesn't toggle the session off.
                    if (HudStateBridge.current().phase == HudStateBridge.VoicePhase.IDLE) {
                        Log.i(TAG, "camera opened → auto-activating Gemini")
                        toggleGeminiSession()
                    }
                }
                // The preview is a grid blocker — re-slot pins once it
                // has its final bounds.
                previewFrame?.post { hudPinBoardController?.refreshZone() }
            }
        }
    }

    /**
     * Size the preview to the standard X3 camera proportions (4:3) at
     * the largest size that fits under the HUD strip, centered. All
     * units are logical px (1dp == 1px at DENSITY_MEDIUM).
     */
    private fun repositionCameraPreview() {
        val preview = findViewById<View?>(R.id.unipanelCameraPreviewFrame) ?: return
        if (preview.visibility != View.VISIBLE) return
        val overlay = findViewById<View?>(R.id.unipanelOverlay) ?: return
        if (overlay.width <= 0) return

        val top = HUD_CONTENT_TOP
        var h = (overlay.height - top - 8).coerceAtLeast(96)
        var w = h * 4 / 3
        val maxW = overlay.width - 16
        if (w > maxW) {
            w = maxW
            h = w * 3 / 4
        }
        val left = ((overlay.width - w) / 2).coerceAtLeast(8)

        val lp = preview.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.topMargin != top || lp.leftMargin != left || lp.width != w || lp.height != h) {
            lp.topMargin = top
            lp.leftMargin = left
            lp.marginStart = left
            lp.width = w
            lp.height = h
            preview.layoutParams = lp
        }
        repositionAssistantCard()
    }

    // ────────────────────────────────────────────────────────────────
    // Pin board
    // ────────────────────────────────────────────────────────────────

    private fun setupHudPinBoard() {
        val board = findViewById<FrameLayout?>(R.id.unipanelPinBoard) ?: return
        val controller = HudPinBoardController(
            activity = this,
            board = board,
            uiHandler = uiHandler,
            forceCursorVisible = { setCursorVisible(true) },
            showToast = { msg -> showNotice(msg) }
        )
        hudPinBoardController = controller
        controller.start()
        setupHubOverlays()
        registerDebugBrowserReceiver()
    }

    /**
     * The two x3hub surfaces that sit above everything: dim, and settings.
     *
     * Both hang off the same overlay root the pin board draws into, so they are
     * duplicated to each eye by BinocularSbsLayout without either knowing that
     * two eyes exist.
     */
    private fun setupHubOverlays() {
        val root = findViewById<FrameLayout?>(R.id.unipanelOverlay)
            ?: findViewById<FrameLayout?>(R.id.unipanelPinBoard)
            ?: return

        // Dim hides the display without surrendering anything behind it: the
        // Gemini session, its audio and every browser page keep running, so
        // coming back is a repaint rather than a reload. While dimmed the
        // cursor is pointless, so it is parked.
        dimController = DimController(root) { dimmed ->
            if (dimmed) setCursorVisible(false)
        }

        hudPinBoardController?.onBrowserWindowCreated = { w ->
            agentFor(w)
            w.onPageInputFocus = { showOnScreenKeyboard(w) }
            w.onPageInputBlur = { }   // the hide timer owns dismissal
        }
        AgentTaskBridge.setListener { task ->
            uiHandler.post {
                // The ACTIVE window if there is one, else the only window —
                // saying "play the first result" right after opening a page
                // should not also require clicking it first.
                val c = hudPinBoardController
                val target = c?.browserWindows()?.firstOrNull { it.isActive }
                    ?: c?.browserWindows()?.singleOrNull()
                if (target == null) {
                    showNotice("Open a page first, then give the agent a task.")
                } else {
                    if (!target.isActive) {
                        c?.browserWindows()?.forEach { if (it !== target) it.deactivate() }
                        target.activate()
                    }
                    agentFor(target).run(task)
                }
            }
        }
        hudPinBoardController?.onBrowserWindowReleased = { w ->
            // The agent controller holds the window and a watchdog Runnable;
            // dropping the map entry without destroy() would keep both alive.
            pageAgents.remove(w)?.destroy()
            if (modifyingWindow === w) modifyingWindow = null
            if (edgeWindow === w) stopEdgeScroll()
        }

        hubSettingsOverlay = HubSettingsOverlay(
            activity = this,
            overlayRoot = root,
            uiHandler = uiHandler,
            forceCursorVisible = { setCursorVisible(true) },
            showToast = { msg -> showNotice(msg) },
            onKeyChanged = { slotId, _ ->
                // A key the live session is already using has changed under it.
                // Say so rather than silently carrying on with the old one —
                // the wearer has no other way to tell whether it took.
                Log.i(TAG, "settings: key '$slotId' changed")
                showNotice("Saved. Restart the session to use the new key.")
            }
        )
    }

    // ────────────────────────────────────────────────────────────────
    // Gemini session toggle + full exit
    // ────────────────────────────────────────────────────────────────

    private fun toggleGeminiSession() {
        val api = voiceServiceApi
        if (api == null) {
            Log.w(TAG, "toggle: service not bound yet — queuing activation")
            pendingVoiceActivateUntilMs = SystemClock.uptimeMillis() + 4000L
            bindVoiceService()
            return
        }
        val phase = HudStateBridge.current().phase
        if (phase == HudStateBridge.VoicePhase.IDLE) {
            Log.i(TAG, "toggle: activateVoice()")
            runCatching { api.activateVoice() }
        } else {
            Log.i(TAG, "toggle: exitGeminiFully() (phase=$phase)")
            exitGeminiFully()
        }
    }

    /** Full Gemini exit: session + camera + chat card, same teardown
     *  set as TapInsight's right-arm double-tap. */
    private fun exitGeminiFully() {
        val api = voiceServiceApi
        runCatching { if (api?.isCameraOn() == true) api.toggleCamera() }
        runCatching { api?.shutdownVoice() }
        HudStateBridge.update {
            it.copy(
                phase = HudStateBridge.VoicePhase.IDLE,
                connection = HudStateBridge.ConnectionStatus.IDLE,
                transcript = null,
                oscilloscopeLevel = 0f,
                notification = null
            )
        }
        runCatching { CameraStateBridge.publish(false) }
        runCatching {
            findViewById<View?>(R.id.unipanelCameraPreviewFrame)?.visibility = View.GONE
            findViewById<View?>(R.id.unipanelVisionDot)?.visibility = View.GONE
        }
        // The chat card is NOT hidden here — the phase→IDLE published above
        // starts the linger timer (handleVoicePhaseChange), so the last
        // reply stays up for CHAT_CARD_LINGER_MS after the session ends.
        Log.i(TAG, "Full Gemini exit (session + camera)")
    }

    private fun toggleCamera() {
        val api = voiceServiceApi
        if (api == null) {
            Log.w(TAG, "toggleCamera: service not bound yet")
            bindVoiceService()
            return
        }
        installCameraPreviewProvider()
        runCatching { api.toggleCamera() }
    }

    // ────────────────────────────────────────────────────────────────
    // Input: trackpad routing, cursor, taps
    // ────────────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val deviceName = ev.device?.name
            ?: InputDevice.getDevice(ev.deviceId)?.name.orEmpty()

        // Left arm (cyttsp6_mt) — volume pad; the ONLY app gesture on it
        // is the camera double-tap. Never let it move the cursor.
        if (deviceName.contains("cyttsp6", ignoreCase = true)) {
            handleLeftArmTouch(ev)
            return true
        }

        // Right trackpad (cyttsp5_mt) — cursor movement + touch-tap clicks.
        // In debug builds `adb shell input` events (device name "Virtual")
        // are accepted too, so the whole gesture set can be driven from a
        // host machine without a hand on the temple.
        if (deviceName.contains("cyttsp5", ignoreCase = true) ||
            (BuildConfig.DEBUG && deviceName.contains("Virtual", ignoreCase = true))
        ) {
            handleTrackpadCursorTouch(ev)
            return true
        }

        // Diagnostic: any temple input whose device name we didn't match.
        // If the right pad reports some other name, this reveals it so the
        // match above can be widened.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            Log.i(TAG, "unmatched touch device='$deviceName' action=DOWN")
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Trackpad ACTION_SCROLL — nothing scrollable to route it to.
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /** Left-arm double-tap → toggle camera. Short taps with move
     *  tolerance; two qualifying UPs inside the window fire. */
    private fun handleLeftArmTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                leftArmTapDownTimeMs = SystemClock.uptimeMillis()
                leftArmTapDownX = ev.x
                leftArmTapDownY = ev.y
                leftArmTapMovedTooFar = false
                leftArmTapTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (leftArmTapTracking && !leftArmTapMovedTooFar) {
                    val dx = ev.x - leftArmTapDownX
                    val dy = ev.y - leftArmTapDownY
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) >
                            LEFT_ARM_TAP_MOVE_TOLERANCE_PX) {
                        leftArmTapMovedTooFar = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val wasTracking = leftArmTapTracking
                val movedTooFar = leftArmTapMovedTooFar
                leftArmTapTracking = false
                if (!wasTracking || movedTooFar) return
                val elapsed = SystemClock.uptimeMillis() - leftArmTapDownTimeMs
                if (elapsed >= TAP_MAX_MS) return
                // A single left-arm tap toggles the camera preview
                // (open ↔ close), per Mars's spec.
                Log.i(TAG, "Left-arm single tap (${elapsed}ms) → toggle camera")
                toggleCamera()
            }
            MotionEvent.ACTION_CANCEL -> {
                leftArmTapTracking = false
            }
        }
    }

    /** Right trackpad finger motion → cursor. Drop the first delta of
     *  each touch sequence (it jumps); gain 0.45. */
    private fun handleTrackpadCursorTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                droppedFirstDelta = false
                lastTrackpadX = ev.x
                lastTrackpadY = ev.y
                rightArmTouchDownMs = SystemClock.uptimeMillis()
                rightArmTouchDownX = ev.x
                rightArmTouchDownY = ev.y
                rightArmTouchMoved = false
                rightArmTouchTracking = true
                // Where the cursor was when the finger LANDED. The resize
                // gate must use this, not the live position: the swipe itself
                // moves the cursor (gain 0.45 over a full pull is ~220px), so
                // judging at ACTION_UP finds the cursor already dragged off
                // the very window the wearer started the swipe on.
                cursorAtTouchDownX = cursorX
                cursorAtTouchDownY = cursorY
                // Deliberately NOT cancelling the pending click here: the
                // finger going down again is how a double-tap BEGINS, and
                // clearing the streak on it means the pair can never form.
                // The cancel belongs on the move-flip below, where the touch
                // has proved it is a drag rather than the next tap.
                setCursorVisible(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // Any right-trackpad movement immediately clears the current
                // chat card, even the first delta that is intentionally not
                // applied to the cursor.
                dismissAssistantCard()
                val dx = ev.x - lastTrackpadX
                val dy = ev.y - lastTrackpadY
                lastTrackpadX = ev.x
                lastTrackpadY = ev.y
                // A tap must stay roughly put; a slide is a cursor move,
                // never a click.
                if (rightArmTouchTracking && !rightArmTouchMoved) {
                    val tdx = ev.x - rightArmTouchDownX
                    val tdy = ev.y - rightArmTouchDownY
                    if (kotlin.math.hypot(tdx.toDouble(), tdy.toDouble()) >
                            RIGHT_ARM_TAP_MOVE_TOLERANCE_PX) {
                        rightArmTouchMoved = true
                        cancelPendingSingleTapClick()
                    }
                }
                if (!droppedFirstDelta) {
                    droppedFirstDelta = true
                    return
                }
                // The cursor is never taken away. Pages used to claim the
                // slide inside an active window so they could scroll, which
                // froze the pointer wherever it happened to be — and on a
                // display you aim with, a frozen pointer reads as a dead
                // device. Scrolling is the edge bands' job now.
                moveCursorBy(dx * cursorGain, dy * cursorGain)
            }
            MotionEvent.ACTION_UP -> {
                val tracking = rightArmTouchTracking
                val moved = rightArmTouchMoved
                rightArmTouchTracking = false
                if (!tracking) return
                if (moved) {
                    maybeHandleEdgePull(ev)
                    return
                }
                val elapsed = SystemClock.uptimeMillis() - rightArmTouchDownMs
                if (elapsed >= TAP_MAX_MS) return
                Log.i(TAG, "Right-arm TOUCH tap (${elapsed}ms)")
                onRightArmTapUp(TapSource.TOUCH)
            }
            MotionEvent.ACTION_CANCEL -> {
                rightArmTouchTracking = false
            }
        }
    }

    private var lastTrackpadX = 0f
    private var lastTrackpadY = 0f

    // ── Edge scrolling ───────────────────────────────────────────────
    //
    // Park the cursor near an active window's top or bottom rim and the page
    // scrolls; move away and it stops. This replaces drag-to-scroll, which
    // could only work by freezing the pointer.
    //
    // Two details carry the whole feel. The speed ramps with the SQUARE of
    // how far into the band the cursor sits, starting from nothing at the
    // inner boundary — so resting near the rim to read a link creeps at a few
    // px/s and the link stays clickable, while pushing to the very edge is a
    // fast scroll. And a short dwell has to pass before anything moves, so
    // crossing a band on the way somewhere else does not jog the page.
    private var edgeWindow: BrowserWindowView? = null
    private var edgeVx = 0f
    private var edgeVy = 0f
    private var edgeArmed = false

    private val edgeDwellRunnable = Runnable {
        edgeArmed = true
        edgeWindow?.setEdgeScrollVelocity(edgeVx, edgeVy)
    }

    /** Where the cursor is asking a window to scroll, and how fast. */
    private data class EdgeScroll(val window: BrowserWindowView, val vx: Float, val vy: Float)

    /** The window and velocity the cursor is currently asking for, if any. */
    private fun edgeScrollRequest(): EdgeScroll? {
        if (hubSettingsOverlay?.isShowing == true) return null
        // While the keyboard is up the wearer is typing, and scrolling would
        // drag the field they are typing into off the screen.
        if (keyboardView?.visibility == View.VISIBLE) return null
        val c = hudPinBoardController ?: return null
        if (c.isInModifyMode()) return null
        val pt = cursorInteractionPoint()
        // Only the ACTIVE window scrolls. Any window under the cursor would
        // mean a background page creeping whenever the wearer rests the
        // pointer over it on the way somewhere else.
        val w = c.browserWindowAt(pt.first, pt.second)?.takeIf { it.isActive } ?: return null
        val loc = IntArray(2)
        w.getLocationOnScreen(loc)
        val vy = edgeVelocityFor(pt.second - loc[1], (loc[1] + w.height) - pt.second)
        val vx = edgeVelocityFor(pt.first - loc[0], (loc[0] + w.width) - pt.first)
        // A corner asks for both at once, which is exactly right: it is the
        // only way to reach the far corner of something wider AND taller
        // than the window.
        if (vx == 0f && vy == 0f) return null
        return EdgeScroll(w, vx, vy)
    }

    /**
     * One axis: distance from the low edge and the high edge, in px, to a
     * signed velocity. Negative scrolls toward the low edge (up / left).
     */
    private fun edgeVelocityFor(fromLow: Float, fromHigh: Float): Float {
        val depth: Float
        val direction: Float
        when {
            fromLow < EDGE_BAND_PX -> { depth = EDGE_BAND_PX - fromLow; direction = -1f }
            fromHigh < EDGE_BAND_PX -> { depth = EDGE_BAND_PX - fromHigh; direction = 1f }
            else -> return 0f
        }
        // Outside the window entirely (either distance negative) is not an
        // edge, it is somewhere else.
        if (fromLow < 0f || fromHigh < 0f) return 0f
        val t = (depth / EDGE_BAND_PX).coerceIn(0f, 1f)
        // Quantised so a slide inside the band does not fire a bridge call per
        // motion event; the page is animating itself between updates anyway.
        return ((direction * EDGE_MAX_PX_PER_S * t * t) / EDGE_SPEED_STEP)
            .roundToInt() * EDGE_SPEED_STEP
    }

    private fun updateEdgeScroll() {
        val request = edgeScrollRequest()
        if (request == null) { stopEdgeScroll(); return }
        if (request.window !== edgeWindow) {
            stopEdgeScroll()
            edgeWindow = request.window
            edgeVx = request.vx
            edgeVy = request.vy
            uiHandler.postDelayed(edgeDwellRunnable, EDGE_DWELL_MS)
            return
        }
        edgeVx = request.vx
        edgeVy = request.vy
        if (edgeArmed) request.window.setEdgeScrollVelocity(request.vx, request.vy)
    }

    private fun stopEdgeScroll() {
        uiHandler.removeCallbacks(edgeDwellRunnable)
        edgeWindow?.setEdgeScrollVelocity(0f, 0f)
        edgeWindow = null
        edgeVx = 0f
        edgeVy = 0f
        edgeArmed = false
    }

    /**
     * Pull right to dim, pull left to come back.
     *
     * The hard part is not the gesture, it is not firing it by accident: this
     * pad's whole job is moving a cursor, and every cursor move is also a
     * horizontal drag. So a pull has to be unmistakably deliberate — most of
     * the way across the pad, decisively sideways rather than diagonal, and
     * quick. A slow careful drag across the pad is someone aiming the cursor,
     * and blacking the display out on them would be maddening.
     *
     * Not available while a browser window has the input: inside a window the
     * page owns the gestures, which is the spec, and it also means a wearer
     * reading a page cannot dim by scrolling enthusiastically.
     */
    private fun maybeHandleEdgePull(ev: MotionEvent) {
        val dx = ev.x - rightArmTouchDownX
        val dy = ev.y - rightArmTouchDownY

        // Resize claims the horizontal swipe first, and only while a window is
        // being modified. Both gestures are "swipe sideways", so one of them
        // has to win: resize is the narrower, more deliberate mode — you had to
        // double-tap a window to get here — so it takes precedence, and dim
        // stays available the instant modify ends.
        val resizing = modifyingWindow
        if (resizing != null && hudPinBoardController?.isInModifyMode() == true) {
            // Only when the swipe STARTED with the cursor on the window
            // (Mars's rule). Judged at finger-down because the swipe itself
            // carries the cursor a few hundred px sideways.
            if (!resizing.containsScreenPoint(cursorAtTouchDownX, cursorAtTouchDownY)) return
            if (kotlin.math.abs(dx) > RESIZE_SWIPE_MIN_PX &&
                kotlin.math.abs(dx) > kotlin.math.abs(dy) * EDGE_PULL_STRAIGHTNESS
            ) {
                // Forward grows, back shrinks — the window climbs a fixed 3:4
                // ladder, so the ratio the wearer chose can never drift.
                resizing.resizeStep(if (dx > 0f) 1 else -1)
                hudPinBoardController?.refreshZone()
                Log.i(TAG, "Resized browser window to ${resizing.windowWidth}x${resizing.windowHeight}")
            }
            return
        }

        val active = hudPinBoardController?.browserWindows()?.any { it.isActive } == true
        if (active) return

        val elapsed = SystemClock.uptimeMillis() - rightArmTouchDownMs
        if (elapsed > EDGE_PULL_MAX_MS) return

        // Pad coordinate space varies by unit, so the threshold is taken from
        // the device's own reported range rather than a pixel count guessed
        // from one pair of glasses.
        val range = ev.device?.getMotionRange(MotionEvent.AXIS_X)?.range
            ?: EDGE_PULL_FALLBACK_SPAN
        val need = range * EDGE_PULL_SPAN_FRACTION
        if (kotlin.math.abs(dx) < need) return
        if (kotlin.math.abs(dx) < kotlin.math.abs(dy) * EDGE_PULL_STRAIGHTNESS) return

        val dim = dimController ?: return
        if (dx > 0f && !dim.isDimmed) {
            Log.i(TAG, "Pull right — dimming")
            dim.enter()
        } else if (dx < 0f && dim.isDimmed) {
            Log.i(TAG, "Pull left — undimming")
            dim.exit()
            setCursorVisible(true)
        }
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        // Aiming at a key IS using the keyboard. Only key presses used to
        // reset the idle timer, so hunting for a letter by sliding — which
        // is the whole interaction on a trackpad — could time the keyboard
        // out from under the wearer mid-word.
        if (keyboardView?.visibility == View.VISIBLE) resetKeyboardHideTimer()
        val container = findViewById<View?>(R.id.mainContainer) ?: return
        val maxW = (container.width.takeIf { it > 0 } ?: 640).toFloat()
        val maxH = (container.height.takeIf { it > 0 } ?: 480).toFloat()
        cursorX = (cursorX + dx).coerceIn(0f, maxW - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, maxH - 1f)
        setCursorVisible(true)
        updateCursorView()
        updateEdgeScroll()
    }

    /** Debug cursor placement: "window" or "x,y" in logical px. */
    private fun placeCursorForDebug(spec: String) {
        if (spec == "window") {
            val w = hudPinBoardController?.browserWindows()?.firstOrNull()
            if (w == null) {
                Log.i(TAG, "DEBUG cursor=window — no browser window")
                return
            }
            val origin = IntArray(2)
            w.getLocationOnScreen(origin)
            cursorX = origin[0] + w.width / 2f
            cursorY = origin[1] + w.height / 2f
        } else {
            val parts = spec.split(",")
            cursorX = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: return
            cursorY = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: return
        }
        setCursorVisible(true)
        updateCursorView()
        Log.i(TAG, "DEBUG cursor -> ($cursorX, $cursorY)")
        updateEdgeScroll()
    }

    private fun updateCursorView() {
        val cursor = findViewById<ImageView?>(R.id.cursorView) ?: return
        cursor.translationX = cursorX
        cursor.translationY = cursorY
    }

    private fun setCursorVisible(visible: Boolean) {
        val cursor = findViewById<ImageView?>(R.id.cursorView) ?: return
        isCursorVisible = visible
        cursor.visibility = if (visible) View.VISIBLE else View.GONE
        uiHandler.removeCallbacks(hideCursorRunnable)
        if (visible) {
            updateCursorView()
            uiHandler.postDelayed(hideCursorRunnable, CURSOR_IDLE_HIDE_MS)
        }
    }

    /** Cursor position in absolute screen coordinates. */
    private fun cursorInteractionPoint(): Pair<Float, Float> {
        val container = findViewById<View?>(R.id.mainContainer)
        val loc = IntArray(2)
        container?.getLocationOnScreen(loc)
        return (cursorX + loc[0]) to (cursorY + loc[1])
    }

    /**
     * The right-arm physical tap arrives as a KEY event
     * (KEYCODE_BUTTON_A / KEYCODE_DPAD_CENTER), never as a touch.
     * Single tap = click at cursor (after the double-tap window);
     * double tap = pin modify mode when over a pin, else toggle the
     * Gemini session. DOWN is never consumed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Diagnostic: surface every key so we can see whether the right-arm
        // tap arrives as a key at all, and with what code.
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            Log.i(
                TAG,
                "KEY code=${event.keyCode} action=${event.action} device='${event.device?.name}'"
            )
        }
        val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!isTapKey) return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    rightArmKeyDownMs = SystemClock.uptimeMillis()
                    rightArmKeyTracking = true
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!rightArmKeyTracking) return true
                rightArmKeyTracking = false
                val elapsed = SystemClock.uptimeMillis() - rightArmKeyDownMs
                if (elapsed >= TAP_MAX_MS) return true
                Log.i(TAG, "Right-arm KEY tap (${elapsed}ms, code=${event.keyCode})")
                onRightArmTapUp(TapSource.KEY)
                return true
            }
        }
        return true
    }

    /** Which transport delivered a tap. The echo is one tap on both. */
    private enum class TapSource { KEY, TOUCH }

    /** Drop a queued click and reset the streak it belonged to. */
    private fun cancelPendingSingleTapClick() {
        pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }
        pendingSingleTapClick = null
        rightArmTapStreak = 0
        rightArmKeyLastTapUpMs = 0L
    }

    /**
     * Shared single/double/triple-tap dispatch for the right arm, driven by
     * whichever path delivers the tap.
     *
     * Two things here are load-bearing and were previously wrong.
     *
     * DEDUPE IS BY SOURCE, NOT BY CLOCK. One physical tap arrives on both the
     * KEY path and the cyttsp5 touch path, and the old filter dropped anything
     * within 90ms of the last accepted tap. But gaps are measured UP-to-UP, and
     * a brisk triple-tap is a ~80ms rhythm — so the third tap of a real triple
     * was discarded as an echo and the streak resolved as a DOUBLE. With
     * double-tap now invoking the page agent that misfire costs a network call.
     * Two taps from the SAME transport are always two taps.
     *
     * MOST TAPS ARE NOT DEFERRED. Waiting out the multi-tap window costs the
     * wearer 340ms of silence on every tap, and silence is what makes people
     * tap again — which cancelled the pending click and fired the double
     * instead. So the deferral is now paid ONLY where a second meaning
     * actually exists: a tap into an already-ACTIVE page, where a click could
     * navigate irreversibly. Everywhere else — the hub, the gear, settings,
     * the character grid, an INERT window — the click fires immediately and
     * the streak keeps counting behind it. A 1→3 chain on an INERT window
     * still lands correctly: activate() then setModify(true) is the right end
     * state either way.
     */
    private fun onRightArmTapUp(source: TapSource) {
        val now = SystemClock.uptimeMillis()
        if (source != lastRightArmTapSource &&
            now - lastRightArmTapUpAcceptedMs < RIGHT_ARM_TAP_DEDUPE_MS
        ) {
            // Same physical tap echoed on the other transport — ignore.
            return
        }
        lastRightArmTapUpAcceptedMs = now
        lastRightArmTapSource = source

        // The 2→3 gap gets a longer tolerance than the 1→2 gap. They are not
        // the same act: the second tap follows the first at whatever rhythm
        // feels natural, while the third is a deliberate "no, I meant the
        // other thing" and arrives later. One window for both meant a
        // comfortable triple was read as a double plus a stray click.
        val gap = now - rightArmKeyLastTapUpMs
        val window = if (rightArmTapStreak >= 2) TRIPLE_TAP_WINDOW_MS else DOUBLE_TAP_WINDOW_MS
        val chained = rightArmKeyLastTapUpMs > 0L && gap <= window
        rightArmTapStreak = if (chained) rightArmTapStreak + 1 else 1
        rightArmKeyLastTapUpMs = now

        pendingSingleTapClick?.let { uiHandler.removeCallbacks(it) }
        pendingSingleTapClick = null

        if (rightArmTapStreak >= 3) {
            // Three is the most we mean anything by, so it can fire at once —
            // there is no fourth meaning to wait for.
            rightArmTapStreak = 0
            rightArmKeyLastTapUpMs = 0L
            onRightArmTripleTap()
            return
        }

        // Latch WHERE the tap happened. performClickAtCursor used to sample
        // the cursor when the timer fired, so drifting the cursor during the
        // wait sent the click somewhere the wearer never aimed — off the gear
        // and onto empty space, which starts a live microphone session.
        val point = cursorInteractionPoint()

        if (rightArmTapStreak == 1 && !tapNeedsDeferral(point)) {
            performClickAtCursor(point)
            // The streak keeps running: a second or third tap still resolves
            // to the agent or to MODIFY, it just no longer holds the first
            // click hostage while it waits to find out.
            val streakGuard = Runnable {
                pendingSingleTapClick = null
                rightArmTapStreak = 0
                rightArmKeyLastTapUpMs = 0L
            }
            pendingSingleTapClick = streakGuard
            uiHandler.postDelayed(streakGuard, TRIPLE_TAP_WINDOW_MS + 20L)
            return
        }

        val streakAtSchedule = rightArmTapStreak
        val resolve = Runnable {
            pendingSingleTapClick = null
            rightArmTapStreak = 0
            rightArmKeyLastTapUpMs = 0L
            if (streakAtSchedule >= 2) onRightArmDoubleTap(gap) else performClickAtCursor(point)
        }
        pendingSingleTapClick = resolve
        uiHandler.postDelayed(resolve, window + 20L)
    }

    /**
     * True only where a single tap is irreversible enough to be worth waiting
     * on: inside a page that already has the input, where a click can navigate
     * away. Everything else is cheap to undo, so it fires at once.
     */
    private fun tapNeedsDeferral(point: Pair<Float, Float>): Boolean {
        if (hubSettingsOverlay?.isShowing == true) return false
        val window = hudPinBoardController?.browserWindowAt(point.first, point.second)
            ?: return false
        return window.isActive && hudPinBoardController?.isInModifyMode() != true
    }

    /**
     * Three taps. On a window it is window control (MODIFY: move, resize,
     * delete); anywhere else it opens settings.
     *
     * Window control moved here from double-tap so that double-tap can mean
     * the page agent, which is the thing a wearer reaches for far more often
     * than moving a window. Three taps is the right price for the rarer and
     * more destructive of the two — MODIFY carries a live delete chip.
     *
     * Leaving a window no longer needs its own gesture: a click outside it
     * releases it, and entering MODIFY drops ACTIVE anyway.
     */
    private fun onRightArmTripleTap() {
        stopEdgeScroll()
        val controller = hudPinBoardController
        val pt = cursorInteractionPoint()
        if (controller != null) {
            if (controller.isInModifyMode()) {
                Log.i(TAG, "Triple-tap while modifying — leaving window control")
                controller.exitModifyMode()
                controller.browserWindows().forEach { it.setModify(false) }
                modifyingWindow = null
                setCursorVisible(true)
                return
            }
            val window = controller.browserWindowAt(pt.first, pt.second)
            if (controller.onDoubleTapAt(pt.first, pt.second)) {
                Log.i(TAG, "Triple-tap on a pin — window control")
                if (window != null) {
                    window.setModify(true)
                    modifyingWindow = window
                }
                setCursorVisible(true)
                return
            }
        }
        Log.i(TAG, "Triple-tap on the hub — settings")
        hubSettingsOverlay?.show()
    }

    /**
     * Two taps. On a window it asks the page agent about that page; on empty
     * space it toggles the Gemini session.
     *
     * It must NOT fall through to toggleGeminiSession for anything else. That
     * used to be the default landing spot for any unclassified double-tap,
     * which meant double-clicking a HUD widget — the gear, a settings button,
     * the natural desktop instinct — opened a live microphone or tore down a
     * conversation in progress. The most side-effectful thing in the app is
     * the wrong place to land by accident.
     */
    private fun onRightArmDoubleTap(gapMs: Long) {
        stopEdgeScroll()
        Log.i(TAG, "Right-arm double-tap (gap=${gapMs}ms)")
        val controller = hudPinBoardController
        val pt = cursorInteractionPoint()

        if (hubSettingsOverlay?.isShowing == true) {
            // A panel is up; a second tap on a button is just a second press.
            performClickAtCursor(pt)
            return
        }

        if (controller != null) {
            if (controller.isInModifyMode()) {
                // Window control is triple-tap now, so a double while in it is
                // a mis-tap, not an exit. Consume it: exiting on a stray pair
                // would be a surprise, and the delete chip is live.
                Log.i(TAG, "Double-tap while modifying — ignored")
                return
            }
            val window = controller.browserWindowAt(pt.first, pt.second)
            if (window != null) {
                // SmartView's flow, and the thing that makes the agent an
                // agent: the first double-tap OPENS THE MIC, the second ends
                // the recording and hands whatever was said to this window's
                // agent. A hard-coded task can only ever say what is on a
                // page; a spoken one can say "open the reviews".
                if (agentRecorder.isRecording) finishAgentTask() else startAgentTask(window)
                return
            }
        }

        // Empty space only.
        if (findOverlayHit(pt.first, pt.second) != null) {
            Log.i(TAG, "Double-tap on HUD chrome — ignored")
            return
        }
        toggleGeminiSession()
    }

    /**
     * Turn a cursor position into a real tap inside a window's page.
     *
     * The cursor lives in screen coordinates and the window wants its own, so
     * the point is rebased against the view's on-screen origin. Both events are
     * recycled: this runs on every click into an active page, and leaking a
     * MotionEvent per tap is the kind of thing that only shows up as a mystery
     * slowdown an hour into a session.
     */
    private fun forwardClickToWindow(
        window: BrowserWindowView,
        screenX: Float,
        screenY: Float
    ): Boolean {
        val origin = IntArray(2)
        window.getLocationOnScreen(origin)
        val localX = screenX - origin[0]
        val localY = screenY - origin[1]
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, localX, localY, 0)
        val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, localX, localY, 0)
        return try {
            window.forwardTouch(down) or window.forwardTouch(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /** Synthetic click at the cursor through the overlay hit-test chain. */
    /** [point] is latched at TAP time, not sampled when a timer fires. */
    private fun performClickAtCursor(point: Pair<Float, Float>? = null) {
        // Every branch below can change whether the cursor's current spot
        // means anything (activating a window, dismissing the keyboard), and
        // the wearer may already be parked in a band — so re-decide after,
        // rather than waiting for the next slide.
        try {
            performClickAtCursorInner(point)
        } finally {
            updateEdgeScroll()
        }
    }

    private fun performClickAtCursorInner(point: Pair<Float, Float>? = null) {
        val pt = point ?: cursorInteractionPoint()

        // A browser window gets first refusal, because it is the only surface
        // whose behaviour depends on whether it already has the input.
        //
        // Merely moving the cursor across a window must NOT wake it: the cursor
        // wanders constantly, and a window that grabbed input on contact would
        // swallow gestures the wearer meant for the hub. So the first click
        // ACTIVATES and goes no further — it is the act of choosing the window,
        // not a click inside the page. Every click after that is the page's.
        // …unless a full-screen panel is up. Settings covers the viewport, so
        // any window under it is behind a modal surface and must not see the
        // tap at all — otherwise pressing a settings card silently activates
        // the window sitting beneath it and the panel appears dead. This is
        // the ONLY way to enter a key on-device, so it has to win.
        // The on-screen keyboard is drawn over everything and is the reason
        // the wearer is pointing there at all, so it gets the click first.
        keyboardView?.takeIf { it.visibility == View.VISIBLE }?.let { kb ->
            val loc = IntArray(2)
            kb.getLocationOnScreen(loc)
            val top = loc[1].toFloat()
            if (pt.second >= top) {
                suppressImeFor(900L)
                if (kb.handleAnchoredTap(pt.first - loc[0], pt.second - top)) return
                return   // a miss inside the keyboard is still not the page's
            }
            // Tapping above the keyboard puts it away.
            hideOnScreenKeyboard()
            return
        }

        // …and modify mode outranks BOTH. The board consumes the next tap to
        // delete (✕ chip) or move the pin — but a browser pin in MODIFY is by
        // definition not ACTIVE, so the window branch below read every tap on
        // it as "activate me" and the delete chip was unreachable: windows
        // could never be closed. The board's claim has to be tested first.
        hudPinBoardController?.let { c ->
            if (c.isInModifyMode()) {
                val consumed = c.onOverlayTapWhileModify(pt.first, pt.second)
                // Whatever happened — delete, move, or a stale-view bailout —
                // the board has left modify mode; the window flags must not
                // outlive it, or the next swipe resizes a window that no
                // longer shows any modify border.
                if (!c.isInModifyMode()) {
                    c.browserWindows().forEach { it.setModify(false) }
                    modifyingWindow = null
                }
                if (consumed) return
            }
        }

        val modalUp = hubSettingsOverlay?.isShowing == true
        val window = if (modalUp) null else
            hudPinBoardController?.browserWindowAt(pt.first, pt.second)
        if (window != null) {
            if (!window.isActive) {
                hudPinBoardController?.browserWindows()?.forEach {
                    if (it !== window) it.deactivate()
                }
                window.activate()
                Log.d(TAG, "browser window activated at (${pt.first}, ${pt.second})")
                return
            }
            // Already active: this one is for the page.
            if (forwardClickToWindow(window, pt.first, pt.second)) return
        } else if (!modalUp) {
            // Clicking away from an active window releases it. Reachable only
            // while the keyboard is up (the one time the cursor can leave an
            // active window) or when the cursor was already outside it —
            // otherwise the active window owns the slide and the cursor never
            // gets out, and triple-tap is the deliberate exit. Taps that land
            // on a modal panel are not "away": they are not about the window.
            hudPinBoardController?.browserWindows()?.forEach { it.deactivate() }
        }

        val handled = dispatchOverlayTouchIfHit(pt.first, pt.second)
        Log.d(TAG, "click at cursor (${pt.first}, ${pt.second}) handled=$handled")
    }

    /**
     * Three-state overlay hit-test (ported from TapInsight):
     *   1. Empty transparent region → nothing happens (black space).
     *   2. Inert visual surface     → consume, do nothing.
     *   3. Interactive widget       → dispatch synthetic DOWN+UP.
     * Pin modify mode consumes the NEXT tap before any hit-test.
     */
    private fun dispatchOverlayTouchIfHit(screenX: Float, screenY: Float): Boolean {
        hudPinBoardController?.let { c ->
            if (c.isInModifyMode() && c.onOverlayTapWhileModify(screenX, screenY)) {
                return true
            }
        }
        val hit = findOverlayHit(screenX, screenY)
        if (hit == null) {
            // Empty space: a plain tap ANYWHERE starts a session when idle,
            // so the user never has to land the cursor on the small orb.
            // While a session is active an empty tap does nothing — the
            // right-arm double-tap is what closes it.
            if (HudStateBridge.current().phase == HudStateBridge.VoicePhase.IDLE) {
                Log.i(TAG, "empty-space tap while idle → activate Gemini")
                toggleGeminiSession()
                return true
            }
            return false
        }
        if (!hit.isInteractive) return true

        val targetLocation = IntArray(2)
        hit.view.getLocationOnScreen(targetLocation)
        val localX = screenX - targetLocation[0]
        val localY = screenY - targetLocation[1]
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, localX, localY, 0)
        val up = MotionEvent.obtain(now, now + 1L, MotionEvent.ACTION_UP, localX, localY, 0)
        try {
            hit.view.dispatchTouchEvent(down)
            hit.view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        return true
    }

    private data class OverlayHit(val view: View, val isInteractive: Boolean)

    private fun findOverlayHit(screenX: Float, screenY: Float): OverlayHit? {
        val overlay = findViewById<View?>(R.id.unipanelOverlay) ?: return null
        if (overlay.visibility != View.VISIBLE) return null
        if (overlay !is ViewGroup) return null
        for (i in overlay.childCount - 1 downTo 0) {
            val child = overlay.getChildAt(i) ?: continue
            val hit = findOverlayHitDescendant(child, screenX, screenY)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Depth-first, reverse child order (visually-on-top wins). An
     * INTERACTIVE descendant always wins; an INERT descendant must not
     * short-circuit a clickable ancestor (the orb's glow child would
     * otherwise swallow the orb's tap).
     */
    private fun findOverlayHitDescendant(
        root: View,
        screenX: Float,
        screenY: Float
    ): OverlayHit? {
        if (root.visibility != View.VISIBLE) return null
        var inertDescendant: OverlayHit? = null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i) ?: continue
                val hit = findOverlayHitDescendant(child, screenX, screenY)
                if (hit != null) {
                    if (hit.isInteractive) return hit
                    if (inertDescendant == null) inertDescendant = hit
                }
            }
        }
        val clickable = root.isClickable
        val surface = root.background?.let { !isTransparentBackground(it) } ?: false
        // The camera preview behaves like empty space: it has a solid
        // background, but a right-arm tap on it should still fall through
        // to the idle-activate path rather than being swallowed as an
        // inert surface (Mars: tapping the preview should start Gemini).
        if (root.id == R.id.unipanelCameraPreviewFrame) return inertDescendant
        if (!clickable && !surface) return inertDescendant
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + root.width
        val bottom = top + root.height
        if (screenX < left || screenX >= right || screenY < top || screenY >= bottom) {
            return inertDescendant
        }
        if (clickable) return OverlayHit(root, isInteractive = true)
        return inertDescendant ?: OverlayHit(root, isInteractive = false)
    }

    private fun isTransparentBackground(bg: android.graphics.drawable.Drawable): Boolean {
        if (bg is android.graphics.drawable.ColorDrawable) {
            return ((bg.color ushr 24) and 0xFF) == 0
        }
        return false
    }

    companion object {
        private const val TAG = "X3HubMain"

        /**
         * What a double-tap asks for when no spoken task was given. Phrased
         * as a task rather than a question because page-agent is an ACTING
         * agent: this keeps it on the page it is already on, which is what a
         * wearer glancing at a small window wants by default.
         */
        private const val DEFAULT_AGENT_TASK =
            "Tell me what is on this page and what I can do here."

        /** A pull must cross this much of the pad to count. */
        private const val EDGE_PULL_SPAN_FRACTION = 0.55f
        /** …and be at least this many times more sideways than vertical. */
        private const val EDGE_PULL_STRAIGHTNESS = 2.5f
        /** …and be a flick, not a slow aim. */
        private const val EDGE_PULL_MAX_MS = 600L
        /** Used only when the pad does not report an X range. */
        private const val EDGE_PULL_FALLBACK_SPAN = 900f
        /** A resize swipe is shorter than a dim pull: you are already in a mode. */
        private const val RESIZE_SWIPE_MIN_PX = 120f

        /** y where under-HUD content starts (2 + 36px strip + gap). */
        private const val HUD_CONTENT_TOP = 46

        // Tap timing — TapInsight-proven constants. The 40ms floor
        // filters a single physical tap echoing as two keycodes.
        private const val TAP_MAX_MS = 400L
        /**
         * Chaining windows. The 1->2 gap is paid on every deferred click, so
         * it is short; the 2->3 gap is paid only by someone already
         * multi-tapping, so it is generous. A single window for both made a
         * comfortable triple resolve as a double.
         */
        // 200 was tried first per the audit and failed live: a real pair
        // measured 210ms UP-to-UP and shattered into two singles, one of
        // which clicked the page. 240 keeps the latency win (was 320) with
        // margin for a casual rhythm.
        private const val DOUBLE_TAP_WINDOW_MS = 240L
        private const val TRIPLE_TAP_WINDOW_MS = 400L

        private const val LEFT_ARM_TAP_MOVE_TOLERANCE_PX = 60f
        // A right-pad touch that moves less than this (raw px) is a tap,
        // not a cursor slide.
        private const val RIGHT_ARM_TAP_MOVE_TOLERANCE_PX = 45f

        /**
         * Band thickness in logical px. Deliberately thin: the windows are
         * only 226-430 px tall, so a fat band would swallow the part of the
         * page the wearer is trying to point at.
         */
        private const val EDGE_BAND_PX = 22f
        private const val EDGE_MAX_PX_PER_S = 700f
        private const val EDGE_SPEED_STEP = 25f
        private const val EDGE_DWELL_MS = 180L
        // Collapse one physical tap delivered on both the key and touch
        // paths. Well below a real double-tap gap (>=150ms typical).
        private const val RIGHT_ARM_TAP_DEDUPE_MS = 90L
        private const val CURSOR_IDLE_HIDE_MS = 6_000L
        private const val NOTICE_DISPLAY_MS = 3_500L
        // How long the last chat card lingers after a session ends.
        private const val CHAT_CARD_LINGER_MS = 10_000L
    }
}
