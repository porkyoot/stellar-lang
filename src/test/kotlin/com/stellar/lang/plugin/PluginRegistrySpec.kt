package com.stellar.lang.plugin

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class PluginRegistrySpec : FunSpec({
    beforeEach {
        PluginRegistry.registerDefaults()
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
        config.translationPlugin.setValue("onnx", false)
        config.detectionPlugin.setValue("onnx", false)
    }

    test("PluginStatus variants produce informative display texts") {
        PluginStatus.Ready().displayText() shouldContain "Ready"
        PluginStatus.Ready("Engine loaded").displayText() shouldContain "Engine loaded"
        PluginStatus.Downloading(42, "model.onnx").displayText() shouldContain "42%"
        PluginStatus.NotConfigured("API key missing").displayText() shouldContain "API key missing"
        PluginStatus.Error("Connection timeout").displayText() shouldContain "Connection timeout"
        PluginStatus.Disabled.displayText() shouldContain "Disabled"
    }

    test("PluginRegistry registers defaults and resolves plugins") {
        PluginRegistry.getAllDetectors().size shouldBe 2
        PluginRegistry.getAllTranslators().size shouldBe 2

        PluginRegistry.getDetector("onnx") shouldNotBe null
        PluginRegistry.getDetector("libretranslate") shouldNotBe null
        PluginRegistry.getTranslator("onnx") shouldNotBe null
        PluginRegistry.getTranslator("libretranslate") shouldNotBe null

        PluginRegistry.getDetector("unknown_id") shouldBe null
        PluginRegistry.getTranslator("unknown_id") shouldBe null
    }

    test("PluginRegistry resolves active plugins based on configuration") {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!

        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        PluginRegistry.getActiveTranslator().id shouldBe "libretranslate"
        PluginRegistry.getActiveDetector().id shouldBe "libretranslate"

        config.translationPlugin.setValue("onnx", false)
        config.detectionPlugin.setValue("onnx", false)
        PluginRegistry.getActiveTranslator().id shouldBe "onnx"
        PluginRegistry.getActiveDetector().id shouldBe "onnx"
    }

    test("PluginRegistry falls back when cleared") {
        PluginRegistry.clear()
        PluginRegistry.getAllDetectors().isEmpty() shouldBe true
        PluginRegistry.getAllTranslators().isEmpty() shouldBe true

        val fallbackDetector = PluginRegistry.getActiveDetector()
        fallbackDetector.id shouldBe "fallback"
        fallbackDetector.displayName shouldBe "Fallback Detector"
        fallbackDetector.description shouldNotBe ""
        fallbackDetector.getStatus() shouldBe PluginStatus.NotConfigured("No detector plugin available")
        fallbackDetector.detectLanguage("Hello") shouldBe null

        val fallbackTranslator = PluginRegistry.getActiveTranslator()
        fallbackTranslator.id shouldBe "fallback"
        fallbackTranslator.displayName shouldBe "Fallback Translator"
        fallbackTranslator.description shouldNotBe ""
        fallbackTranslator.getStatus() shouldBe PluginStatus.NotConfigured("No translation plugin available")
        fallbackTranslator.translate("Hello", "en", "es") shouldBe null
        fallbackTranslator.translateBatch(listOf("Hello"), "en", "es") shouldBe listOf("Hello")

        // Restore defaults
        PluginRegistry.registerDefaults()
        PluginRegistry.getAllDetectors().size shouldBe 2
    }

    test("custom plugin registration and default batch translation") {
        val customTranslator = object : TranslationPlugin {
            override val id: String = "custom_test"
            override val displayName: String = "Custom Test"
            override val description: String = "Test translator"
            override fun getStatus(): PluginStatus = PluginStatus.Ready("Test")
            override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
                return if (text == "fail") null else "$text-translated"
            }
        }
        PluginRegistry.registerTranslator(customTranslator)
        PluginRegistry.getTranslator("custom_test") shouldBe customTranslator

        val batch = customTranslator.translateBatch(listOf("A", "fail"), "en", "es")
        batch shouldBe listOf("A-translated", "fail")
    }
})
