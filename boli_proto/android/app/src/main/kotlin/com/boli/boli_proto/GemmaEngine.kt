package com.boli.boli_proto

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GemmaEngine — MediaPipe LLM Inference wrapper for Gemma 3n E2B INT4.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  MODEL SETUP (required before first use)                                │
 * │                                                                         │
 * │  adb push <gemma-3n-e2b-it-int4.task> \                                │
 * │    /sdcard/Android/data/com.boli.boli_proto/files/gemma/               │
 * │                 gemma-3n-e2b-it-int4.task                               │
 * │                                                                         │
 * │  Same resolveAsset pattern as OnnxAsr / FastPitchTts: model lives in   │
 * │  external files dir; falls back gracefully if not present.              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * API note (tasks-genai 0.10.x):
 *   In 0.10.x, topK / temperature live in LlmInferenceSessionOptions.
 *   Sessions are created via LlmInferenceSession.createFromOptions(inference, sessionOptions).
 *   Queries are added via session.addQueryChunk(prompt), followed by session.generateResponse()
 *   or session.generateResponseAsync(progressListener).
 *   We create one LlmInference instance and reuse it; each generate() call
 *   creates a fresh session to ensure clean KV-cache state between prompts.
 *
 * Threading:
 *   - All session operations are protected by [mutex] (sessions are NOT thread-safe).
 *   - [generate] and [generateStreaming] are suspend funs — never block UI thread.
 *   - [warmUp] is called from a background thread (same as OnnxAsr / FastPitchTts).
 */
class GemmaEngine(private val context: Context) {

    @Volatile
    var isAvailable: Boolean = false
        private set

    /** The base LlmInference instance — reused across generate() calls. */
    private var llmInference: LlmInference? = null
    private val mutex = Mutex()

    var resolvedModelName: String? = null
        private set

    // -------------------------------------------------------------------------
    // Initialisation & Lifecycle
    // -------------------------------------------------------------------------

    fun warmUp() {
        val modelFile = resolveModelFile()
        resolvedModelName = modelFile?.name
        if (modelFile == null) {
            Log.i(TAG, "Gemma model not found — running in fallback-only mode. " +
                    "Push model to external files dir to enable Gemma.")
            isAvailable = false
            return
        }

        try {
            // Close previous instance if re-initializing
            try {
                llmInference?.close()
            } catch (_: Throwable) {}
            llmInference = null

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_SEQUENCE_TOKENS)
                .setMaxTopK(TOP_K)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isAvailable = true
            Log.i(TAG, "Gemma ready — model=${modelFile.name} " +
                    "(${modelFile.length() / 1_048_576}MB) [seqTokens=$MAX_SEQUENCE_TOKENS]")
        } catch (t: Throwable) {
            Log.e(TAG, "Gemma init failed — falling back to deterministic", t)
            isAvailable = false
            llmInference = null
        }
    }

    /**
     * Checks if Gemma is available or tries to initialize if a model was newly pushed.
     */
    fun checkAvailability(): Boolean {
        if (isAvailable && llmInference != null) return true
        warmUp()
        return isAvailable
    }

    /**
     * Explicit lifecycle cleanup — releases the ~1.5GB native model memory.
     */
    fun close() {
        try {
            isAvailable = false
            llmInference?.close()
            llmInference = null
            Log.i(TAG, "GemmaEngine successfully closed and native resources freed.")
        } catch (t: Throwable) {
            Log.w(TAG, "Error while closing GemmaEngine", t)
        }
    }

    // -------------------------------------------------------------------------
    // Inference helpers
    // -------------------------------------------------------------------------

    /** Build session options (topK / temperature live here in 0.10.x). */
    private fun sessionOptions(
        temperature: Float = STRUCTURED_TEMPERATURE,
        topK: Int = TOP_K,
    ): LlmInferenceSessionOptions =
        LlmInferenceSessionOptions.builder()
            .setTopK(topK)
            .setTemperature(temperature)
            .build()

    // -------------------------------------------------------------------------
    // Public inference API
    // -------------------------------------------------------------------------

    /**
     * Generates a completion for [prompt].
     * Returns null if the engine is not available or generation fails/times out.
     * Callers must handle null by routing to [DeterministicFallback].
     *
     * @param temperature Lower (~0.15) for structured extractions/lessons,
     *                    higher (~0.45) for conversational roleplay.
     */
    suspend fun generate(
        prompt: String,
        temperature: Float = STRUCTURED_TEMPERATURE,
        topK: Int = TOP_K,
    ): String? {
        if (!isAvailable) {
            if (!checkAvailability()) return null
        }
        val inference = llmInference ?: return null

        return try {
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    mutex.withLock {
                        val t0 = System.currentTimeMillis()
                        // Create a fresh session for each generate call so KV-cache
                        // doesn't accumulate across unrelated prompts.
                        val session = LlmInferenceSession.createFromOptions(
                            inference,
                            sessionOptions(temperature = temperature, topK = topK),
                        )
                        val result = try {
                            session.addQueryChunk(prompt)
                            val raw = session.generateResponse()
                            raw?.ifEmpty { null }
                        } catch (t: Throwable) {
                            Log.e(TAG, "MediaPipe generation error caught safely", t)
                            null
                        } finally {
                            try {
                                session.close()
                            } catch (_: Throwable) {}
                        }
                        val ms = System.currentTimeMillis() - t0
                        Log.i(TAG, "generate [temp=$temperature]: ${result?.length ?: 0} chars in ${ms}ms -> ${result?.take(120)?.replace('\n', ' ')}")
                        result
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed or timed out", t)
            null
        }
    }

    /**
     * Streaming generation — delivers tokens to [onToken] as they arrive.
     * Returns the complete joined text, or null on failure.
     */
    suspend fun generateStreaming(
        prompt: String,
        onToken: suspend (String) -> Unit,
    ): String? {
        if (!isAvailable) {
            if (!checkAvailability()) return null
        }
        val inference = llmInference ?: return null

        return try {
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    mutex.withLock {
                        val sb = StringBuilder()
                        val t0 = System.currentTimeMillis()

                        val session = LlmInferenceSession.createFromOptions(inference, sessionOptions())
                        try {
                            session.addQueryChunk(prompt)

                            val complete = java.util.concurrent.atomic.AtomicBoolean(false)
                            val listener = ProgressListener<String> { partial, done ->
                                partial?.let {
                                    sb.append(it)
                                    runBlocking { onToken(it) }
                                }
                                if (done) complete.set(true)
                            }
                            session.generateResponseAsync(listener)
                            var elapsed = 0L
                            while (!complete.get() && elapsed < INFERENCE_TIMEOUT_MS) {
                                kotlinx.coroutines.delay(20)
                                elapsed += 20
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "MediaPipe streaming error caught safely", t)
                        } finally {
                            try {
                                session.close()
                            } catch (_: Throwable) {}
                        }

                        val ms = System.currentTimeMillis() - t0
                        Log.i(TAG, "generateStreaming: ${sb.length} chars in ${ms}ms")
                        sb.toString().ifEmpty { null }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "generateStreaming failed", t)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Model file resolution (mirrors OnnxAsr.resolveAsset pattern)
    // -------------------------------------------------------------------------

    private fun resolveModelFile(): File? {
        val extDir = context.getExternalFilesDir(null)

        // 1. External pushed files take precedence (allows adb push hot-swapping)
        if (extDir != null) {
            val gemmaDir = File(extDir, "gemma").apply { if (!exists()) mkdirs() }

            // 1a. Check known candidate filenames in gemma subfolder
            for (filename in CANDIDATE_FILENAMES) {
                val inSubfolder = File(gemmaDir, filename)
                if (inSubfolder.exists() && inSubfolder.length() > MIN_MODEL_SIZE_BYTES) {
                    Log.i(TAG, "Gemma model (external) @ ${inSubfolder.absolutePath} " +
                            "(${inSubfolder.length() / 1_048_576}MB)")
                    return inSubfolder
                }
            }

            // 1b. Check any .task or .bin model file in gemma subfolder
            val anyInSubfolder = gemmaDir.listFiles()?.firstOrNull {
                (it.name.endsWith(".task") || it.name.endsWith(".bin")) && it.length() > MIN_MODEL_SIZE_BYTES
            }
            if (anyInSubfolder != null) {
                Log.i(TAG, "Gemma model discovered (external subfolder) @ ${anyInSubfolder.absolutePath} " +
                        "(${anyInSubfolder.length() / 1_048_576}MB)")
                return anyInSubfolder
            }

            // 1c. Check flat external directory
            for (filename in CANDIDATE_FILENAMES) {
                val flat = File(extDir, filename)
                if (flat.exists() && flat.length() > MIN_MODEL_SIZE_BYTES) {
                    Log.i(TAG, "Gemma model (flat) @ ${flat.absolutePath}")
                    return flat
                }
            }
            val anyFlat = extDir.listFiles()?.firstOrNull {
                (it.name.endsWith(".task") || it.name.endsWith(".bin")) && it.length() > MIN_MODEL_SIZE_BYTES
            }
            if (anyFlat != null) {
                Log.i(TAG, "Gemma model discovered (flat) @ ${anyFlat.absolutePath}")
                return anyFlat
            }
        }

        // 2. Check internal filesDir (already unpacked from APK assets)
        for (filename in CANDIDATE_FILENAMES) {
            val internalFile = File(context.filesDir, filename)
            if (internalFile.exists() && internalFile.length() > MIN_MODEL_SIZE_BYTES) {
                Log.i(TAG, "Gemma model (internal filesDir) @ ${internalFile.absolutePath}")
                return internalFile
            }
        }
        val anyInternal = context.filesDir.listFiles()?.firstOrNull {
            (it.name.endsWith(".task") || it.name.endsWith(".bin")) && it.length() > MIN_MODEL_SIZE_BYTES
        }
        if (anyInternal != null) {
            Log.i(TAG, "Gemma model discovered (internal) @ ${anyInternal.absolutePath}")
            return anyInternal
        }

        // 3. Check and unpack from APK assets if bundled
        for (filename in CANDIDATE_FILENAMES) {
            try {
                val assetList = context.assets.list("") ?: emptyArray()
                if (filename in assetList) {
                    val dst = File(context.filesDir, filename)
                    Log.i(TAG, "Unpacking bundled Gemma model $filename from APK assets...")
                    context.assets.open(filename).use { input ->
                        dst.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (dst.exists() && dst.length() > MIN_MODEL_SIZE_BYTES) {
                        Log.i(TAG, "Successfully unpacked bundled Gemma model (${dst.length() / 1_048_576}MB)")
                        return dst
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not unpack $filename from assets", e)
            }
        }

        return null
    }

    companion object {
        private const val TAG = "SeedheBolGemma"
        val CANDIDATE_FILENAMES = listOf(
            "gemma-3n-e2b-it-int4.task",
            "gemma-2b-it-cpu-int4.task",
            "gemma-2b-it-cpu-int4.bin",
            "gemma-2b-it-gpu-int4.bin",
            "gemma-2b-it-gpu-int4.task",
        )
        const val MODEL_FILENAME = "gemma-3n-e2b-it-int4.task"
        /** Max context tokens (prompt + generation). 768 tokens provides ample capacity. */
        private const val MAX_SEQUENCE_TOKENS = 768
        const val TOP_K = 25
        /** Deterministic, rule-adhering temperature for structured OCR/lesson extraction. */
        const val STRUCTURED_TEMPERATURE = 0.15f
        /** Controlled temperature for conversational turns that prevents INT4 repetition loops. */
        const val ROLEPLAY_TEMPERATURE = 0.45f
        /** Guard against truncated pushes — anything below 100 MB is suspicious. */
        private const val MIN_MODEL_SIZE_BYTES = 100L * 1_048_576
        /** Maximum inference execution duration before returning clean fallback. */
        const val INFERENCE_TIMEOUT_MS = 12000L
    }
}
