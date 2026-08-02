package com.x3hub.app.core.camera

/**
 * The still-photo door between the voice tools and the camera.
 *
 * A tool runs on a coroutine with a bare Context; binding a camera use
 * case needs the foreground service's lifecycle. Same pattern as the
 * other bridges: the service installs a capturer while it is alive, and
 * a caller finding null gets the honest answer — there is no camera to
 * ask right now.
 *
 * The capturer delivers ONE finished JPEG (or null on any failure) on an
 * arbitrary thread.
 */
object StillCameraBridge {
    @Volatile
    var capturer: ((onDone: (ByteArray?) -> Unit) -> Unit)? = null
}
