package com.x3hub.app.core.bridge

/**
 * Binder API surface for the voice foreground Service. Single-module
 * in X3Gemini, but the binder-interface shape is kept from TapInsight:
 * MainActivity binds the Service and casts the returned IBinder to
 * this interface. Same process — plain in-process calls, no AIDL.
 */
interface VoiceServiceApi {

    /** Begin a Gemini Live voice session. Idempotent. */
    fun activateVoice()

    /** End the current voice session. Idempotent. */
    fun shutdownVoice()

    /**
     * End the session as soon as the model's current turn is complete and
     * its audio has finished playing — the page-agent handoff: the agent
     * has the command and the wearer wants the microphone closed the
     * moment Gemini finishes saying so, not five seconds later. This
     * replaced a hold-the-session-open-for-the-outcome mechanism; the ⚙
     * glyph reports progress and the agent speaks its own result.
     */
    fun endSessionAfterTurn()

    /**
     * Hold the microphone privilege without starting a voice session.
     *
     * This app draws itself as a HUD over whatever the wearer is running, so
     * its Activity is never the top resumed activity — the launcher is. Since
     * Android 11 a process in that position does not get an error when it
     * records; it gets SILENCE, indistinguishable from a wearer who said
     * nothing. Only a foreground service typed `microphone` lifts that, which
     * is why the Live session hears fine and anything recording from the
     * Activity does not.
     *
     * Reference counted: the camera, the Live session and a page-agent
     * capture can all want it at once, and whichever finishes first must not
     * pull it out from under the others.
     */
    fun holdMicPrivilege()

    /** Release a [holdMicPrivilege]. Idempotent below zero. */
    fun releaseMicPrivilege()

    /** Snapshot of the HUD state the Service has published. */
    fun currentState(): HudStateBridge.State

    /**
     * Toggle CameraX streaming. When ON, frames stream into the active
     * Gemini Live session (and the preview frame lights up). Idempotent;
     * state mirrored via [CameraStateBridge].
     */
    fun toggleCamera()

    /**
     * Debug only: inject a typed turn as if the wearer had spoken it.
     * The shoot rig cannot rely on a room — the host's audio may be routed
     * to a muted external speaker — and a scripted capture needs the tool
     * calls to happen deterministically.
     */
    fun sendDebugText(text: String) {}

    /**
     * Debug only: stream a raw 16 kHz mono PCM16 file from app-private
     * storage through the same Gemini realtime-audio path as the microphone.
     * The pipeline waits for setupComplete, so scripted voice tests cannot
     * race the Live handshake.
     */
    fun sendDebugPcm16File(fileName: String) {}

    /**
     * Put a picture in front of the assistant mid-session.
     *
     * Some pages ARE a picture — an image result, a chart, a scan — and
     * their text is empty, so reading them hands the model nothing and it
     * answers from imagination. Streaming the window itself as a frame lets
     * it actually look at what the wearer is looking at.
     */
    fun sendPageImage(base64Jpeg: String) {}

    /** True when CameraX is currently streaming. */
    fun isCameraOn(): Boolean

    /**
     * Install a Preview.SurfaceProvider (PreviewView.surfaceProvider) so
     * the next camera activation binds a Preview use case. Pass null to
     * clear. Safe to call before [toggleCamera].
     */
    fun setCameraPreviewSurfaceProvider(provider: androidx.camera.core.Preview.SurfaceProvider?)

    companion object {
        /** FQN used with Intent.setClassName for the bindService call. */
        const val SERVICE_FQN: String =
            "com.x3hub.app.core.session.GeminiSessionForegroundService"
    }
}
