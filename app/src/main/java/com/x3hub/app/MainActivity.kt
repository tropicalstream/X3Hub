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
import kotlinx.coroutines.launch
import com.x3hub.app.core.tools.BrowserTool
import com.x3hub.app.ui.BrowserWindowView
import com.x3hub.app.ui.DimController
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
    private var dimController: DimController? = null
    /** The window currently in modify mode, so a swipe knows what to resize. */
    private var modifyingWindow: BrowserWindowView? = null
    private var lastRightArmTapUpAcceptedMs: Long = 0L

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
                    }
                }
                if (!droppedFirstDelta) {
                    droppedFirstDelta = true
                    return
                }
                // Inside an ACTIVE window the page owns the gestures. The
                // cursor deliberately stops wandering there — it would only
                // be a second thing moving on top of a page that is already
                // scrolling — and the slide becomes the page's drag instead.
                if (activeWindowOwnsGestures()) {
                    if (rightArmTouchMoved) forwardDragToActiveWindow(dx, dy)
                    return
                }
                moveCursorBy(dx * cursorGain, dy * cursorGain)
            }
            MotionEvent.ACTION_UP -> {
                val tracking = rightArmTouchTracking
                val moved = rightArmTouchMoved
                rightArmTouchTracking = false
                if (!tracking) return
                if (moved) {
                    // A drag that went to the page ends there; it is not also
                    // a candidate for the dim pull.
                    if (finishWindowDrag(cancelled = false)) return
                    maybeHandleEdgePull(ev)
                    return
                }
                val elapsed = SystemClock.uptimeMillis() - rightArmTouchDownMs
                if (elapsed >= TAP_MAX_MS) return
                Log.i(TAG, "Right-arm TOUCH tap (${elapsed}ms)")
                onRightArmTapUp()
            }
            MotionEvent.ACTION_CANCEL -> {
                rightArmTouchTracking = false
                finishWindowDrag(cancelled = true)
            }
        }
    }

    private var lastTrackpadX = 0f
    private var lastTrackpadY = 0f

    // ── Page drag (scrolling inside an active window) ────────────────
    private var draggingWindow: BrowserWindowView? = null
    private var dragLocalX = 0f
    private var dragLocalY = 0f
    private var dragDownTimeMs = 0L

    /**
     * True while a window has the input and is not being moved/resized.
     * Modify mode is excluded because there the sideways swipe is the
     * resize gesture, and both cannot claim the same slide.
     */
    private fun activeWindowOwnsGestures(): Boolean {
        val c = hudPinBoardController ?: return false
        if (c.isInModifyMode()) return false
        return c.browserWindows().any { it.isActive }
    }

    /**
     * Turn trackpad deltas into a touch drag on the page. Forwarding is 1:1
     * so the page behaves exactly like a phone under a finger — a scroll of
     * n pad units is n page px, and flings/overscroll come free from the
     * WebView rather than being re-simulated here.
     *
     * Nothing is forwarded until the touch has already disqualified itself
     * as a tap, so clicks still take the untouched tap path.
     */
    private fun forwardDragToActiveWindow(dx: Float, dy: Float) {
        val window = draggingWindow ?: run {
            val w = hudPinBoardController?.browserWindows()
                ?.firstOrNull { it.isActive } ?: return
            val origin = IntArray(2)
            w.getLocationOnScreen(origin)
            val pt = cursorInteractionPoint()
            dragLocalX = pt.first - origin[0]
            dragLocalY = pt.second - origin[1]
            dragDownTimeMs = SystemClock.uptimeMillis()
            draggingWindow = w
            sendDragEvent(w, MotionEvent.ACTION_DOWN)
            w
        }
        dragLocalX += dx
        dragLocalY += dy
        sendDragEvent(window, MotionEvent.ACTION_MOVE)
    }

    /** Close an in-flight page drag. Returns true if there was one. */
    private fun finishWindowDrag(cancelled: Boolean): Boolean {
        val w = draggingWindow ?: return false
        draggingWindow = null
        sendDragEvent(
            w,
            if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        )
        return true
    }

    private fun sendDragEvent(w: BrowserWindowView, action: Int) {
        val ev = MotionEvent.obtain(
            dragDownTimeMs, SystemClock.uptimeMillis(),
            action, dragLocalX, dragLocalY, 0
        )
        try {
            w.forwardTouch(ev)
        } finally {
            ev.recycle()
        }
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
        val container = findViewById<View?>(R.id.mainContainer) ?: return
        val maxW = (container.width.takeIf { it > 0 } ?: 640).toFloat()
        val maxH = (container.height.takeIf { it > 0 } ?: 480).toFloat()
        cursorX = (cursorX + dx).coerceIn(0f, maxW - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, maxH - 1f)
        setCursorVisible(true)
        updateCursorView()
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
                onRightArmTapUp()
                return true
            }
        }
        return true
    }

    /**
     * Shared single/double-tap dispatch for the right arm, driven by
     * whichever path delivers the tap (KEY event or cyttsp5 touch). A
     * short dedupe window collapses one physical tap that arrives on
     * BOTH paths into a single logical tap. Single tap → click at cursor
     * after the double-tap window; double tap → [onRightArmDoubleTap].
     */
    private fun onRightArmTapUp() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRightArmTapUpAcceptedMs < RIGHT_ARM_TAP_DEDUPE_MS) {
            // Same physical tap echoed as both a key and a touch — ignore.
            return
        }
        lastRightArmTapUpAcceptedMs = now

        // x3hub counts to THREE, and that costs something worth naming.
        //
        // X3Gemini fired the double-tap the instant the second tap landed.
        // A third meaning cannot coexist with that: to know a pair is really a
        // pair, you have to wait out the window in which a third could arrive.
        // So the double-tap action is now DEFERRED by one window, and entering
        // pin-modify feels marginally later than it used to. That is the whole
        // price of triple-tap and it is paid only on multi-taps — a single tap
        // is unchanged, and single taps are almost all of them.
        val gap = now - rightArmKeyLastTapUpMs
        val chained = rightArmKeyLastTapUpMs > 0L &&
            gap in DOUBLE_TAP_MIN_GAP_MS..DOUBLE_TAP_WINDOW_MS
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

        val streakAtSchedule = rightArmTapStreak
        val resolve = Runnable {
            pendingSingleTapClick = null
            rightArmTapStreak = 0
            rightArmKeyLastTapUpMs = 0L
            if (streakAtSchedule >= 2) onRightArmDoubleTap(gap) else performClickAtCursor()
        }
        pendingSingleTapClick = resolve
        uiHandler.postDelayed(resolve, DOUBLE_TAP_WINDOW_MS + 20L)
    }

    /**
     * Three taps. Inside an ACTIVE browser window it means "give me back the
     * cursor"; anywhere else it opens settings.
     *
     * Deliberately NOT settings-from-inside-a-window: SmartView used triple-tap
     * for its own settings, and keeping that here would mean the same gesture
     * did two different things depending on a focus state the wearer cannot
     * always see. Leaving the window is the more useful of the two, because
     * without it an active window has no keyboard-free way out.
     */
    private fun onRightArmTripleTap() {
        val active = hudPinBoardController?.browserWindows()?.firstOrNull { it.isActive }
        if (active != null) {
            Log.i(TAG, "Triple-tap inside an active browser window — releasing it")
            active.requestExit()
            setCursorVisible(true)
            return
        }
        Log.i(TAG, "Triple-tap on the hub — settings")
        hubSettingsOverlay?.show()
    }

    private fun onRightArmDoubleTap(gapMs: Long) {
        Log.i(TAG, "Right-arm double-tap (gap=${gapMs}ms)")
        // Pin board gets first refusal: exit modify mode, or enter it
        // when the cursor rests on a pin (move/delete flow, as before).
        val controller = hudPinBoardController
        if (controller != null) {
            if (controller.isInModifyMode()) {
                controller.exitModifyMode()
                controller.browserWindows().forEach { it.setModify(false) }
                modifyingWindow = null
                return
            }
            val pt = cursorInteractionPoint()
            val window = controller.browserWindowAt(pt.first, pt.second)
            if (controller.onDoubleTapAt(pt.first, pt.second)) {
                // A browser window enters modify alongside the board's own
                // highlight-and-delete-chip. It needs telling separately
                // because resize is its business, not the board's — and
                // because modify must drop ACTIVE, or a resize swipe would
                // scroll the page instead of resizing the window.
                if (window != null) {
                    window.setModify(true)
                    modifyingWindow = window
                }
                setCursorVisible(true)
                return
            }
        }
        // Otherwise: toggle the Gemini session (activate ↔ full exit).
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
    private fun performClickAtCursor() {
        val pt = cursorInteractionPoint()

        // A browser window gets first refusal, because it is the only surface
        // whose behaviour depends on whether it already has the input.
        //
        // Merely moving the cursor across a window must NOT wake it: the cursor
        // wanders constantly, and a window that grabbed input on contact would
        // swallow gestures the wearer meant for the hub. So the first click
        // ACTIVATES and goes no further — it is the act of choosing the window,
        // not a click inside the page. Every click after that is the page's.
        val window = hudPinBoardController?.browserWindowAt(pt.first, pt.second)
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
        } else {
            // Clicking away from an active window releases it, which is what
            // makes the hub feel like it owns the cursor again without needing
            // a deliberate exit gesture every time.
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
        private const val DOUBLE_TAP_MIN_GAP_MS = 40L
        private const val DOUBLE_TAP_WINDOW_MS = 320L

        private const val LEFT_ARM_TAP_MOVE_TOLERANCE_PX = 60f
        // A right-pad touch that moves less than this (raw px) is a tap,
        // not a cursor slide.
        private const val RIGHT_ARM_TAP_MOVE_TOLERANCE_PX = 45f
        // Collapse one physical tap delivered on both the key and touch
        // paths. Well below a real double-tap gap (>=150ms typical).
        private const val RIGHT_ARM_TAP_DEDUPE_MS = 90L
        private const val CURSOR_IDLE_HIDE_MS = 6_000L
        private const val NOTICE_DISPLAY_MS = 3_500L
        // How long the last chat card lingers after a session ends.
        private const val CHAT_CARD_LINGER_MS = 10_000L
    }
}
