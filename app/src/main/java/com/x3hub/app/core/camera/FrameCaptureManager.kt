package com.x3hub.app.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FrameCaptureManager(
    private val context: Context
) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastFrameMs = 0L

    /**
     * Bound beside the streaming use cases so a still can be taken at any
     * moment the camera is on; null whenever the device refused the
     * three-way bind, in which case [captureStill] says so instead of
     * silently downgrading to a preview frame.
     */
    private var stillUseCase: ImageCapture? = null

    /**
     * Full sensor resolution, JPEG — the same output the system camera
     * button produces. No target resolution on purpose: MAXIMIZE_QUALITY
     * with none set picks the largest JPEG size the sensor offers.
     */
    private fun buildStill(): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(STILL_JPEG_QUALITY)
        .build()

    fun start(
        owner: LifecycleOwner,
        previewSurfaceProvider: Preview.SurfaceProvider? = null,
        onFrameBase64: (String) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // 720p target: CameraX picks the closest sensor-supported
                // size. Roughly 12× the pixels of the previous default
                // (~640×480) so saved photos look like real photos
                // instead of thumbnails, while staying small enough for
                // Gemini Live multimodal streaming.
                .setTargetResolution(Size(1280, 720))
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameMs < FRAME_INTERVAL_MS) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                lastFrameMs = now
                val jpeg = imageProxyToJpeg(imageProxy)
                imageProxy.close()

                if (jpeg != null) {
                    val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                    onFrameBase64(encoded)
                }
            }

            val previewUseCase = previewSurfaceProvider?.let { surfaceProvider ->
                // 4:3 preview — the X3's native photo proportions, full
                // sensor field of view (the 16:9 analysis stream Gemini
                // receives is a crop of this).
                Preview.Builder()
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .build().apply {
                        setSurfaceProvider(surfaceProvider)
                    }
            }

            // Still capture rides along whenever the hardware allows the
            // extra use case; a device that refuses the wider combination
            // keeps streaming exactly as before, minus stills-while-on.
            val still = buildStill()
            val bound = sequenceOf(
                Triple(CameraSelector.DEFAULT_BACK_CAMERA, still, true),
                Triple(CameraSelector.DEFAULT_FRONT_CAMERA, still, true),
                Triple(CameraSelector.DEFAULT_BACK_CAMERA, null, false),
                Triple(CameraSelector.DEFAULT_FRONT_CAMERA, null, false)
            ).firstOrNull { (selector, capture, _) ->
                runCatching {
                    cameraProvider.unbindAll()
                    bindUseCases(cameraProvider, owner, selector, analysis, previewUseCase, capture)
                }.isSuccess
            }
            stillUseCase = if (bound?.third == true) still else null
            if (bound == null) Log.w(TAG, "start: no camera accepted the use cases")
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        stillUseCase = null
    }

    /**
     * Take ONE full-resolution photo and hand back its JPEG bytes, or
     * null when the camera cannot deliver. Two paths to the same shutter:
     * a camera that is already streaming shoots through the still use
     * case bound at [start]; a camera that is OFF is opened for just this
     * frame and released again — the wearer never has to know the
     * preview exists to take a picture.
     */
    fun captureStill(owner: LifecycleOwner, onDone: (ByteArray?) -> Unit) {
        val boundStill = stillUseCase
        if (provider != null && boundStill != null) {
            shoot(boundStill, onDone)
            return
        }
        if (provider != null) {
            // Streaming, but the hardware refused the three-way bind at
            // start — rebinding around a live stream is a teardown the
            // stream may not survive, so be honest instead.
            Log.w(TAG, "captureStill: streaming without a still use case")
            onDone(null)
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val oneShotProvider = runCatching { future.get() }.getOrNull()
            if (oneShotProvider == null) { onDone(null); return@addListener }
            val capture = buildStill()
            val ok = runCatching {
                oneShotProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            }.recoverCatching {
                oneShotProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, capture)
            }.isSuccess
            if (!ok) { onDone(null); return@addListener }
            shoot(capture) { bytes ->
                // unbind is main-thread-only; the callback is not.
                ContextCompat.getMainExecutor(context).execute {
                    runCatching { oneShotProvider.unbind(capture) }
                }
                onDone(bytes)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun shoot(capture: ImageCapture, onDone: (ByteArray?) -> Unit) {
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    image.close()
                    // The rotation is BAKED INTO THE PIXELS, at still
                    // quality. The button photo measures 4032×3024 but
                    // carries an EXIF rotate tag — every EXIF-honoring
                    // viewer shows it upright PORTRAIT, which is this
                    // sensor's true framing. Our pin renderer reads pixels
                    // and ignores EXIF, so shipping the tag instead of the
                    // rotation displayed photos lying on their side
                    // (reported as \"270 degrees rotated\"). Baking it shows
                    // the same upright picture everywhere the button's own
                    // output does, at the same 12MP. On a shot this size
                    // the rotate is a real allocation, so a failure falls
                    // back to the sideways-but-present frame.
                    onDone(
                        if (rotation != 0) {
                            runCatching {
                                rotateJpeg(bytes, rotation, STILL_JPEG_QUALITY)
                            }.getOrDefault(bytes)
                        } else bytes
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w(TAG, "captureStill failed: ${exception.message}")
                    onDone(null)
                }
            }
        )
    }

    fun shutdown() {
        cameraExecutor.shutdownNow()
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        return runCatching {
            val nv21 = yuv420ToNv21(image)
            val out = ByteArrayOutputStream()
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            val jpeg = out.toByteArray()
            // The sensor delivers frames in its native orientation; CameraX
            // reports how far they must be rotated to appear upright via
            // imageInfo.rotationDegrees. YuvImage ignores that, so without this
            // the frames Gemini/Hermes receive are rotated (90° on the X3 Pro),
            // which is why the model reported "the image is rotated." Apply the
            // rotation to the encoded JPEG so downstream consumers always get an
            // upright image.
            val rotation = image.imageInfo.rotationDegrees
            if (rotation == 0) jpeg else rotateJpeg(jpeg, rotation)
        }.getOrNull()
    }

    /** Rotate an encoded JPEG by [degrees] (clockwise) and re-encode. */
    private fun rotateJpeg(
        jpeg: ByteArray,
        degrees: Int,
        quality: Int = JPEG_QUALITY
    ): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (rotated !== src) rotated.recycle()
            out.toByteArray()
        } catch (e: Throwable) {
            jpeg
        } finally {
            src.recycle()
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = yPlane.buffer
        var position = 0
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            yBuffer.get(nv21, position, width)
            position += width
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            val uRowStart = row * uPlane.rowStride
            val vRowStart = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRowStart + col * uPlane.pixelStride
                val vIndex = vRowStart + col * vPlane.pixelStride
                val u = uBuffer.getSafe(uIndex)
                val v = vBuffer.getSafe(vIndex)
                nv21[position++] = v
                nv21[position++] = u
            }
        }

        return nv21
    }

    private fun java.nio.ByteBuffer.getSafe(index: Int): Byte {
        return if (index in 0 until limit()) get(index) else 0
    }

    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        selector: CameraSelector,
        analysis: ImageAnalysis,
        preview: Preview?,
        still: ImageCapture? = null
    ) {
        val useCases = listOfNotNull(analysis, preview, still).toTypedArray()
        cameraProvider.bindToLifecycle(owner, selector, *useCases)
    }

    companion object {
        private const val TAG = "FrameCapture"
        private const val FRAME_INTERVAL_MS = 1100L
        // Chosen against a real shutter-button photo from the glasses'
        // gallery: same 4032×3024 JPEG, and 95 lands in the same
        // bytes-per-pixel family as the system camera's own encode. The
        // HAL's noise/sharpening pipeline differs slightly, so exact byte
        // parity is not a thing an app can promise.
        private const val STILL_JPEG_QUALITY = 95
        // Bumped 62 → 88 so save_photo writes look like real photos
        // (sharp text, recognizable detail) instead of low-bitrate
        // streaming preview frames. The wire cost for Gemini Live
        // is acceptable at 720p.
        private const val JPEG_QUALITY = 88
    }
}
