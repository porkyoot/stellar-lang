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

        val translator = OnnxTranslationPlugin()
        val translated = translator.translate("Hello world", "en", "es")
        translated shouldBe "Hello world"

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
})
