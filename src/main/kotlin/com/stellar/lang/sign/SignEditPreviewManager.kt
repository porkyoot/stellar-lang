package com.stellar.lang.sign

import com.stellar.lang.service.TranslationService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages real-time translation state and debouncing for sign GUI live preview.
 */
@Suppress("LongMethod")
object SignEditPreviewManager {
    internal const val DEBOUNCE_MS = 250L
    internal const val FAILED_RETRY_COOLDOWN_MS = 5_000L
    internal const val MAX_RETRY_COOLDOWN_MS = 60_000L
    internal const val PREVIEW_LINE_CHARS = 20

    @Volatile
    var lastRequestedText: String = ""

    @Volatile
    var lastRequestTime: Long = 0L

    @Volatile
    var currentTranslatedText: String = ""

    @Volatile
    var detectedLanguage: String? = null

    val isTranslating = AtomicBoolean(false)
    val hasFailed = AtomicBoolean(false)
    val isSameLanguage = AtomicBoolean(false)

    @Volatile
    var failureCount: Int = 0

    fun getRetryCooldownMs(): Long {
        val factor = 1L shl (failureCount - 1).coerceAtLeast(0)
        return (FAILED_RETRY_COOLDOWN_MS * factor).coerceAtMost(MAX_RETRY_COOLDOWN_MS)
    }

    fun clear() {
        lastRequestedText = ""
        lastRequestTime = 0L
        currentTranslatedText = ""
        detectedLanguage = null
        isTranslating.set(false)
        hasFailed.set(false)
        isSameLanguage.set(false)
        failureCount = 0
    }

    @Suppress("CognitiveComplexMethod")
    fun updateRealtimeTranslation(inputText: String): String {
        val trimmed = inputText.trim()
        if (trimmed.isEmpty()) {
            clear()
            return ""
        }

        val targetLang = TranslationService.getTargetLanguage()

        val cached = TranslationService.getCached(trimmed, targetLang)
        if (cached != null) {
            currentTranslatedText = cached.translatedText
            detectedLanguage = cached.detectedLanguage
            lastRequestedText = trimmed
            hasFailed.set(false)
            isSameLanguage.set(cached.isSameLanguage)
            failureCount = 0
            return currentTranslatedText
        }

        if (TranslationService.isFailed(trimmed, targetLang)) {
            hasFailed.set(true)
        }

        val isFailedState = hasFailed.get()
        val waitMs = if (isFailedState) getRetryCooldownMs() else DEBOUNCE_MS

        if (trimmed != lastRequestedText) {
            lastRequestedText = trimmed
            lastRequestTime = System.currentTimeMillis()
            failureCount = 0
        } else if (!isTranslating.get() && System.currentTimeMillis() - lastRequestTime >= waitMs) {
            lastRequestTime = System.currentTimeMillis()
            isTranslating.set(true)
            val force = isFailedState
            TranslationService.translateAsync(trimmed, forceRetry = force) { result ->
                isTranslating.set(false)
                if (result != null) {
                    currentTranslatedText = result.translatedText
                    detectedLanguage = result.detectedLanguage
                    hasFailed.set(false)
                    isSameLanguage.set(result.isSameLanguage)
                    failureCount = 0
                } else {
                    hasFailed.set(true)
                    detectedLanguage = null
                    isSameLanguage.set(false)
                    failureCount++
                }
            }
        }

        return currentTranslatedText
    }
}
