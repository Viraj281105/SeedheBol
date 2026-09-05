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
    // Initialisation
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
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_OUTPUT_TOKENS)
                .setMaxTopK(TOP_K)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isAvailable = true
            Log.i(TAG, "Gemma ready — model=${modelFile.name} " +
                    "(${modelFile.length() / 1_048_576}MB)")
        } catch (e: Exception) {
            Log.e(TAG, "Gemma init failed — falling back to deterministic", e)
            isAvailable = false
            llmInference = null
        }
    }

    // -------------------------------------------------------------------------
    // Inference helpers
    // -------------------------------------------------------------------------

    /** Build session options (topK / temperature live here in 0.10.x). */
    private fun sessionOptions(): LlmInferenceSessionOptions =
        LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)
            .build()

    // -------------------------------------------------------------------------
    // Public inference API
    // -------------------------------------------------------------------------

    /**
     * Generates a completion for [prompt].
     * Returns null if the engine is not available or generation fails.
     * Callers must handle null by routing to [DeterministicFallback].
     */
    suspend fun generate(prompt: String): String? {
        if (!isAvailable) return null
        val inference = llmInference ?: return null

        return try {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val t0 = System.currentTimeMillis()
                    // Create a fresh session for each generate call so KV-cache
                    // doesn't accumulate across unrelated prompts.
                    val session = LlmInferenceSession.createFromOptions(inference, sessionOptions())
                    session.addQueryChunk(prompt)
                    val result = session.generateResponse()
                    session.close()
                    val ms = System.currentTimeMillis() - t0
                    Log.i(TAG, "generate: ${result?.length ?: 0} chars in ${ms}ms")
                    result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generate failed", e)
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
        if (!isAvailable) return null
        val inference = llmInference ?: return null

        return try {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    val sb = StringBuilder()
                    val t0 = System.currentTimeMillis()

                    val session = LlmInferenceSession.createFromOptions(inference, sessionOptions())
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
                    while (!complete.get()) Thread.sleep(20)
                    session.close()

                    val ms = System.currentTimeMillis() - t0
                    Log.i(TAG, "generateStreaming: ${sb.length} chars in ${ms}ms")
                    sb.toString().ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateStreaming failed", e)
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
            for (filename in CANDIDATE_FILENAMES) {
                val inSubfolder = File(extDir, "gemma/$filename")
                if (inSubfolder.exists() && inSubfolder.length() > MIN_MODEL_SIZE_BYTES) {
                    Log.i(TAG, "Gemma model (external) @ ${inSubfolder.absolutePath} " +
                            "(${inSubfolder.length() / 1_048_576}MB)")
                    return inSubfolder
                }

                val flat = File(extDir, filename)
                if (flat.exists() && flat.length() > MIN_MODEL_SIZE_BYTES) {
                    Log.i(TAG, "Gemma model (flat) @ ${flat.absolutePath}")
                    return flat
                }
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
        private const val TAG = "BoliGemma"
        val CANDIDATE_FILENAMES = listOf(
            "gemma-2b-it-cpu-int4.bin",
            "gemma-2b-it-cpu-int4.task",
            "gemma-3n-e2b-it-int4.task",
            "gemma-2b-it-gpu-int4.bin",
        )
        const val MODEL_FILENAME = "gemma-2b-it-cpu-int4.bin"
        private const val MAX_OUTPUT_TOKENS = 512
        private const val TOP_K = 40
        private const val TEMPERATURE = 0.7f
        /** Guard against truncated pushes — anything below 100 MB is suspicious. */
        private const val MIN_MODEL_SIZE_BYTES = 100L * 1_048_576
    }
}
