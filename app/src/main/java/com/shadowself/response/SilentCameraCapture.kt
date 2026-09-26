package com.shadowself.response

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.shadowself.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * SilentCameraCapture
 *
 * Captures a front-camera photo without any UI, sound, or notification.
 * Uses CameraX ImageCapture use case bound to a synthetic LifecycleOwner
 * so it works from a ForegroundService with no Activity present.
 *
 * Output: AES-256-GCM encrypted JPEG written to filesDir/incidents/.
 * The encryption key lives exclusively in Android Keystore — never in a file.
 *
 * Failure is silent — if the camera is unavailable (another app is using it,
 * permission revoked, hardware failure), the alert continues without a photo.
 * The incident is still logged with photoPath = null.
 *
 * Why a synthetic LifecycleOwner:
 *   CameraX requires a LifecycleOwner to manage camera resource acquisition
 *   and release. From a Service there is no Activity LifecycleOwner, so we
 *   create a minimal one that we manually drive: CREATED → STARTED → RESUMED
 *   before binding, then PAUSED → STOPPED → DESTROYED after capture to ensure
 *   CameraX releases the hardware immediately.
 */
@Singleton
class SilentCameraCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoEncryptor: PhotoEncryptor
) {
    companion object {
        private const val TAG          = "SilentCameraCapture"
        private const val PHOTO_DIR    = "incidents"
        private const val DATE_FORMAT  = "yyyyMMdd_HHmmss"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /**
     * Captures a front-facing selfie and returns the encrypted file path.
     * Returns null if capture failed for any reason.
     * Caller should never throw — this wraps all exceptions internally.
     */
    suspend fun capture(): CaptureResult = withContext(Dispatchers.Main) {
        try {
            val rawFile    = createOutputFile()
            val captured   = captureToFile(rawFile)
            if (!captured) return@withContext CaptureResult.Failed("Camera capture failed")

            // Encrypt the raw JPEG using AES-256-GCM with Keystore key
            val encrypted  = photoEncryptor.encryptFile(rawFile)
            rawFile.delete()  // Remove unencrypted original immediately

            Logger.d(TAG, "Photo captured and encrypted: ${encrypted.name}")
            CaptureResult.Success(encrypted.absolutePath)
        } catch (e: SecurityException) {
            Logger.e(TAG, "Camera permission denied: ${e.message}")
            CaptureResult.Failed("Camera permission denied")
        } catch (e: Exception) {
            Logger.e(TAG, "Capture exception: ${e.message}")
            CaptureResult.Failed(e.message ?: "Unknown error")
        }
    }

    private suspend fun captureToFile(outputFile: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val lifecycleOwner = ServiceLifecycleOwner()
            lifecycleOwner.start()

            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    val capture  = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        capture
                    )

                    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                    capture.takePicture(
                        outputOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                provider.unbindAll()
                                lifecycleOwner.stop()
                                cont.resume(true)
                            }
                            override fun onError(e: ImageCaptureException) {
                                Logger.e(TAG, "ImageCapture error: ${e.message}")
                                provider.unbindAll()
                                lifecycleOwner.stop()
                                cont.resume(false)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Logger.e(TAG, "CameraProvider error: ${e.message}")
                    lifecycleOwner.stop()
                    cont.resume(false)
                }
            }, ContextCompat.getMainExecutor(context))

            cont.invokeOnCancellation { lifecycleOwner.stop() }
        }

    private fun createOutputFile(): File {
        val dir = File(context.filesDir, PHOTO_DIR).also { it.mkdirs() }
        val ts  = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
        return File(dir, "intruder_raw_$ts.jpg")
    }

    /**
     * Minimal LifecycleOwner for use from a Service context.
     * CameraX requires a LifecycleOwner — this synthetic one gives us
     * full control over the camera lifecycle without an Activity.
     */
    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    sealed class CaptureResult {
        data class Success(val encryptedPath: String) : CaptureResult()
        data class Failed(val reason: String)         : CaptureResult()
    }
}
