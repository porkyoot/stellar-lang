package com.stellar.lang.config

import com.stellar.core.config.ConfigManager
import com.stellar.core.input.Key
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class StellarLangConfigSpec : FunSpec({
    test("StellarLangConfig registers with expected defaults") {
        val testId = "test_${System.nanoTime()}"
        val config = ConfigManager.register("stellar_lang", testId, StellarLangConfig::class.java)
        config shouldNotBe null
        config.enabled.value() shouldBe true
        config.apiHost.value() shouldBe "https://libretranslate.com"
        config.apiKey.value() shouldBe ""
        config.targetLanguage.value() shouldBe "auto"
        config.translationPlugin.value() shouldBe "onnx"
        config.detectionPlugin.value() shouldBe "onnx"
        config.onnxModelDir.value() shouldBe "config/stellar_lang/models"
        config.onnxAutoDownload.value() shouldBe true
        config.onnxExecutionThreads.value() shouldBe StellarLangConfig.DEFAULT_ONNX_THREADS
        config.translateChat.value() shouldBe true
        config.translateSigns.value() shouldBe true
        config.signTooltips.value() shouldBe true
        config.signRaycastIntervalMs.value() shouldBe StellarLangConfig.DEFAULT_SIGN_RAYCAST_INTERVAL_MS
        config.translateBooks.value() shouldBe true
        config.translateEntities.value() shouldBe true
        config.translateItems.value() shouldBe true
        config.translateContainers.value() shouldBe true
        config.translateMapBanners.value() shouldBe true
        config.hideIndicators.value() shouldBe false
        config.showOriginalKey.value() shouldBe Key.KEY_COMMA

        config.hideIndicators.setValue(true, true)
        config.hideIndicators.value() shouldBe true

        config.translateMapBanners.setValue(false, true)
        config.translateMapBanners.value() shouldBe false

        config.signTooltips.setValue(false, true)
        config.signTooltips.value() shouldBe false

        config.signRaycastIntervalMs.setValue(200, true)
        config.signRaycastIntervalMs.value() shouldBe 200

        config.translationPlugin.setValue("libretranslate", true)
        config.translationPlugin.value() shouldBe "libretranslate"

        config.detectionPlugin.setValue("libretranslate", true)
        config.detectionPlugin.value() shouldBe "libretranslate"

        config.onnxExecutionThreads.setValue(4, true)
        config.onnxExecutionThreads.value() shouldBe 4

        config.enabled.setValue(false, true)
        config.enabled.value() shouldBe false

        config.targetLanguage.setValue("es", true)
        config.targetLanguage.value() shouldBe "es"

        config.showOriginalKey.setValue(Key.KEY_PERIOD, true)
        config.showOriginalKey.value() shouldBe Key.KEY_PERIOD
    }
})
