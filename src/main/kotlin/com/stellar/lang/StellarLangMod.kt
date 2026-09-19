package com.stellar.lang

import com.stellar.core.StellarCore
import com.stellar.core.config.ConfigManager
import com.stellar.lang.config.StellarLangConfig
import net.fabricmc.api.ClientModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Dedicated client entry point for the Stellar Lang translation mod.
 */
class StellarLangMod : ClientModInitializer {
    private val logger: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitializeClient() {
        StellarCore.logInfo("Initializing Stellar Lang under namespace ${StellarCore.NAMESPACE}")
        logger.info("Initializing client mod Stellar Lang")

        val config = ConfigManager.register(MOD_ID, "main", StellarLangConfig::class.java)
        if (config.onnxAutoDownload.value()) {
            com.stellar.lang.plugin.onnx.OnnxModelManager.autoDownloadModelsInBackground()
        }
    }

    companion object {
        const val MOD_ID: String = "stellar_lang"
    }
}
