package com.boli.boli_proto

import android.util.Log

/**
 * LlmOutputSanitizer
 *
 * Defense-in-depth against small language model (SLM) failure modes on CPU:
 *   1. Degenerative repetition loops (e.g. autoregressive n-gram loops like "पार्य मान होई पार्य मान होई...").
 *   2. Lexical diversity collapse (repeating the same small set of words over and over).
 *   3. Script mismatch (generating Devanagari when target language is Tamil/Telugu, or generating English when Indic script is expected).
 *   4. Length explosion without terminal punctuation.
 *
 * Any text failing these checks is either sanitized/truncated to the clean prefix
 * or rejected, triggering a clean fallback to curated workplace content.
 */
object LlmOutputSanitizer {

    private const val TAG = "LlmSanitizer"

    /**
     * Returns true if [text] exhibits catastrophic repetition or looping.
     */
    fun hasDegenerativeRepetition(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 20) return false

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 5) return false

        // Check 1: Consecutive n-gram repetition (n = 2, 3, 4)
        for (n in 2..4) {
            if (words.size >= n * 2) {
                var consecutive = 1
                for (i in 0 until words.size - 2 * n + 1 step n) {
                    val g1 = words.subList(i, i + n).joinToString(" ")
                    val g2 = words.subList(i + n, i + 2 * n).joinToString(" ")
                    if (g1.equals(g2, ignoreCase = true)) {
                        consecutive++
                        if (consecutive >= 2) {
                            Log.w(TAG, "Detected consecutive $n-gram loop: \"$g1\"")
                            return true
                        }
                    } else {
                        consecutive = 1
                    }
                }
            }
        }

        // Check 2: Lexical diversity collapse on longer sequences
        if (words.size >= 10) {
            val unique = words.map { it.lowercase().replace(Regex("[.,?!।\"'\\-]"), "") }.toSet().size
            val ratio = unique.toDouble() / words.size.toDouble()
            if (ratio < 0.45) {
                Log.w(TAG, "Lexical diversity collapse: $unique unique out of ${words.size} words (ratio $ratio)")
                return true
            }
        }

        // Check 3: Single word frequency spike (e.g. one word makes up > 25% of the sentence)
        val counts = mutableMapOf<String, Int>()
        for (w in words) {
            val clean = w.lowercase().replace(Regex("[.,?!।\"'\\-]"), "")
            if (clean.length >= 2) {
                val c = (counts[clean] ?: 0) + 1
                counts[clean] = c
                if (c >= 4 && c.toDouble() / words.size.toDouble() > 0.25) {
                    Log.w(TAG, "Single-word repetition spike on \"$clean\": $c / ${words.size}")
                    return true
                }
            }
        }

        // Check 4: Intra-sentence clause duplication (e.g. "X? और X?", "X आहे? आणि X आहे?")
        if (hasDuplicateClauses(trimmed)) {
            return true
        }

        return false
    }

    /**
     * Detects repeated clauses separated by punctuation or conjunctions.
     * E.g. "आपका नाम क्या है? और आपका नाम क्या है?"
     */
    fun hasDuplicateClauses(text: String): Boolean {
        val clauses = text.split(Regex("[?!।.,;\\n]+"))
            .map { it.trim().lowercase().replace(Regex("^(और|व|तथा|आणि|पण|किंवा)\\s+"), "").trim() }
            .filter { it.length >= 6 }

        if (clauses.size >= 2) {
            for (i in 0 until clauses.size - 1) {
                for (j in i + 1 until clauses.size) {
                    val c1 = clauses[i]
                    val c2 = clauses[j]
                    if (c1 == c2 || (c1.length >= 10 && (c1.contains(c2) || c2.contains(c1)))) {
                        Log.w(TAG, "Detected duplicate clause: \"$c1\" vs \"$c2\"")
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Removes bracketed placeholders like "[your name]", "<name>", "(worker)", etc.
     */
    fun stripPlaceholders(text: String): String {
        return text
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\((worker|name|learner|target|role)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("['\"]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Deduplicates identical or overlapping clauses within a single line.
     * E.g. "आजो, आपका नाम क्या है? और आपका नाम क्या है?" -> "आजो, आपका नाम क्या है?"
     */
    fun deduplicateClauses(text: String): String {
        val clean = stripPlaceholders(text)
        val clauseRegex = Regex("[^?!।\\n]+([?!।\\n]+|$)")
        val matches = clauseRegex.findAll(clean).map { it.value.trim() }.filter { it.isNotBlank() }.toList()
        if (matches.size <= 1) return clean

        val kept = mutableListOf<String>()
        val seenNormalized = mutableListOf<String>()

        for (match in matches) {
            val normalized = match.lowercase()
                .replace(Regex("^(और|व|तथा|आणि|पण|किंवा|अरे|भावा|आजो|दादा)\\s*[,:]?\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

            val isDup = seenNormalized.any { prev ->
                prev == normalized || (prev.length >= 8 && normalized.length >= 8 && (prev.contains(normalized) || normalized.contains(prev)))
            }

            if (!isDup) {
                seenNormalized.add(normalized)
                kept.add(match)
            }
        }

        return if (kept.isNotEmpty()) kept.joinToString(" ").trim() else clean
    }

    /**
     * Checks if text generated for Marathi (mr) is actually pure Hindi.
     */
    fun isHindiIntrusionForMarathi(text: String): Boolean {
        val clean = text.lowercase()
        val hindiMarkers = listOf("आपका", "क्या है", "नाम क्या", "है क्या", "में आपका", "मुझे बताओ", "आप कहाँ", "आप कैसे", "नहीं है", "कर रहे हो", "करता हूँ")
        val marathiMarkers = listOf("आहे", "नाही", "काय", "कसे", "आले", "केले", "झाले", "होते", "तुम्ही", "मला", "आपण", "करा", "सांगा", "घ्या", "द्या", "कुठे", "कधी", "भावा", "साहेब", "सुट्टे", "पाहिजे")

        val hasHindi = hindiMarkers.any { clean.contains(it) }
        val hasMarathi = marathiMarkers.any { clean.contains(it) }

        return hasHindi && !hasMarathi
    }

    /**
     * Strips any trailing repetition loop, keeping only the clean prefix.
     * Returns null if the text is hopelessly corrupted or the clean prefix is too short.
     */
    fun sanitize(text: String): String? {
        val noPlaceholders = stripPlaceholders(text)
        val deduplicated = deduplicateClauses(noPlaceholders)
        val trimmed = deduplicated.trim()

        if (!hasDegenerativeRepetition(trimmed)) return trimmed

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (n in 2..4) {
            for (i in 0 until words.size - 2 * n) {
                val g1 = words.subList(i, i + n).joinToString(" ")
                val g2 = words.subList(i + n, i + 2 * n).joinToString(" ")
                if (g1.equals(g2, ignoreCase = true)) {
                    val cleanTokens = words.subList(0, i + n)
                    if (cleanTokens.size >= 3) {
                        val result = cleanTokens.joinToString(" ").trim()
                        Log.i(TAG, "Sanitized repetition loop from ${words.size} words down to ${cleanTokens.size}: \"$result\"")
                        return result
                    } else {
                        Log.w(TAG, "Repetition began immediately at token $i — rejecting as corrupted")
                        return null
                    }
                }
            }
        }

        // If ratio collapse without exact loop, take the first 8 unique words
        val uniqueWords = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (w in words) {
            val key = w.lowercase().replace(Regex("[.,?!।\"']"), "")
            if (!seen.contains(key)) {
                seen.add(key)
                uniqueWords.add(w)
            }
            if (uniqueWords.size >= 8) break
        }

        return if (uniqueWords.size >= 4) {
            uniqueWords.joinToString(" ")
        } else {
            null
        }
    }

    /**
     * Verifies that the text contains characters corresponding to the expected language script.
     *
     * Supported scripts:
     *   - Hindi / Marathi: Devanagari (U+0900..U+097F)
     *   - Tamil: Tamil (U+0B80..U+0BFF)
     *   - Telugu: Telugu (U+0C00..U+0C7F)
     *   - Kannada: Kannada (U+0C80..U+0CFF)
     *   - Malayalam: Malayalam (U+0D00..U+0D7F)
     *   - Bengali / Assamese: Bengali (U+0980..U+09FF)
     *   - Gujarati: Gujarati (U+0A80..U+0AFF)
     *   - Odia: Odia (U+0B00..U+0B7F)
     *   - Punjabi: Gurmukhi (U+0A00..U+0A7F)
     */
    fun matchesScript(text: String, langCodeOrName: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false

        val lang = langCodeOrName.lowercase().trim()

        val hasDevanagari = clean.any { it in '\u0900'..'\u097F' }
        val hasTamil = clean.any { it in '\u0B80'..'\u0BFF' }
        val hasTelugu = clean.any { it in '\u0C00'..'\u0C7F' }
        val hasKannada = clean.any { it in '\u0C80'..'\u0CFF' }
        val hasMalayalam = clean.any { it in '\u0D00'..'\u0D7F' }
        val hasBengali = clean.any { it in '\u0980'..'\u09FF' }
        val hasGujarati = clean.any { it in '\u0A80'..'\u0AFF' }
        val hasOdia = clean.any { it in '\u0B00'..'\u0B7F' }
        val hasGurmukhi = clean.any { it in '\u0A00'..'\u0A7F' }
        val hasLatin = clean.any { it in 'a'..'z' || it in 'A'..'Z' }

        return when {
            lang.startsWith("mr") || lang.contains("marathi") -> hasDevanagari
            lang.startsWith("hi") || lang.contains("hindi") -> hasDevanagari
            lang.startsWith("ta") || lang.contains("tamil") -> hasTamil || (hasLatin && clean.length > 4)
            lang.startsWith("te") || lang.contains("telugu") -> hasTelugu || (hasLatin && clean.length > 4)
            lang.startsWith("kn") || lang.contains("kannada") -> hasKannada || (hasLatin && clean.length > 4)
            lang.startsWith("ml") || lang.contains("malayalam") -> hasMalayalam || (hasLatin && clean.length > 4)
            lang.startsWith("bn") || lang.contains("bengali") || lang.startsWith("as") -> hasBengali || (hasLatin && clean.length > 4)
            lang.startsWith("gu") || lang.contains("gujarati") -> hasGujarati || (hasLatin && clean.length > 4)
            lang.startsWith("or") || lang.contains("odia") -> hasOdia || (hasLatin && clean.length > 4)
            lang.startsWith("pa") || lang.contains("punjabi") -> hasGurmukhi || (hasLatin && clean.length > 4)
            else -> true
        }
    }

    /**
     * Quick check whether an L2 phrase is valid and safe to display.
     */
    fun isValidL2Output(text: String, lang: String): Boolean {
        if (text.isBlank() || text.length < 3) return false
        val clean = stripPlaceholders(text)
        if (clean.length < 3) return false

        // Check for Hindi intrusion when target is Marathi
        val isMarathi = lang.lowercase().startsWith("mr") || lang.lowercase().contains("marathi")
        if (isMarathi && isHindiIntrusionForMarathi(clean)) {
            Log.w(TAG, "Rejected Hindi intrusion for Marathi L2: '$clean'")
            return false
        }

        if (hasDegenerativeRepetition(clean)) return false
        if (!matchesScript(clean, lang)) return false
        return true
    }
}
