package com.stellar.lang.plugin

/**
 * Plugin interface for detecting language of text.
 */
interface LanguageDetectorPlugin {
    val id: String
    val displayName: String
    val description: String

    fun getStatus(): PluginStatus

    suspend fun detectLanguage(text: String): String?
}
