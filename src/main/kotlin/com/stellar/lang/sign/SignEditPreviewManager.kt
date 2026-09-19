package com.stellar.lang.sign

import com.stellar.lang.service.TranslationService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages real-time translation state and debouncing for sign GUI live preview.
 */
object SignEditPreviewManager {
    internal const val DEBOUNCE_MS = 250L
    internal const val PREVIEW_LINE_CHARS = 20

    @Volatile
    var lastRequestedText: String = ""

    @Volatile
    var lastRequestTime: Long = 0L

    @Volatile
    var currentTranslatedText: String = ""

    val isTranslating = AtomicBoolean(false)

    fun clear() {
        lastRequestedText = ""
        lastRequestTime = 0L
        currentTranslatedText = ""
        isTranslating.set(false)
    }

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
            return currentTranslatedText
        }

        if (trimmed != lastRequestedText) {
            lastRequestedText = trimmed
            lastRequestTime = System.currentTimeMillis()
        } else if (!isTranslating.get() && System.currentTimeMillis() - lastRequestTime >= DEBOUNCE_MS) {
            isTranslating.set(true)
            TranslationService.translateAsync(trimmed) { result ->
                isTranslating.set(false)
                if (result != null) {
                    currentTranslatedText = result.translatedText
                }
            }
        }

        return currentTranslatedText
    }
}
