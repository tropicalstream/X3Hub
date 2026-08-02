package com.x3hub.app.core.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.x3hub.app.X3HubApp
import com.x3hub.app.core.bridge.CameraStateBridge
import com.x3hub.app.core.bridge.ChatCardBridge
import com.x3hub.app.core.bridge.HudStateBridge
import com.x3hub.app.core.bridge.VoiceServiceApi
import kotlinx.coroutines.launch

/**
 * Foreground Service hosting the Gemini Live voice pipeline + CameraX.
 * Ported from TapInsight unipanel, minus the companion server, OAuth,
 * agents, and HUD data feeds. MainActivity binds it and drives it
 * through [VoiceServiceApi].
 *
 * Foreground-vs-bound semantics: bindService alone does NOT grant the
 * mic privilege; the Service goes foreground inside [activateVoice] /
 * [toggleCamera] via [startForegroundIfNeeded].
 */
class GeminiSessionForegroundService : LifecycleService() {

    private inner class LocalBinder : Binder(), VoiceServiceApi {
        override fun activateVoice() = this@GeminiSessionForegroundService.activateVoice()
        override fun shutdownVoice() = this@GeminiSessionForegroundService.shutdownVoice()
        override fun holdMicPrivilege() = this@GeminiSessionForegroundService.holdMicPrivilege()
        override fun releaseMicPrivilege() = this@GeminiSessionForegroundService.releaseMicPrivilege()
        override fun currentState(): HudStateBridge.State = HudStateBridge.current()
        override fun toggleCamera() = this@GeminiSessionForegroundService.toggleCamera()
        override fun sendDebugText(text: String) { pipeline.sendDebugText(text) }
        override fun endSessionAfterTurn() { pipeline.endSessionAfterTurn() }
        override fun sendDebugPcm16File(fileName: String) {
            pipeline.sendDebugPcm16File(fileName)
        }
        override fun sendPageImage(base64Jpeg: String) { pipeline.sendPageImage(base64Jpeg) }
        override fun isCameraOn(): Boolean = cameraOn
        override fun setCameraPreviewSurfaceProvider(
            provider: androidx.camera.core.Preview.SurfaceProvider?
        ) {
            this@GeminiSessionForegroundService.cameraPreviewSurfaceProvider = provider
        }
    }

    private val binder = LocalBinder()

    @Volatile
    private var foregroundActive: Boolean = false

    /** Lazy so the audio stack isn't allocated until voice activates. */
    private val pipeline: GeminiVoicePipeline by lazy { GeminiVoicePipeline(this) }

    private val frameCapture by lazy {
        com.x3hub.app.core.camera.FrameCaptureManager(this)
    }

    @Volatile
    private var cameraOn: Boolean = false

    /** Preview surface provider supplied by MainActivity's PreviewView. */
    @Volatile
    private var cameraPreviewSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null

    override fun onCreate() {
        super.onCreate()
        // The voice tools' shutter: runs on the main thread because
        // CameraX binding demands it, against THIS service's lifecycle —
        // the same owner the streaming use cases ride.
        com.x3hub.app.core.camera.StillCameraBridge.capturer = { onDone ->
            androidx.core.content.ContextCompat.getMainExecutor(this).execute {
                frameCapture.captureStill(this, onDone)
            }
        }
        // Bridge the shared ChatSessionModel.messages flow into
        // ChatCardBridge so the HUD chat card renders the live chat.
        lifecycleScope.launch {
            try {
                val chat = (applicationContext as X3HubApp).chatModel
                chat.messages.collect { messages ->
                    val cards = messages.map { msg ->
                        ChatCardBridge.Card(
                            text = msg.text,
                            fromUser = msg.fromUser,
                            timestampMs = msg.timestampMs
                        )
                    }
                    ChatCardBridge.publish(cards)
                }
            } catch (e: Exception) {
                Log.w(TAG, "messages → ChatCardBridge bridge failed: ${e.message}", e)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind — issuing LocalBinder")
        // LifecycleService's onBind drives its internal lifecycle; the
        // LifecycleOwner must reach STARTED for CameraX to bind.
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        com.x3hub.app.core.camera.StillCameraBridge.capturer = null
        // Stop CameraX before the pipeline so the analyzer doesn't push
        // a frame into a closed WebSocket.
        if (cameraOn) {
            runCatching { frameCapture.stop() }
            cameraOn = false
            CameraStateBridge.publish(false)
        }
        runCatching { pipeline.release() }
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        foregroundActive = false
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────
    // VoiceServiceApi implementation
    // ────────────────────────────────────────────────────────────────

    /**
     * Order matters: promote to foreground BEFORE the AudioRecord opens
     * (Android 11+ revokes the mic without the privilege attached), then
     * the pipeline connects; AudioRecord opens after onSessionReady.
     */
    private fun activateVoice() {
        Log.i(TAG, "activateVoice()")
        startForegroundIfNeeded()
        pipeline.activate()
    }

    /**
     * Toggle CameraX streaming. Frames go to the active Gemini Live
     * session via [GeminiVoicePipeline.sendCameraFrame]; the preview
     * lights up through [cameraPreviewSurfaceProvider]. Idempotent;
     * state mirrored via [CameraStateBridge].
     */
    private fun toggleCamera() {
        if (cameraOn) {
            Log.i(TAG, "toggleCamera: stopping CameraX")
            runCatching { frameCapture.stop() }
            cameraOn = false
            CameraStateBridge.publish(false)
            HudStateBridge.update { it.copy(notification = "Camera off") }
        } else {
            Log.i(TAG, "toggleCamera: starting CameraX")
            // FGS must be foreground BEFORE CameraX opens, else Android
            // revokes camera access mid-bind on API 30+.
            startForegroundIfNeeded()
            runCatching {
                frameCapture.start(
                    owner = this,
                    previewSurfaceProvider = cameraPreviewSurfaceProvider,
                    onFrameBase64 = { base64 -> pipeline.sendCameraFrame(base64) }
                )
                cameraOn = true
                CameraStateBridge.publish(true)
            }.onFailure { e ->
                Log.w(TAG, "toggleCamera start failed: ${e.message}", e)
                HudStateBridge.update {
                    it.copy(notification = "Camera couldn't start: ${e.message}")
                }
            }
        }
    }

    /**
     * Tear down the live voice session; keep the Service alive so a
     * subsequent activateVoice() skips the bindService round-trip.
     */
    private fun shutdownVoice() {
        Log.i(TAG, "shutdownVoice()")
        pipeline.shutdown(reason = null)
        dropForegroundIfUnneeded()
    }

    /**
     * Outstanding claims on the microphone privilege. A page-agent capture
     * takes one for the few seconds it records — see [VoiceServiceApi.holdMicPrivilege]
     * for why recording without it yields silence rather than an error.
     */
    private var micHolds = 0

    private fun holdMicPrivilege() {
        micHolds++
        startForegroundIfNeeded()
        Log.i(TAG, "mic privilege held (holds=$micHolds)")
    }

    private fun releaseMicPrivilege() {
        if (micHolds > 0) micHolds--
        Log.i(TAG, "mic privilege released (holds=$micHolds)")
        dropForegroundIfUnneeded()
    }

    /** Give up the FGS only when nothing still needs it. */
    private fun dropForegroundIfUnneeded() {
        if (cameraOn || micHolds > 0) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        foregroundActive = false
    }

    // ────────────────────────────────────────────────────────────────
    // FGS promotion
    // ────────────────────────────────────────────────────────────────

    private fun startForegroundIfNeeded() {
        if (foregroundActive) return
        ensureNotificationChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("X3Gemini")
            .setContentText("Voice session active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundActive = true
            Log.d(TAG, "startForeground OK — mic privilege attached")
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "GeminiFgs"
        private const val CHANNEL_ID = "x3hub_voice_session"
        private const val NOTIFICATION_ID = 0x76_3F_45

        private fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "X3Gemini voice session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while the Gemini voice assistant is listening."
                setShowBadge(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
