package com.stellar.lang.plugin

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for translation and language detection plugins.
 */
object PluginRegistry {
    private val detectorPlugins = ConcurrentHashMap<String, LanguageDetectorPlugin>()
    private val translationPlugins = ConcurrentHashMap<String, TranslationPlugin>()

    init {
        registerDefaults()
    }

    fun registerDefaults() {
        val libre = com.stellar.lang.plugin.libretranslate.LibreTranslatePlugin()
        registerDetector(libre)
        registerTranslator(libre)

        val onnxDetector = com.stellar.lang.plugin.onnx.OnnxLanguageDetectorPlugin()
        val onnxTranslator = com.stellar.lang.plugin.onnx.OnnxTranslationPlugin()
        registerDetector(onnxDetector)
        registerTranslator(onnxTranslator)
    }

    fun registerDetector(plugin: LanguageDetectorPlugin) {
        detectorPlugins[plugin.id.lowercase()] = plugin
    }

    fun registerTranslator(plugin: TranslationPlugin) {
        translationPlugins[plugin.id.lowercase()] = plugin
    }

    fun getDetector(id: String): LanguageDetectorPlugin? = detectorPlugins[id.lowercase()]

    fun getTranslator(id: String): TranslationPlugin? = translationPlugins[id.lowercase()]

    fun getAllDetectors(): List<LanguageDetectorPlugin> = detectorPlugins.values.toList()

    fun getAllTranslators(): List<TranslationPlugin> = translationPlugins.values.toList()

    fun getActiveDetector(): LanguageDetectorPlugin {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        val preferred = config?.detectionPlugin?.value()?.trim()?.lowercase() ?: "onnx"
        return detectorPlugins[preferred]
            ?: detectorPlugins["onnx"]
            ?: detectorPlugins.values.firstOrNull()
            ?: FallbackDetectorPlugin
    }

    fun getActiveTranslator(): TranslationPlugin {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        val preferred = config?.translationPlugin?.value()?.trim()?.lowercase() ?: "onnx"
        return translationPlugins[preferred]
            ?: translationPlugins["onnx"]
            ?: translationPlugins.values.firstOrNull()
            ?: FallbackTranslationPlugin
    }

    fun clear() {
        detectorPlugins.clear()
        translationPlugins.clear()
    }

    object FallbackDetectorPlugin : LanguageDetectorPlugin {
        override val id: String = "fallback"
        override val displayName: String = "Fallback Detector"
        override val description: String = "No detector plugin registered"
        override fun getStatus(): PluginStatus = PluginStatus.NotConfigured("No detector plugin available")
        override suspend fun detectLanguage(text: String): String? = null
    }

    object FallbackTranslationPlugin : TranslationPlugin {
        override val id: String = "fallback"
        override val displayName: String = "Fallback Translator"
        override val description: String = "No translator plugin registered"
        override fun getStatus(): PluginStatus = PluginStatus.NotConfigured("No translation plugin available")
        override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? = null
    }
}
