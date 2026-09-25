package com.stellar.lang.sign

import com.stellar.lang.service.TranslationService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages real-time translation state and debouncing for sign GUI live preview.
 */
object SignEditPreviewManager {
    internal const val DEBOUNCE_MS = 250L
    internal const val FAILED_RETRY_COOLDOWN_MS = 5_000L
    internal const val PREVIEW_LINE_CHARS = 20

    @Volatile
    var lastRequestedText: String = ""

    @Volatile
    var lastRequestTime: Long = 0L

    @Volatile
    var currentTranslatedText: String = ""

    val isTranslating = AtomicBoolean(false)
    val hasFailed = AtomicBoolean(false)

    fun clear() {
        lastRequestedText = ""
        lastRequestTime = 0L
        currentTranslatedText = ""
        isTranslating.set(false)
        hasFailed.set(false)
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
            lastRequestedText = trimmed
            hasFailed.set(false)
            return currentTranslatedText
        }

        if (TranslationService.isFailed(trimmed, targetLang)) {
            hasFailed.set(true)
        }

        val isFailedState = hasFailed.get()
        val waitMs = if (isFailedState) FAILED_RETRY_COOLDOWN_MS else DEBOUNCE_MS

        if (trimmed != lastRequestedText) {
            lastRequestedText = trimmed
            lastRequestTime = System.currentTimeMillis()
        } else if (!isTranslating.get() && System.currentTimeMillis() - lastRequestTime >= waitMs) {
            lastRequestTime = System.currentTimeMillis()
            isTranslating.set(true)
            val force = isFailedState
            TranslationService.translateAsync(trimmed, forceRetry = force) { result ->
                isTranslating.set(false)
                if (result != null) {
                    currentTranslatedText = result.translatedText
                    hasFailed.set(false)
                } else {
                    hasFailed.set(true)
                }
            }
        }

        return currentTranslatedText
    }
}
