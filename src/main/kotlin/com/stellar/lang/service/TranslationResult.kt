package com.stellar.lang.service

/**
 * Encapsulates the outcome of a translation request.
 */
data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val detectedLanguage: String,
    val targetLanguage: String,
    val isSameLanguage: Boolean,
)
