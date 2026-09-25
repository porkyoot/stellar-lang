@file:Suppress(
    "TooGenericExceptionCaught",
    "LargeClass",
    "MagicNumber",
    "ReturnCount",
    "UnusedParameter",
    "UnderscoresInNumericLiterals",
    "LongMethod",
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "StringLiteralDuplication",
    "TooManyFunctions",
)

package com.stellar.lang.plugin.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes machine learning inference locally using ONNX Runtime.
 */
object OnnxInferenceEngine : AutoCloseable {
    private val logger: Logger = LoggerFactory.getLogger("StellarLang-OnnxInference")
    private val inferenceLock = Any()

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var envInitFailed: Boolean = false

    @Volatile
    var envInitErrorMessage: String? = null
        private set

    @Volatile
    private var detectionSession: OrtSession? = null

    @Volatile
    private var loadedDetectionModelPath: String? = null

    @Volatile
    private var detectionVocab: Map<String, Int>? = null

    @Volatile
    private var loadedVocabPath: String? = null

    private val translationSessions = ConcurrentHashMap<String, OrtSession>()

    private val loadedTranslationModelPaths = ConcurrentHashMap<String, String>()

    private val decoderSessions = ConcurrentHashMap<String, OrtSession>()

    private val loadedDecoderModelPaths = ConcurrentHashMap<String, String>()

    // Mapping of FLORES-200 class indices to standard 2-letter ISO language codes
    private val idToLanguage = arrayOf(
        "lt", "fon", "rw", "km", "bjn", "prs", "wo", "rn", "en", "gd",
        "lv", "ny", "kac", "lua", "tk", "tpi", "gn", "xh", "bm", "mi",
        "sa", "is", "ks", "be", "he", "zh", "ba", "fr", "pt", "uk",
        "umb", "kn", "sm", "sq", "kbp", "ln", "ur", "yo", "az", "lb",
        "tw", "hi", "tl", "as", "om", "el", "taq", "nso", "da", "fa",
        "pa", "war", "mr", "mni", "ar", "sc", "vec", "or", "lg", "ltg",
        "gu", "it", "sv", "cjk", "ace", "taq", "cat", "ms", "hu", "kk",
        "pl", "ban", "nus", "ar", "ar", "es", "sk", "hr", "crh", "tr",
        "bs", "ss", "ki", "yi", "sd", "ha", "ta", "mg", "ku", "ace",
        "mk", "lij", "dyu", "mos", "ay", "ast", "fj", "lmo", "zh", "no",
        "hy", "am", "jv", "sg", "mai", "lo", "uz", "my", "fi", "kr",
        "tt", "ar", "dz", "pag", "ky", "sn", "zu", "kab", "fur", "ku",
        "vi", "ml", "bem", "so", "ar", "szl", "tg", "te", "qu", "de",
        "bjn", "az", "eu", "cs", "nl", "shn", "bg", "kam", "kmb", "ro",
        "bho", "gl", "awa", "th", "li", "ht", "mag", "kg", "ps", "ka",
        "mn", "ar", "kr", "ko", "oc", "lus", "ar", "eo", "pap", "ig",
        "fo", "bn", "zh", "ceb", "luo", "sr", "id", "sl", "min", "scn",
        "ar", "si", "mt", "kea", "ug", "ne", "ks", "bug", "hne", "sat",
        "sw", "ts", "nn", "ru", "din", "su", "af", "ar", "ga", "st",
        "ee", "ff", "tum", "ilo", "cy", "ti", "tzm", "bo", "tn", "et",
        "ja",
    )

    @Synchronized
    fun getOrInitEnv(): OrtEnvironment? {
        if (envInitFailed) return null
        val existing = env
        if (existing != null) return existing
        return try {
            val created = OrtEnvironment.getEnvironment()
            env = created
            created
        } catch (ex: Throwable) {
            envInitFailed = true
            envInitErrorMessage = ex.message ?: ex.javaClass.simpleName
            logger.warn("ONNX Runtime native environment could not be initialized: {}", envInitErrorMessage)
            null
        }
    }

    fun isEnvironmentAvailable(): Boolean = getOrInitEnv() != null

    fun validateModel(modelFile: java.io.File): Boolean {
        if (!modelFile.exists() || modelFile.length() < 100L) return false
        val environment = getOrInitEnv() ?: return false
        return try {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
            }
            environment.createSession(modelFile.absolutePath, opts).use {
                it.inputNames.isNotEmpty()
            }
        } catch (ex: Throwable) {
            logger.warn("ONNX model validation failed for {}: {}", modelFile.name, ex.message)
            false
        }
    }

    private fun getExecutionThreads(): Int {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        return config?.onnxExecutionThreads?.value() ?: StellarLangConfig.DEFAULT_ONNX_THREADS
    }

    @Synchronized
    private fun getOrCreateDetectionSession(): OrtSession? {
        val environment = getOrInitEnv() ?: return null
        val modelFile = OnnxModelManager.getDetectionModelFile()
        if (!modelFile.exists() || modelFile.length() == 0L) {
            detectionSession?.close()
            detectionSession = null
            loadedDetectionModelPath = null
            return null
        }
        if (detectionSession != null && loadedDetectionModelPath == modelFile.absolutePath) {
            return detectionSession
        }
        detectionSession?.close()
        detectionSession = null
        loadedDetectionModelPath = null

        return runCatching {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(getExecutionThreads())
            }
            environment.createSession(modelFile.absolutePath, opts).also {
                detectionSession = it
                loadedDetectionModelPath = modelFile.absolutePath
                logger.info("Initialized ONNX detection session from {}", modelFile.name)
            }
        }.onFailure { ex ->
            logger.error("Failed to load ONNX detection model: {}", ex.message)
        }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateTranslationSession(targetLang: String): OrtSession? {
        val environment = getOrInitEnv() ?: return null
        val modelFile = OnnxModelManager.getTranslationModelFile(targetLang)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            translationSessions.remove(targetLang)?.close()
            loadedTranslationModelPaths.remove(targetLang)
            return null
        }
        val existing = translationSessions[targetLang]
        if (existing != null && loadedTranslationModelPaths[targetLang] == modelFile.absolutePath) {
            return existing
        }
        existing?.close()
        translationSessions.remove(targetLang)
        loadedTranslationModelPaths.remove(targetLang)

        return runCatching {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(getExecutionThreads())
            }
            environment.createSession(modelFile.absolutePath, opts).also {
                translationSessions[targetLang] = it
                loadedTranslationModelPaths[targetLang] = modelFile.absolutePath
                logger.info("Initialized ONNX translation session for '{}'", targetLang)
            }
        }.onFailure { ex ->
            logger.error("Failed to load ONNX translation model for '{}': {}", targetLang, ex.message)
        }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateDecoderSession(targetLang: String): OrtSession? {
        val environment = getOrInitEnv() ?: return null
        val modelFile = OnnxModelManager.getTranslationDecoderFile(targetLang)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            decoderSessions.remove(targetLang)?.close()
            loadedDecoderModelPaths.remove(targetLang)
            return null
        }
        val existing = decoderSessions[targetLang]
        if (existing != null && loadedDecoderModelPaths[targetLang] == modelFile.absolutePath) {
            return existing
        }
        existing?.close()
        decoderSessions.remove(targetLang)
        loadedDecoderModelPaths.remove(targetLang)

        return runCatching {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(getExecutionThreads())
            }
            environment.createSession(modelFile.absolutePath, opts).also {
                decoderSessions[targetLang] = it
                loadedDecoderModelPaths[targetLang] = modelFile.absolutePath
                logger.info("Initialized ONNX decoder session for '{}'", targetLang)
            }
        }.onFailure { ex ->
            logger.error("Failed to load ONNX decoder model for '{}': {}", targetLang, ex.message)
        }.getOrNull()
    }

    fun detectLanguage(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val environment = getOrInitEnv() ?: return null
        val session = getOrCreateDetectionSession() ?: return null

        return synchronized(inferenceLock) {
            runCatching {
                val tokens = simpleTokenize(trimmed, maxLen = 64)
                val seqLen = tokens.size.toLong()

                val tokenBuffer = LongBuffer.wrap(tokens)
                val maskBuffer = LongBuffer.wrap(LongArray(tokens.size) { 1L })
                val typeBuffer = LongBuffer.wrap(LongArray(tokens.size) { 0L })

                val inputTensor = OnnxTensor.createTensor(environment, tokenBuffer, longArrayOf(1, seqLen))
                var maskTensor: OnnxTensor? = null
                var typeTensor: OnnxTensor? = null
                var results: OrtSession.Result? = null
                try {
                    maskTensor = OnnxTensor.createTensor(environment, maskBuffer, longArrayOf(1, seqLen))
                    typeTensor = OnnxTensor.createTensor(environment, typeBuffer, longArrayOf(1, seqLen))

                    val inputs = mutableMapOf<String, OnnxTensor>(
                        "input_ids" to inputTensor,
                        "attention_mask" to maskTensor,
                    )
                    if (session.inputNames.contains("token_type_ids")) {
                        inputs["token_type_ids"] = typeTensor
                    }

                    results = session.run(inputs)
                    val outputTensor = results.get(0) as? OnnxTensor
                    val logits = (outputTensor?.value as? Array<*>)?.get(0) as? FloatArray

                    if (logits != null) {
                        var maxIdx = 0
                        var maxVal = Float.NEGATIVE_INFINITY
                        for (i in logits.indices) {
                            if (logits[i] > maxVal) {
                                maxVal = logits[i]
                                maxIdx = i
                            }
                        }

                        if (maxIdx in idToLanguage.indices) {
                            idToLanguage[maxIdx]
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } finally {
                    results?.close()
                    typeTensor?.close()
                    maskTensor?.close()
                    inputTensor.close()
                }
            }.onFailure { ex ->
                logger.warn("ONNX language detection failed: {}", ex.message)
            }.getOrNull()
        }
    }

    fun translate(text: String, sourceLang: String?, targetLang: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val environment = getOrInitEnv() ?: return null
        val session = getOrCreateTranslationSession(targetLang) ?: return null

        return synchronized(inferenceLock) {
            runCatching {
                val vocab = getOrLoadTranslationVocab(targetLang)
                // Encode input sequence
                val tokens = simpleTokenize(trimmed, maxLen = 128, vocab = vocab)
                val seqLen = tokens.size.toLong()
                val tokenBuffer = LongBuffer.wrap(tokens)
                val inputTensor = OnnxTensor.createTensor(environment, tokenBuffer, longArrayOf(1, seqLen))
                var maskTensor: OnnxTensor? = null
                var results: OrtSession.Result? = null
                try {
                    val maskBuffer = LongBuffer.wrap(LongArray(tokens.size) { 1L })
                    maskTensor = OnnxTensor.createTensor(environment, maskBuffer, longArrayOf(1, seqLen))

                    val inputs = mutableMapOf<String, OnnxTensor>("input_ids" to inputTensor)
                    if (session.inputNames.contains("attention_mask")) {
                        inputs["attention_mask"] = maskTensor
                    }
                    results = session.run(inputs)
                    val outputTensor = results.get(0) as? OnnxTensor
                    val outputType = outputTensor?.info?.type

                    if (outputType == ai.onnxruntime.OnnxJavaType.INT64) {
                        val outTokens = when (val value = outputTensor.value) {
                            is Array<*> -> value.firstOrNull() as? LongArray ?: LongArray(0)
                            is LongArray -> value
                            else -> LongArray(0)
                        }
                        if (vocab != null && outTokens.isNotEmpty()) {
                            OnnxWordPieceTokenizer.detokenize(outTokens, vocab).ifBlank { null }
                        } else {
                            null
                        }
                    } else {
                        val decoderSession = getOrCreateDecoderSession(targetLang)
                        val canRunSeq2Seq = decoderSession != null && vocab != null
                        val hasTensors = outputTensor != null && maskTensor != null
                        if (canRunSeq2Seq && hasTensors) {
                            val context = Seq2SeqContext(
                                decoderSession = decoderSession,
                                encoderHiddenStates = outputTensor,
                                encoderAttentionMask = maskTensor,
                                vocab = vocab,
                            )
                            runAutoregressiveDecoding(environment, context)
                        } else {
                            logger.debug(
                                "ONNX translation model for '{}' is encoder-only (output: {}); " +
                                    "decoder required for generation",
                                targetLang,
                                outputType,
                            )
                            null
                        }
                    }
                } finally {
                    results?.close()
                    maskTensor?.close()
                    inputTensor.close()
                }
            }.onFailure { ex ->
                logger.warn("ONNX translation failed for target '{}': {}", targetLang, ex.message)
            }.getOrNull()
        }
    }

    private data class Seq2SeqContext(
        val decoderSession: OrtSession,
        val encoderHiddenStates: OnnxTensor,
        val encoderAttentionMask: OnnxTensor,
        val vocab: Map<String, Int>,
    )

    @Suppress("MagicNumber", "NestedBlockDepth", "ReturnCount")
    private fun runAutoregressiveDecoding(
        environment: OrtEnvironment,
        ctx: Seq2SeqContext,
    ): String? {
        val startTokenId = (ctx.vocab["<pad>"] ?: 64171).toLong()
        val eosTokenId = (ctx.vocab["</s>"] ?: 0).toLong()
        val decTokens = mutableListOf<Long>(startTokenId)
        val maxTokens = 64
        var finished = false
        var stepCount = 0

        while (stepCount < maxTokens && !finished) {
            stepCount++
            val decSeqLen = decTokens.size.toLong()
            val decBuffer = LongBuffer.wrap(decTokens.toLongArray())
            val decInputTensor = OnnxTensor.createTensor(environment, decBuffer, longArrayOf(1, decSeqLen))
            var decResults: OrtSession.Result? = null
            try {
                val decInputs = mapOf(
                    "encoder_attention_mask" to ctx.encoderAttentionMask,
                    "input_ids" to decInputTensor,
                    "encoder_hidden_states" to ctx.encoderHiddenStates,
                )
                decResults = ctx.decoderSession.run(decInputs)
                val logitsTensor = decResults.get(0) as? OnnxTensor
                val stepLogits = when (val logitsVal = logitsTensor?.value) {
                    is Array<*> -> {
                        val batch0 = logitsVal.firstOrNull() as? Array<*>
                        batch0?.getOrNull(decTokens.size - 1) as? FloatArray
                    }
                    else -> null
                }
                if (stepLogits == null) {
                    finished = true
                } else {
                    if (startTokenId.toInt() in stepLogits.indices) {
                        stepLogits[startTokenId.toInt()] = Float.NEGATIVE_INFINITY
                    }

                    var bestToken = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (i in stepLogits.indices) {
                        if (stepLogits[i] > bestScore) {
                            bestScore = stepLogits[i]
                            bestToken = i
                        }
                    }

                    if (bestToken.toLong() == eosTokenId) {
                        finished = true
                    } else {
                        decTokens.add(bestToken.toLong())
                    }
                }
            } finally {
                decResults?.close()
                decInputTensor.close()
            }
        }

        val generated = decTokens.drop(1).toLongArray()
        if (generated.isEmpty()) return null
        return OnnxWordPieceTokenizer.detokenize(generated, ctx.vocab).ifBlank { null }
    }

    fun isTranslationModelGenerative(targetLang: String): Boolean {
        if (OnnxModelManager.isDecoderReady(targetLang)) return true
        val session = getOrCreateTranslationSession(targetLang) ?: return false
        val firstInfo = session.outputInfo.values.firstOrNull()?.info as? ai.onnxruntime.TensorInfo
        return firstInfo?.type == ai.onnxruntime.OnnxJavaType.INT64
    }

    internal fun getOrLoadTranslationVocab(targetLang: String): Map<String, Int>? {
        val vocabFile = OnnxModelManager.getTranslationVocabFile(targetLang)
        if (vocabFile.exists() && vocabFile.length() > 0L) {
            return OnnxWordPieceTokenizer.loadVocab(vocabFile)
        }
        return getOrLoadDetectionVocab()
    }

    internal fun getOrLoadDetectionVocab(): Map<String, Int>? {
        val vocabFile = OnnxModelManager.getDetectionVocabFile()
        if (!vocabFile.exists() || vocabFile.length() == 0L) {
            detectionVocab = null
            loadedVocabPath = null
            return null
        }
        val cached = detectionVocab
        if (cached != null && loadedVocabPath == vocabFile.absolutePath) {
            return cached
        }
        return OnnxWordPieceTokenizer.loadVocab(vocabFile)?.also {
            detectionVocab = it
            loadedVocabPath = vocabFile.absolutePath
        }
    }

    internal fun wordPieceTokenize(text: String, maxLen: Int, vocab: Map<String, Int>): LongArray =
        OnnxWordPieceTokenizer.tokenize(text, maxLen, vocab)

    internal fun simpleTokenize(text: String, maxLen: Int, vocab: Map<String, Int>? = null): LongArray {
        val activeVocab = vocab ?: getOrLoadDetectionVocab()
        if (activeVocab != null) {
            if (OnnxWordPieceTokenizer.isSentencePiece(activeVocab)) {
                return OnnxWordPieceTokenizer.tokenizeSentencePiece(text, maxLen, activeVocab)
            }
            return wordPieceTokenize(text, maxLen, activeVocab)
        }
        // Deterministic character/byte tokenization compatible with multilingual BERT input
        val tokens = mutableListOf<Long>()
        tokens.add(101L) // [CLS]
        for (ch in text.take(maxLen - 2)) {
            tokens.add(ch.code.toLong() % 30000L + 1000L)
        }
        tokens.add(102L) // [SEP]
        return tokens.toLongArray()
    }

    fun resetSessions() {
        runCatching { detectionSession?.close() }
        detectionSession = null
        loadedDetectionModelPath = null
        detectionVocab = null
        loadedVocabPath = null
        translationSessions.values.forEach { runCatching { it.close() } }
        translationSessions.clear()
        loadedTranslationModelPaths.clear()
        decoderSessions.values.forEach { runCatching { it.close() } }
        decoderSessions.clear()
        loadedDecoderModelPaths.clear()
    }

    internal fun resetEnvironment() {
        resetSessions()
        runCatching { env?.close() }
        env = null
        envInitFailed = false
        envInitErrorMessage = null
    }

    override fun close() {
        resetEnvironment()
    }
}
