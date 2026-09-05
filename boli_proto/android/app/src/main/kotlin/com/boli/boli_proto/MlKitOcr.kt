package com.boli.boli_proto

import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR using Google ML Kit.
 *
 * Why ML Kit instead of PaddleOCR (RapidOCR)?
 *   The previous approach (docs/HANDOFF.md §OCR) showed that PaddleOCR's shared
 *   "ch" detector scored 0.000 on every Devanagari test image at default thresholds
 *   and only found fragments when thresholds were pushed to 0.1. ML Kit ships a
 *   dedicated Devanagari text recognition model, runs entirely on-device, requires
 *   no Python environment, and is already integrated with Android/CameraX.
 *
 * Script coverage:
 *   - DEVANAGARI — Hindi + Marathi (the primary pair currently shipping)
 *   - LATIN      — English transliterations / signboards in Roman script
 *   - Tamil, Telugu, Kannada are covered by the Latin recognizer fallback for
 *     now; dedicated recognizers can be added identically by adding the
 *     corresponding ML Kit artifact and a new recognizer instance.
 *
 * Threading: ML Kit callbacks arrive on an arbitrary thread. [recognizeBytes]
 * bridges to coroutines via [suspendCancellableCoroutine] and is safe to call
 * from any coroutine context.
 */
class MlKitOcr {

    data class OcrResult(
        /** The concatenated text extracted from the image. */
        val text: String,
        /** Which recognizer produced this result. */
        val scriptHint: Script,
        /** True if the raw result was non-empty before cleanup. */
        val hadContent: Boolean,
    ) {
        val isEmpty get() = text.isBlank()
    }

    enum class Script { DEVANAGARI, LATIN, NONE }

    /** ML Kit recognizer instances are thread-safe and reusable. */
    private val devanagariRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Recognizes text in [imageBytes] (JPEG or PNG, typical camera capture).
     *
     * Strategy:
     *   1. Try Devanagari first — if it yields text, return immediately.
     *   2. Fall back to Latin recognizer.
     *   3. If both are empty, return OcrResult with isEmpty=true.
     *
     * Callers that already know the script can pass [preferredScript] to skip
     * the Devanagari attempt when Latin is expected.
     */
    data class CropRect(
        val left: Float = 0f,
        val top: Float = 0f,
        val width: Float = 1f,
        val height: Float = 1f,
    )

    /**
     * Recognizes text in [imageBytes] (JPEG or PNG, typical camera capture).
     *
     * Strategy:
     *   1. Determine image orientation from EXIF metadata and rotate upright.
     *   2. If [cropRect] is specified, crop the upright image strictly to the viewfinder box.
     *   3. Try Devanagari first with correct rotation.
     *   4. Fall back to Latin recognizer.
     *   5. If both are empty, return OcrResult with isEmpty=true.
     */
    suspend fun recognizeBytes(
        imageBytes: ByteArray,
        preferredScript: Script = Script.DEVANAGARI,
        cropRect: CropRect? = null,
    ): OcrResult {
        val rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return OcrResult("", Script.NONE, false).also {
                Log.e(TAG, "Failed to decode image bytes into Bitmap")
            }

        // Extract EXIF orientation
        val exifRotation = runCatching {
            val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(imageBytes))
            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

        // Rotate bitmap upright first so coordinates match the portrait viewfinder
        val uprightBitmap = if (exifRotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(exifRotation.toFloat())
            android.graphics.Bitmap.createBitmap(
                rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
            )
        } else {
            rawBitmap
        }

        // Crop to the bounding box if provided
        val targetBitmap = if (cropRect != null && (cropRect.width < 0.99f || cropRect.height < 0.99f)) {
            val x = (cropRect.left * uprightBitmap.width).toInt().coerceIn(0, uprightBitmap.width - 1)
            val y = (cropRect.top * uprightBitmap.height).toInt().coerceIn(0, uprightBitmap.height - 1)
            val w = (cropRect.width * uprightBitmap.width).toInt().coerceIn(1, uprightBitmap.width - x)
            val h = (cropRect.height * uprightBitmap.height).toInt().coerceIn(1, uprightBitmap.height - y)
            Log.i(TAG, "Cropping upright bitmap (${uprightBitmap.width}x${uprightBitmap.height}) to rect [$x, $y, $w, $h]")
            android.graphics.Bitmap.createBitmap(uprightBitmap, x, y, w, h)
        } else {
            uprightBitmap
        }

        Log.i(TAG, "recognizeBytes: target size=${targetBitmap.width}x${targetBitmap.height}, exifRotation=$exifRotation°")

        if (preferredScript == Script.DEVANAGARI) {
            var image = InputImage.fromBitmap(targetBitmap, 0)
            var devResult = runRecognizer(devanagariRecognizer, image).trim()

            // If empty or negligible text and no explicit crop was given, try other 90-degree rotations
            if (devResult.length < 3 && cropRect == null) {
                val candidateRotations = listOf(90, 270, 180).filter { it != 0 }
                for (rot in candidateRotations) {
                    val retryImage = InputImage.fromBitmap(targetBitmap, rot)
                    val retryResult = runRecognizer(devanagariRecognizer, retryImage).trim()
                    if (retryResult.length > devResult.length) {
                        Log.i(TAG, "Devanagari OCR improved at rotation=$rot°: ${retryResult.length} chars")
                        devResult = retryResult
                        image = retryImage
                        if (devResult.length >= 5) break
                    }
                }
            }

            if (devResult.isNotEmpty()) {
                Log.i(TAG, "Devanagari OCR extracted [${devResult.length} chars]: \"${devResult.replace('\n', ' ')}\"")
                return OcrResult(devResult, Script.DEVANAGARI, true)
            } else {
                // Devanagari model found nothing; fall back to Latin
                val latinResult = runRecognizer(latinRecognizer, image).trim()
                Log.i(TAG, "Latin fallback OCR: ${latinResult.length} chars: \"${latinResult.replace('\n', ' ')}\"")
                return OcrResult(latinResult, Script.LATIN, latinResult.isNotEmpty())
            }
        } else {
            val image = InputImage.fromBitmap(targetBitmap, 0)
            val latinResult = runRecognizer(latinRecognizer, image).trim()
            return OcrResult(latinResult, Script.LATIN, latinResult.isNotEmpty())
        }
    }

    /** Bridges ML Kit's listener-based API into a suspend function. */
    private suspend fun runRecognizer(
        recognizer: TextRecognizer,
        image: InputImage,
    ): String = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit recognition failed", e)
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    companion object {
        private const val TAG = "SeedheBolOcr"
    }
}
