@file:Suppress("LargeClass")

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
        translated shouldBe "hola mundo"
        // Second call exercises session caching hit
        val cachedTrans = translator.translate("Hello world 2", "en", "es")
        cachedTrans shouldBe "hola mundo dos"

        val batch = translator.translateBatch(listOf("Hello", "World"), "en", "es")
        batch shouldBe listOf("hola", "mundo")

        OnnxInferenceEngine.isTranslationModelGenerative("es") shouldBe true
        OnnxInferenceEngine.isTranslationModelGenerative("non_existent") shouldBe false

        OnnxInferenceEngine.resetSessions()
    }

    test("OnnxTranslationPlugin with attention_mask model") {
        val tempDir = java.io.File("build/test_trans_mask_${System.nanoTime()}")
        val transDir = java.io.File(tempDir, "translation/es").apply { mkdirs() }
        java.io.File("src/test/resources/test_models/detection/model.onnx").copyTo(java.io.File(transDir, "model.onnx"))

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxModelDir.setValue(tempDir.path, false)
        OnnxInferenceEngine.resetSessions()

        val translator = OnnxTranslationPlugin()
        translator.translate("Hello world", "en", "es") shouldBe null

        tempDir.deleteRecursively()
        OnnxInferenceEngine.resetSessions()
    }

    test("WordPiece tokenization normalizes accents and splits words") {
        val mockVocab = mapOf(
            "[UNK]" to 0,
            "[CLS]" to 1,
            "[SEP]" to 2,
            "ta" to 10,
            "mere" to 11,
            "est" to 12,
            "pas" to 13,
            "pret" to 14,
            "##e" to 15,
        )
        val tokens = OnnxInferenceEngine.wordPieceTokenize("Ta mère est pas prête", 32, mockVocab)
        tokens shouldBe longArrayOf(1L, 10L, 11L, 12L, 13L, 14L, 15L, 2L)

        val unkTokens = OnnxInferenceEngine.wordPieceTokenize("xyz", 32, mockVocab)
        unkTokens shouldBe longArrayOf(1L, 0L, 2L)

        val shortTokens = OnnxInferenceEngine.wordPieceTokenize("hello world 2", 3, mockVocab)
        shortTokens.size shouldBe 3

        OnnxWordPieceTokenizer.loadVocab(java.io.File("build/non_existent_vocab.json")) shouldBe null

        val badJsonFile = java.io.File("build/bad_vocab.json").apply { writeText("not json") }
        OnnxWordPieceTokenizer.loadVocab(badJsonFile) shouldBe null
        badJsonFile.delete()

        val emptyObjFile = java.io.File("build/empty_vocab.json").apply { writeText("{}") }
        OnnxWordPieceTokenizer.loadVocab(emptyObjFile) shouldBe null
        emptyObjFile.delete()

        val punctuationParts = OnnxWordPieceTokenizer.splitWordsAndPunctuation("hello, world! 123")
        punctuationParts shouldBe listOf("hello", ",", "world", "!", "123")

        val detokVocab = mapOf(
            "[UNK]" to 0,
            "[CLS]" to 1,
            "[SEP]" to 2,
            "[PAD]" to 3,
            "hello" to 10,
            "##world" to 11,
            "!" to 12,
            "\u2581salut" to 13,
            "\u2581monde" to 14,
        )
        val decodedWordPiece = OnnxWordPieceTokenizer.detokenize(longArrayOf(1L, 10L, 11L, 12L, 2L), detokVocab)
        decodedWordPiece shouldBe "helloworld!"

        val decodedSp = OnnxWordPieceTokenizer.detokenize(longArrayOf(1L, 13L, 14L, 2L), detokVocab)
        decodedSp shouldBe "salut monde"

        val decodedEmpty = OnnxWordPieceTokenizer.detokenize(longArrayOf(1L, 2L, 3L), detokVocab)
        decodedEmpty shouldBe ""
    }

    test("OnnxLanguageDetectorPlugin detects French accurately with real tokenizer") {
        val candidates = listOf(
            java.io.File("run/client/config/stellar_lang/models"),
            java.io.File("../run/client/config/stellar_lang/models"),
        )
        val realModelsDir = candidates.firstOrNull {
            java.io.File(it, "detection/model.onnx").exists() && java.io.File(it, "detection/tokenizer.json").exists()
        }
        if (realModelsDir != null) {
            val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
            config.onnxModelDir.setValue(realModelsDir.path, false)
            OnnxInferenceEngine.resetSessions()

            val detector = OnnxLanguageDetectorPlugin()
            val detected = detector.detectLanguage("Ta mère est pas prête")
            detected shouldBe "fr"

            val detectedEn = detector.detectLanguage("Your mother is not ready")
            detectedEn shouldBe "en"

            val detectedEs = detector.detectLanguage("Buenos días amigo")
            detectedEs shouldBe "es"
            OnnxInferenceEngine.resetSessions()
        }
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

        val field = OnnxModelManager::class.java.getDeclaredField("failureCooldowns")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cooldowns = field.get(OnnxModelManager) as MutableMap<String, Long>
        cooldowns["detection"] = System.currentTimeMillis()
        cooldowns["translation-en"] = System.currentTimeMillis()

        val detector = OnnxLanguageDetectorPlugin()
        detector.detectLanguage("Bonjour le monde") shouldBe null

        val translator = OnnxTranslationPlugin()
        translator.translate("Bonjour le monde", "fr", "en") shouldBe null

        OnnxModelManager.reset()
    }

    test("OnnxWordPieceTokenizer detokenize handles all branches") {
        val vocab = mapOf(
            "[PAD]" to 0,
            "[UNK]" to 1,
            "[CLS]" to 2,
            "[SEP]" to 3,
            "<pad>" to 4,
            "<s>" to 5,
            "</s>" to 6,
            "<unk>" to 7,
            "hello" to 8,
            "world" to 9,
            "play" to 10,
            "##ing" to 11,
            "\u2581start" to 12,
            "\u2581next" to 13,
            "." to 14,
            "!" to 15,
        )

        // Empty tokens
        OnnxWordPieceTokenizer.detokenize(longArrayOf(), vocab) shouldBe ""

        // Special tokens ignored
        OnnxWordPieceTokenizer.detokenize(longArrayOf(2L, 4L, 5L, 6L, 7L, 3L), vocab) shouldBe ""

        // Unknown token ID ignored
        OnnxWordPieceTokenizer.detokenize(longArrayOf(999L), vocab) shouldBe ""

        // WordPiece subtoken joining
        OnnxWordPieceTokenizer.detokenize(longArrayOf(10L, 11L), vocab) shouldBe "playing"

        // SentencePiece prefix joining
        OnnxWordPieceTokenizer.detokenize(longArrayOf(12L, 13L), vocab) shouldBe "start next"

        // Standard words and punctuation
        OnnxWordPieceTokenizer.detokenize(longArrayOf(8L, 9L, 14L, 15L), vocab) shouldBe "hello world.!"
    }

    test("OnnxInferenceEngine isTranslationModelGenerative returns false for missing or non-generative models") {
        OnnxInferenceEngine.isTranslationModelGenerative("missing_lang") shouldBe false
        OnnxInferenceEngine.isTranslationModelGenerative("en") shouldBe false
    }

    test("OnnxInferenceEngine and OnnxTranslationPlugin translate text through ONNX model") {
        val testModelsDir = java.io.File("src/test/resources/test_models")
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxModelDir.setValue(testModelsDir.absolutePath, false)
        OnnxInferenceEngine.resetSessions()

        // Verify model is generative
        OnnxInferenceEngine.isTranslationModelGenerative("es") shouldBe true

        // Direct inference engine translation
        val translated = OnnxInferenceEngine.translate("hello world", "en", "es")
        translated shouldBe "hola mundo"

        val translatedHello = OnnxInferenceEngine.translate("hello", "en", "es")
        translatedHello shouldBe "hola"

        val translatedWorld = OnnxInferenceEngine.translate("world", "en", "es")
        translatedWorld shouldBe "mundo"

        // OnnxTranslationPlugin single and batch translation through model
        val plugin = OnnxTranslationPlugin()
        val pluginRes = plugin.translate("hello world", "en", "es")
        pluginRes shouldBe "hola mundo"

        val batchRes = plugin.translateBatch(listOf("hello", "world", "2"), "en", "es")
        batchRes shouldBe listOf("hola", "mundo", "dos")

        OnnxInferenceEngine.resetSessions()
    }

    test("OnnxInferenceEngine resetEnvironment, validation, blank inputs and thread configuration") {
        OnnxInferenceEngine.translate("", "en", "es") shouldBe null
        OnnxInferenceEngine.translate("   ", "en", "es") shouldBe null
        OnnxInferenceEngine.detectLanguage("") shouldBe null
        OnnxInferenceEngine.detectLanguage("   ") shouldBe null

        val nonExistent = java.io.File("build/non_existent.onnx")
        OnnxInferenceEngine.validateModel(nonExistent) shouldBe false

        val tinyFile = java.io.File.createTempFile("tiny", ".onnx").apply {
            writeBytes(ByteArray(10))
            deleteOnExit()
        }
        OnnxInferenceEngine.validateModel(tinyFile) shouldBe false

        val validModel = java.io.File("src/test/resources/test_models/detection/model.onnx")
        OnnxInferenceEngine.validateModel(validModel) shouldBe true

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxExecutionThreads.setValue(4, false)

        OnnxInferenceEngine.isEnvironmentAvailable() shouldBe true
        OnnxInferenceEngine.resetEnvironment()
        OnnxInferenceEngine.isEnvironmentAvailable() shouldBe true
    }

    test("OnnxWordPieceTokenizer SentencePiece tokenization and array vocab loading") {
        val spVocab = mapOf(
            "</s>" to 0,
            "<unk>" to 1,
            "\u2581hello" to 10,
            "\u2581world" to 11,
            "!" to 12,
        )
        OnnxWordPieceTokenizer.isSentencePiece(spVocab) shouldBe true
        val tokens = OnnxWordPieceTokenizer.tokenizeSentencePiece("hello world!", 10, spVocab)
        tokens shouldBe longArrayOf(10L, 11L, 12L, 0L)

        val unkTokens = OnnxWordPieceTokenizer.tokenizeSentencePiece("xyz", 10, spVocab)
        unkTokens shouldBe longArrayOf(1L, 1L, 1L, 1L, 0L)

        val arrayVocabFile = java.io.File.createTempFile("array_vocab", ".json").apply {
            writeText("""{"model": {"vocab": [["</s>", 0.0], ["<unk>", 0.0], ["\u2581hi", -1.0]]}}""")
            deleteOnExit()
        }
        val loaded = OnnxWordPieceTokenizer.loadVocab(arrayVocabFile)
        loaded shouldNotBe null
        loaded?.get("\u2581hi") shouldBe 2

        val emptyVocabFile = java.io.File.createTempFile("empty_array", ".json").apply {
            writeText("""{"model": {"vocab": []}}""")
            deleteOnExit()
        }
        OnnxWordPieceTokenizer.loadVocab(emptyVocabFile) shouldBe null

        OnnxWordPieceTokenizer.isSentencePiece(mapOf("[CLS]" to 0, "test" to 1)) shouldBe false
        OnnxWordPieceTokenizer.isSentencePiece(mapOf("</s>" to 0, "test" to 1)) shouldBe true
        OnnxWordPieceTokenizer.isSentencePiece(mapOf("\u2581test" to 0)) shouldBe true
        OnnxWordPieceTokenizer.isSentencePiece(mapOf("test" to 0)) shouldBe false
    }

    test("OnnxInferenceEngine performs seq2seq autoregressive decoding with opus-mt model") {
        val realModelDir = java.io.File("config/stellar_lang/models")
        val enDecoder = java.io.File(realModelDir, "translation/en/decoder.onnx")
        if (enDecoder.exists()) {
            val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
            config.onnxModelDir.setValue(realModelDir.absolutePath, false)
            OnnxInferenceEngine.resetSessions()

            OnnxInferenceEngine.isTranslationModelGenerative("en") shouldBe true
            val translated = OnnxInferenceEngine.translate("Ta mère est pas prête", null, "en")
            translated shouldBe "Your mother's not ready"

            // Second call exercises decoder session caching hit
            val cachedTrans = OnnxInferenceEngine.translate("Ta mère est pas prête", null, "en")
            cachedTrans shouldBe "Your mother's not ready"

            OnnxInferenceEngine.resetSessions()
        }
    }

    test("OnnxInferenceEngine handles corrupt decoder model and empty inputs") {
        OnnxInferenceEngine.detectLanguage("") shouldBe null
        OnnxInferenceEngine.detectLanguage("   ") shouldBe null
        OnnxInferenceEngine.translate("", null, "en") shouldBe null
        OnnxInferenceEngine.translate("   ", null, "en") shouldBe null

        val tempDir = java.io.File("build/test_corrupt_dec_${System.nanoTime()}")
        val transDir = java.io.File(tempDir, "translation/corrupt").apply { mkdirs() }
        java.io.File("src/test/resources/test_models/detection/model.onnx").copyTo(java.io.File(transDir, "model.onnx"))

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxModelDir.setValue(tempDir.path, false)
        OnnxInferenceEngine.resetSessions()

        // Should return false because decoder does not exist and encoder output is not INT64
        OnnxInferenceEngine.isTranslationModelGenerative("corrupt") shouldBe false

        // Now write corrupt decoder.onnx to test getOrCreateDecoderSession failure handling
        java.io.File(transDir, "decoder.onnx").writeText("corrupt onnx content")
        OnnxInferenceEngine.translate("Hello world", null, "corrupt") shouldBe null

        tempDir.deleteRecursively()
        OnnxInferenceEngine.resetSessions()
    }
})
