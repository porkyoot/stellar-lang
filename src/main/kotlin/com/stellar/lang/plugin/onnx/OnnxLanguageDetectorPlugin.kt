package com.stellar.lang.plugin.onnx

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.LanguageDetectorPlugin
import com.stellar.lang.plugin.PluginStatus

/**
 * Language detector backed by local ONNX Runtime inference.
 */
class OnnxLanguageDetectorPlugin : LanguageDetectorPlugin {
    override val id: String = "onnx"
    override val displayName: String = "ONNX Runtime (Local Offline)"
    override val description: String = "Detects languages locally using an on-device ONNX neural model."

    override fun getStatus(): PluginStatus = OnnxModelManager.getDetectionStatus()

    override suspend fun detectLanguage(text: String): String? {
        val detected = OnnxInferenceEngine.detectLanguage(text)
        if (detected == null && !OnnxModelManager.isDetectionModelReady()) {
            val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            if (config?.onnxAutoDownload?.value() == true) {
                OnnxModelManager.downloadDetectionModelAsync()
            }
        }
        return detected
    }
}
