package com.stellar.lang.plugin.onnx

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class OnnxPluginSpec : FunSpec({
    beforeEach {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
        config.onnxModelDir.setValue("build/no_models", false)
        config.targetLanguage.setValue("en", false)
        config.onnxAutoDownload.setValue(false, false)
        OnnxInferenceEngine.resetSessions()
    }

    afterSpec {
        OnnxInferenceEngine.resetSessions()
    }

    test("OnnxLanguageDetectorPlugin metadata and status") {
        val plugin = OnnxLanguageDetectorPlugin()
        plugin.id shouldBe "onnx"
        plugin.displayName shouldBe "ONNX Runtime (Local Offline)"
        plugin.description shouldContain "ONNX"
        plugin.getStatus() shouldNotBe null

        plugin.detectLanguage("") shouldBe null
        plugin.detectLanguage("   ") shouldBe null
        plugin.detectLanguage("Hello without model") shouldBe null
    }

    test("OnnxLanguageDetectorPlugin and OnnxInferenceEngine execution with model") {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxModelDir.setValue("src/test/resources/test_models", false)

        val detector = OnnxLanguageDetectorPlugin()
        val detected = detector.detectLanguage("Hello world")
        detected shouldBe "en"
        // Second call exercises session caching hit
        val cachedDetect = detector.detectLanguage("Hello world 2")
        cachedDetect shouldBe "en"

        val translator = OnnxTranslationPlugin()
        val translated = translator.translate("Hello world", "en", "es")
        translated shouldBe "Hello world"
        // Second call exercises session caching hit
        val cachedTrans = translator.translate("Hello world 2", "en", "es")
        cachedTrans shouldBe "Hello world 2"

        val batch = translator.translateBatch(listOf("Hello", "World"), "en", "es")
        batch shouldBe listOf("Hello", "World")

        OnnxInferenceEngine.resetSessions()
    }

    test("OnnxTranslationPlugin metadata and status") {
        val plugin = OnnxTranslationPlugin()
        plugin.id shouldBe "onnx"
        plugin.displayName shouldBe "ONNX Runtime (Local Offline)"
        plugin.description shouldContain "ONNX"
        plugin.getStatus() shouldNotBe null

        plugin.translate("", null, "en") shouldBe null
        plugin.translate("   ", null, "en") shouldBe null
        plugin.translate("Hello without model", null, "en") shouldBe null

        val batch = plugin.translateBatch(listOf("", "  "), null, "en")
        batch shouldBe listOf("", "  ")

        val batchWithoutModel = plugin.translateBatch(listOf("Hello", "World"), null, "fr")
        batchWithoutModel shouldBe listOf("Hello", "World")
    }

    test("OnnxInferenceEngine tokenization and life cycle") {
        val tokens = OnnxInferenceEngine.simpleTokenize("Hello World", 32)
        tokens.size shouldNotBe 0
        tokens.first() shouldBe 101L // [CLS]
        tokens.last() shouldBe 102L // [SEP]

        val longTokens = OnnxInferenceEngine.simpleTokenize("A".repeat(100), 10)
        longTokens.size shouldBe 10

        OnnxInferenceEngine.detectLanguage("") shouldBe null
        OnnxInferenceEngine.translate("", null, "en") shouldBe null
        OnnxInferenceEngine.envInitErrorMessage shouldBe null
        OnnxInferenceEngine.getOrInitEnv() shouldNotBe null

        OnnxInferenceEngine.close()
    }

    test("OnnxInferenceEngine handles corrupted model files gracefully") {
        val tempDir = java.io.File("build/corrupt_models_${System.nanoTime()}")
        val detDir = java.io.File(tempDir, "detection").apply { mkdirs() }
        val detFile = java.io.File(detDir, "model.onnx")
        detFile.writeText("corrupt onnx data")

        val transDir = java.io.File(tempDir, "translation/es").apply { mkdirs() }
        val transFile = java.io.File(transDir, "model.onnx")
        transFile.writeText("corrupt onnx data")

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxModelDir.setValue(tempDir.path, false)
        OnnxInferenceEngine.resetSessions()

        OnnxInferenceEngine.detectLanguage("Hello") shouldBe null
        OnnxInferenceEngine.translate("Hello", "en", "es") shouldBe null

        tempDir.deleteRecursively()
        OnnxInferenceEngine.resetSessions()
    }

    test("OnnxInferenceEngine environment availability, model validation, and reset") {
        OnnxInferenceEngine.isEnvironmentAvailable() shouldBe true

        val validModel = java.io.File("src/test/resources/test_models/detection/model.onnx")
        OnnxInferenceEngine.validateModel(validModel) shouldBe true

        val missingModel = java.io.File("build/non_existent.onnx")
        OnnxInferenceEngine.validateModel(missingModel) shouldBe false

        val smallCorrupt = java.io.File("build/small_corrupt.onnx").apply { writeText("bad") }
        OnnxInferenceEngine.validateModel(smallCorrupt) shouldBe false
        smallCorrupt.delete()

        OnnxInferenceEngine.resetEnvironment()
        OnnxInferenceEngine.isEnvironmentAvailable() shouldBe true
    }

    test("PluginRegistry normalizePluginId and active plugin resolution") {
        com.stellar.lang.plugin.PluginRegistry.normalizePluginId("ONNX") shouldBe "onnx"
        com.stellar.lang.plugin.PluginRegistry.normalizePluginId("onnx runtime (local offline)") shouldBe "onnx"
        com.stellar.lang.plugin.PluginRegistry.normalizePluginId("LibreTranslate") shouldBe "libretranslate"
        com.stellar.lang.plugin.PluginRegistry.normalizePluginId("custom_plugin") shouldBe "custom_plugin"

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.translationPlugin.setValue("onnx runtime (local offline)", false)
        config.detectionPlugin.setValue("onnx runtime (local offline)", false)

        com.stellar.lang.plugin.PluginRegistry.getActiveTranslator().id shouldBe "onnx"
        com.stellar.lang.plugin.PluginRegistry.getActiveDetector().id shouldBe "onnx"
    }

    test("Onnx plugins trigger auto download when models are missing and autoDownload is enabled") {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxAutoDownload.setValue(true, false)
        config.onnxModelDir.setValue("build/missing_models_${System.nanoTime()}", false)

        val detector = OnnxLanguageDetectorPlugin()
        detector.detectLanguage("Bonjour le monde") shouldBe null

        val translator = OnnxTranslationPlugin()
        translator.translate("Bonjour le monde", "fr", "en") shouldBe null

        OnnxModelManager.reset()
    }
})
