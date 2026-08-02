package com.x3hub.app.core.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.x3hub.app.core.bridge.HudStateBridge
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gemini-callable camera tool. `save_photo` captures the most recent
 * camera frame (base64 JPEG via [frameProvider], populated by the
 * Gemini Live multimodal pipeline) and writes it to shared storage
 * under `DCIM/X3Gemini/` via MediaStore — photos appear in the RayNeo
 * native gallery and over USB/MTP, no write permission needed on
 * Android 10+ (minSdk is 29/Q, so the MediaStore path always applies).
 */
class CameraTool(
    private val context: Context,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    override val name = "camera_action"

    companion object {
        private const val TAG = "CameraTool"
        private val FILENAME_TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        const val DCIM_SUBFOLDER = "X3Hub"

        /**
         * A cold camera takes ~1-2s to open plus MAXIMIZE_QUALITY capture
         * time; generous headroom, but bounded — a wedged camera HAL must
         * fail the tool call, not hang the model's turn forever.
         */
        private const val CAPTURE_TIMEOUT_MS = 12_000L
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "save_photo"
        val title = args["title"]?.trim()?.takeIf { it.isNotBlank() } ?: "X3Hub Photo"
        Log.d(TAG, "action=$action title=$title")
        return when (action) {
            "save_photo" -> savePhoto(title)
            "take_photo" -> takePhoto(title)
            else -> Result.success("Unknown camera action: $action")
        }
    }

    /**
     * Take a NEW full-resolution picture — the same output pressing the
     * camera button produces, not a frame skimmed off the preview stream
     * — save it to the gallery, and pin it to the HUD board.
     *
     * A HANDOFF, exactly like a page errand: the tool returns the moment
     * the capture is dispatched, because a cold camera plus a 12MP encode
     * is seconds of work, and the first cut spent them INSIDE the tool
     * turn — the model sat mute holding the mic while the shutter ground
     * away, then the session lingered its idle clock on top. Now the
     * model speaks its one line, the session hangs up, the ⚙ glyph shows
     * the capture working, and the pin appearing IS the result.
     */
    private fun takePhoto(title: String): Result<String> {
        val capturer = com.x3hub.app.core.camera.StillCameraBridge.capturer
            ?: return Result.failure(
                IllegalStateException("The camera isn't available right now.")
            )
        com.x3hub.app.core.bridge.PhotoCaptureBridge.set(true)
        captureScope.launch {
            try {
                val jpeg = kotlinx.coroutines.withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                    kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { cont ->
                        capturer { bytes ->
                            if (cont.isActive) cont.resume(bytes) { }
                        }
                    }
                }
                if (jpeg == null || jpeg.isEmpty()) {
                    Log.w(TAG, "take_photo: capture failed or timed out")
                    // The session that asked is gone by design; the HUD
                    // notification line is the surface that remains.
                    HudStateBridge.update {
                        it.copy(notification = "The photo could not be taken.")
                    }
                    return@launch
                }
                storePhoto(jpeg, title)
            } finally {
                com.x3hub.app.core.bridge.PhotoCaptureBridge.set(false)
            }
        }
        return Result.success(
            "Taking the photo — it will appear on the HUD as a pin and in the " +
                "glasses gallery. The camera works on its own from here."
        )
    }

    /** The storage half of [takePhoto], off the tool turn. */
    private fun storePhoto(jpeg: ByteArray, title: String) {
        val timestamp = FILENAME_TIMESTAMP.format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "photo" }
        val filename = "$timestamp-$safeTitle.jpg"
        val galleryOk = saveToDcimViaMediaStore(jpeg, filename) != null
        if (galleryOk) {
            Log.i(TAG, "take_photo: ${jpeg.size}B to DCIM/$DCIM_SUBFOLDER/$filename")
        }

        // Same directory the picture pins already live in, so the board's
        // renderer and full-screen viewer treat it like any other picture.
        val pinDir = java.io.File(context.filesDir, "hud_pins").apply { mkdirs() }
        val pinFile = java.io.File(pinDir, "pin_${System.currentTimeMillis()}.jpg")
        val pinWriteOk = runCatching { pinFile.writeBytes(jpeg) }.isSuccess
        val pinned = pinWriteOk && com.x3hub.app.core.bridge.HudPinStore.add(
            com.x3hub.app.core.bridge.HudPinStore.HudPin(
                type = com.x3hub.app.core.bridge.HudPinStore.TYPE_PICTURE,
                label = title.take(24),
                payload = pinFile.absolutePath
            )
        )
        if (pinWriteOk && !pinned) runCatching { pinFile.delete() }
        when {
            pinned -> Unit                     // the pin on the board says it all
            galleryOk -> HudStateBridge.update {
                it.copy(notification = "Photo saved to the gallery — the board is full.")
            }
            else -> HudStateBridge.update {
                it.copy(notification = "The photo could not be stored.")
            }
        }
    }

    /**
     * Off-turn work only. SupervisorJob so one failed capture cannot
     * poison the next; never cancelled — the tool object lives as long
     * as the dispatcher that owns it.
     */
    private val captureScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private fun savePhoto(title: String): Result<String> {
        val base64 = frameProvider()?.trim().orEmpty()
        if (base64.isEmpty()) {
            Log.w(TAG, "save_photo: no camera frame available")
            return Result.failure(
                IllegalStateException(
                    "Camera frame not available. The camera must be on — " +
                        "the user can double-tap the left temple arm to start it."
                )
            )
        }

        val jpegBytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "save_photo: base64 decode failed", e)
            return Result.failure(IOException("Failed to decode camera frame: ${e.localizedMessage}"))
        }
        if (jpegBytes.isEmpty()) {
            return Result.failure(IOException("Camera frame decoded to 0 bytes."))
        }

        val timestamp = FILENAME_TIMESTAMP.format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "photo" }
        val filename = "$timestamp-$safeTitle.jpg"

        val saved = saveToDcimViaMediaStore(jpegBytes, filename)
            ?: return Result.failure(IOException("Could not write the photo to storage."))
        Log.i(TAG, "save_photo: wrote ${jpegBytes.size}B to DCIM/$DCIM_SUBFOLDER/$filename (id=${saved.id})")
        return Result.success(
            "Photo saved as $filename in the glasses gallery (DCIM/$DCIM_SUBFOLDER)."
        )
    }

    private data class DcimSave(val id: Long, val uri: Uri)

    /**
     * Insert the JPEG into MediaStore under `DCIM/X3Gemini/`. IS_PENDING
     * gates the half-written file from other gallery apps.
     */
    private fun saveToDcimViaMediaStore(jpegBytes: ByteArray, filename: String): DcimSave? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$DCIM_SUBFOLDER/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert failed: ${e.message}")
            null
        } ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { it.write(jpegBytes) }
                ?: throw IOException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            DcimSave(ContentUris.parseId(uri), uri)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore write failed; rolling back: ${e.message}")
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }
}
