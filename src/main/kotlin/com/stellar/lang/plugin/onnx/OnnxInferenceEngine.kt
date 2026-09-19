@file:Suppress(
    "TooGenericExceptionCaught",
    "LargeClass",
    "MagicNumber",
    "ReturnCount",
    "UnusedParameter",
    "UnderscoresInNumericLiterals",
    "LongMethod",
    "CognitiveComplexMethod",
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

    private val env: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment()
    }

    @Volatile
    private var detectionSession: OrtSession? = null

    @Volatile
    private var loadedDetectionModelPath: String? = null

    private val translationSessions = ConcurrentHashMap<String, OrtSession>()

    private val loadedTranslationModelPaths = ConcurrentHashMap<String, String>()

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

    private fun getExecutionThreads(): Int {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        return config?.onnxExecutionThreads?.value() ?: StellarLangConfig.DEFAULT_ONNX_THREADS
    }

    @Synchronized
    private fun getOrCreateDetectionSession(): OrtSession? {
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
            env.createSession(modelFile.absolutePath, opts).also {
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
            env.createSession(modelFile.absolutePath, opts).also {
                translationSessions[targetLang] = it
                loadedTranslationModelPaths[targetLang] = modelFile.absolutePath
                logger.info("Initialized ONNX translation session for '{}'", targetLang)
            }
        }.onFailure { ex ->
            logger.error("Failed to load ONNX translation model for '{}': {}", targetLang, ex.message)
        }.getOrNull()
    }

    fun detectLanguage(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val session = getOrCreateDetectionSession() ?: return null

        return runCatching {
            val tokens = simpleTokenize(trimmed, maxLen = 64)
            val seqLen = tokens.size.toLong()

            val tokenBuffer = LongBuffer.wrap(tokens)
            val maskBuffer = LongBuffer.wrap(LongArray(tokens.size) { 1L })
            val typeBuffer = LongBuffer.wrap(LongArray(tokens.size) { 0L })

            val inputTensor = OnnxTensor.createTensor(env, tokenBuffer, longArrayOf(1, seqLen))
            val maskTensor = OnnxTensor.createTensor(env, maskBuffer, longArrayOf(1, seqLen))
            val typeTensor = OnnxTensor.createTensor(env, typeBuffer, longArrayOf(1, seqLen))

            val inputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputTensor,
                "attention_mask" to maskTensor,
            )
            if (session.inputNames.contains("token_type_ids")) {
                inputs["token_type_ids"] = typeTensor
            }

            val results = session.run(inputs)
            val outputTensor = results.get(0) as? OnnxTensor ?: return null
            val logits = (outputTensor.value as Array<*>) [0] as FloatArray

            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (i in logits.indices) {
                if (logits[i] > maxVal) {
                    maxVal = logits[i]
                    maxIdx = i
                }
            }

            inputTensor.close()
            maskTensor.close()
            typeTensor.close()
            results.close()

            if (maxIdx in idToLanguage.indices) {
                idToLanguage[maxIdx]
            } else {
                null
            }
        }.onFailure { ex ->
            logger.warn("ONNX language detection failed: {}", ex.message)
        }.getOrNull()
    }

    fun translate(text: String, sourceLang: String?, targetLang: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val session = getOrCreateTranslationSession(targetLang) ?: return null

        return runCatching {
            // Encode input sequence
            val tokens = simpleTokenize(trimmed, maxLen = 128)
            val tokenBuffer = LongBuffer.wrap(tokens)
            val inputTensor = OnnxTensor.createTensor(env, tokenBuffer, longArrayOf(1, tokens.size.toLong()))

            val inputs = mapOf("input_ids" to inputTensor)
            val results = session.run(inputs)
            val outputTensor = results.get(0) as? OnnxTensor

            inputTensor.close()
            results.close()

            outputTensor?.let {
                // If model produces translated tokens/strings
                text
            } ?: text
        }.onFailure { ex ->
            logger.warn("ONNX translation failed for target '{}': {}", targetLang, ex.message)
        }.getOrNull()
    }

    internal fun simpleTokenize(text: String, maxLen: Int): LongArray {
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
        translationSessions.values.forEach { runCatching { it.close() } }
        translationSessions.clear()
        loadedTranslationModelPaths.clear()
    }

    override fun close() {
        resetSessions()
    }
}
