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
    suspend fun recognizeBytes(
        imageBytes: ByteArray,
        preferredScript: Script = Script.DEVANAGARI,
    ): OcrResult {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return OcrResult("", Script.NONE, false).also {
                Log.e(TAG, "Failed to decode image bytes into Bitmap")
            }

        val image = InputImage.fromBitmap(bitmap, 0 /* rotation */)

        return if (preferredScript == Script.DEVANAGARI) {
            val devResult = runRecognizer(devanagariRecognizer, image)
            val cleanDev = devResult.trim()
            if (cleanDev.isNotEmpty()) {
                Log.i(TAG, "Devanagari OCR: ${cleanDev.length} chars")
                OcrResult(cleanDev, Script.DEVANAGARI, true)
            } else {
                // Devanagari model found nothing; fall back to Latin
                val latinResult = runRecognizer(latinRecognizer, image).trim()
                Log.i(TAG, "Latin fallback OCR: ${latinResult.length} chars")
                OcrResult(latinResult, Script.LATIN, latinResult.isNotEmpty())
            }
        } else {
            val latinResult = runRecognizer(latinRecognizer, image).trim()
            OcrResult(latinResult, Script.LATIN, latinResult.isNotEmpty())
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
        private const val TAG = "BoliOcr"
    }
}
