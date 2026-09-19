package com.stellar.lang.plugin.onnx

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginStatus
import com.stellar.lang.plugin.TranslationPlugin

/**
 * Translator backed by local ONNX Runtime inference.
 */
class OnnxTranslationPlugin : TranslationPlugin {
    override val id: String = "onnx"
    override val displayName: String = "ONNX Runtime (Local Offline)"
    override val description: String = "Translates text locally using on-device ONNX neural models."

    override fun getStatus(): PluginStatus {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        val target = config?.targetLanguage?.value() ?: "auto"
        return OnnxModelManager.getTranslationStatus(target)
    }

    override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
        val translated = OnnxInferenceEngine.translate(text, sourceLang, targetLang)
        if (translated == null && !OnnxModelManager.isTranslationModelReady(targetLang)) {
            val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            if (config?.onnxAutoDownload?.value() == true) {
                OnnxModelManager.downloadTranslationModelAsync(targetLang)
            }
        }
        return translated
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
    ): List<String>? {
        return texts.map { text ->
            translate(text, sourceLang, targetLang) ?: text
        }
    }
}
