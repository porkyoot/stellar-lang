package com.stellar.lang.plugin

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for translation and language detection plugins.
 */
@Suppress("TooManyFunctions", "ComplexCondition", "ReturnCount")
object PluginRegistry {
    private const val ONNX_PLUGIN_ID = "onnx"
    private const val LIBRE_PLUGIN_ID = "libretranslate"

    private val detectorPlugins = ConcurrentHashMap<String, LanguageDetectorPlugin>()
    private val translationPlugins = ConcurrentHashMap<String, TranslationPlugin>()

    @Volatile
    private var explicitFallbackTranslator: TranslationPlugin? = null

    @Volatile
    private var explicitFallbackDetector: LanguageDetectorPlugin? = null

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

    internal fun normalizePluginId(rawId: String): String {
        val lower = rawId.trim().lowercase()
        return when {
            lower.contains(ONNX_PLUGIN_ID) || lower.contains("local") -> ONNX_PLUGIN_ID
            lower.contains("libre") -> LIBRE_PLUGIN_ID
            else -> lower
        }
    }

    fun getDetector(id: String): LanguageDetectorPlugin? {
        val norm = normalizePluginId(id)
        return detectorPlugins[norm] ?: detectorPlugins[id.lowercase()]
    }

    fun getTranslator(id: String): TranslationPlugin? {
        val norm = normalizePluginId(id)
        return translationPlugins[norm] ?: translationPlugins[id.lowercase()]
    }

    fun getAllDetectors(): List<LanguageDetectorPlugin> = detectorPlugins.values.toList()

    fun getAllTranslators(): List<TranslationPlugin> = translationPlugins.values.toList()

    fun getActiveDetector(): LanguageDetectorPlugin {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        val preferred = normalizePluginId(config?.detectionPlugin?.value() ?: ONNX_PLUGIN_ID)
        return detectorPlugins[preferred]
            ?: detectorPlugins[ONNX_PLUGIN_ID]
            ?: detectorPlugins.values.firstOrNull()
            ?: FallbackDetectorPlugin
    }

    fun getActiveTranslator(): TranslationPlugin {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        val preferred = normalizePluginId(config?.translationPlugin?.value() ?: ONNX_PLUGIN_ID)
        return translationPlugins[preferred]
            ?: translationPlugins[ONNX_PLUGIN_ID]
            ?: translationPlugins.values.firstOrNull()
            ?: FallbackTranslationPlugin
    }

    fun setExplicitFallbackTranslator(plugin: TranslationPlugin?) {
        explicitFallbackTranslator = plugin
    }

    fun setExplicitFallbackDetector(plugin: LanguageDetectorPlugin?) {
        explicitFallbackDetector = plugin
    }

    fun getFallbackTranslator(current: TranslationPlugin = getActiveTranslator()): TranslationPlugin? {
        val explicit = explicitFallbackTranslator
        if (explicit != null && explicit !== current && explicit !== FallbackTranslationPlugin) {
            return explicit
        }
        val normId = normalizePluginId(current.id)
        val fallbackId = when (normId) {
            ONNX_PLUGIN_ID -> LIBRE_PLUGIN_ID
            LIBRE_PLUGIN_ID -> ONNX_PLUGIN_ID
            else -> null
        } ?: return null
        val candidate = getTranslator(fallbackId) ?: return null
        return if (candidate !== current && candidate !== FallbackTranslationPlugin) candidate else null
    }

    fun getFallbackDetector(current: LanguageDetectorPlugin = getActiveDetector()): LanguageDetectorPlugin? {
        val explicit = explicitFallbackDetector
        if (explicit != null && explicit !== current && explicit !== FallbackDetectorPlugin) {
            return explicit
        }
        val normId = normalizePluginId(current.id)
        val fallbackId = when (normId) {
            ONNX_PLUGIN_ID -> LIBRE_PLUGIN_ID
            LIBRE_PLUGIN_ID -> ONNX_PLUGIN_ID
            else -> null
        } ?: return null
        val candidate = getDetector(fallbackId) ?: return null
        return if (candidate !== current && candidate !== FallbackDetectorPlugin) candidate else null
    }

    fun getCandidateTranslators(): List<TranslationPlugin> {
        val active = getActiveTranslator()
        val fallback = getFallbackTranslator(active)
        val list = listOfNotNull(active, fallback).distinctBy { it.id }
            .filter { it !== FallbackTranslationPlugin }
        return if (list.isNotEmpty()) list else listOf(active)
    }

    fun clear() {
        explicitFallbackTranslator = null
        explicitFallbackDetector = null
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
