package com.stellar.lang.plugin

/**
 * Plugin interface for machine translation.
 */
interface TranslationPlugin {
    val id: String
    val displayName: String
    val description: String

    fun getStatus(): PluginStatus

    suspend fun translate(text: String, sourceLang: String?, targetLang: String): String?

    suspend fun translateBatch(texts: List<String>, sourceLang: String?, targetLang: String): List<String>? {
        return texts.map { text ->
            translate(text, sourceLang, targetLang) ?: text
        }
    }
}
