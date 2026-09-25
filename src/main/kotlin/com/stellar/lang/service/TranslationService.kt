@file:Suppress("LargeClass", "CognitiveComplexMethod")

package com.stellar.lang.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginRegistry
import com.stellar.lang.plugin.PluginStatus
import com.stellar.lang.plugin.libretranslate.LibreTranslatePlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service managing communication with LibreTranslate API, caching, and language detection.
 */
@Suppress(
    "TooManyFunctions",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
)
object TranslationService {
    private val logger: Logger = LoggerFactory.getLogger(StellarLangMod.MOD_ID)
    private val gson = Gson()
    private const val CONNECT_TIMEOUT_SECONDS = 4L
    private const val TIMEOUT_SECONDS = 5L
    private const val THREAD_POOL_SIZE = 3
    private const val RETRY_INTERVAL_SECONDS = 15L
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val APPLICATION_JSON = "application/json"
    private const val HEADER_CONTENT_TYPE = "Content-Type"
    private const val HEADER_ACCEPT = "Accept"
    private const val ERROR_SNIPPET_LENGTH = 80
    private const val UNKNOWN_LANG = "unknown"
    private const val MAX_ATTEMPTS_PER_PROVIDER = 2
    private const val RETRY_DELAY_MS = 50L

    @Volatile
    private var lastNetworkException: Throwable? = null

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    private val executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE) { runnable ->
        Thread(runnable, "StellarLang-Worker").apply { isDaemon = true }
    }

    private val retryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "StellarLang-RetryWorker").apply { isDaemon = true }
    }

    data class FailedRequest(
        val text: String,
        val targetLang: String,
        val failedAt: Long,
    )

    internal val failedRequests = ConcurrentHashMap<String, FailedRequest>()
    private val successListeners = CopyOnWriteArrayList<(TranslationResult) -> Unit>()

    init {
        retryExecutor.scheduleWithFixedDelay(
            { runCatching { retryFailedTranslations() } },
            RETRY_INTERVAL_SECONDS,
            RETRY_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private const val MIN_LANG_CODE_LENGTH = 2
    private const val MAX_LANG_CODE_LENGTH = 3
    private const val DEFAULT_FALLBACK_LANG = "en"
    private const val AUTO_LANG = "auto"

    internal var languageProvider: (() -> String?)? = null

    fun getTargetLanguage(): String {
        val configured = getConfig().targetLanguage.value().trim().lowercase()
        if (configured.isNotEmpty() && configured != AUTO_LANG) {
            return configured
        }
        return inferTargetLanguage()
    }

    fun inferTargetLanguage(): String {
        val gameCode = languageProvider?.invoke() ?: getGameLanguageCode()
        return normalizeLanguageCode(gameCode)
    }

    fun normalizeLanguageCode(code: String?): String {
        if (code.isNullOrBlank()) {
            val sysLang = runCatching { java.util.Locale.getDefault().language }.getOrNull()
            return if (!sysLang.isNullOrBlank() && sysLang.length in MIN_LANG_CODE_LENGTH..MAX_LANG_CODE_LENGTH) {
                sysLang.lowercase()
            } else {
                DEFAULT_FALLBACK_LANG
            }
        }
        val clean = code.trim().lowercase().replace('-', '_')
        if (clean == "lol_us") return DEFAULT_FALLBACK_LANG
        val primary = clean.substringBefore('_')
        return if (primary.length in MIN_LANG_CODE_LENGTH..MAX_LANG_CODE_LENGTH) primary else DEFAULT_FALLBACK_LANG
    }

    private fun getGameLanguageCode(): String? {
        return runCatching {
            net.minecraft.client.Minecraft.getInstance().options.languageCode
        }.getOrNull()
    }

    fun getConfig(): StellarLangConfig {
        return ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
    }

    fun getCached(text: String, targetLang: String): TranslationResult? {
        return TranslationCache.get(text, targetLang)
    }

    fun putCache(result: TranslationResult) {
        TranslationCache.put(result)
    }

    fun evict(text: String, targetLang: String = getTargetLanguage()) {
        val trimmed = text.trim()
        val key = TranslationCache.cacheKey(trimmed, targetLang)
        TranslationCache.evict(trimmed, targetLang)
        failedRequests.remove(key)
    }

    fun isInFlight(text: String, targetLang: String = getTargetLanguage()): Boolean {
        val key = TranslationCache.cacheKey(text.trim(), targetLang)
        return TranslationCache.isInFlight(key)
    }

    @JvmOverloads
    @Suppress("ReturnCount")
    fun translateAsync(
        text: String,
        forceRetry: Boolean = false,
        callback: (TranslationResult?) -> Unit,
    ) {
        val trimmed = text.trim()
        val config = getConfig()
        if (trimmed.isEmpty() || !config.enabled.value()) {
            callback(null)
            return
        }

        val targetLang = getTargetLanguage()
        val key = TranslationCache.cacheKey(trimmed, targetLang)

        if (forceRetry) {
            evict(trimmed, targetLang)
        } else {
            val cached = TranslationCache.get(trimmed, targetLang)
            if (cached != null) {
                callback(cached)
                return
            }
            if (TranslationCache.isCircuitBreakerOpen() || TranslationCache.isThrottled(key)) {
                callback(null)
                return
            }
        }

        val shouldFetch = TranslationCache.queueInFlight(key, callback)
        if (!shouldFetch) {
            return
        }

        dispatchTranslationTask(trimmed, key)
    }

    private fun dispatchTranslationTask(
        trimmed: String,
        key: String,
    ) {
        val targetLang = getTargetLanguage()
        executor.execute {
            val result = executeTranslation(trimmed, targetLang)
            if (result != null) {
                TranslationCache.put(result)
                failedRequests.remove(key)
                notifySuccess(result)
            } else {
                val transStatus = PluginRegistry.getActiveTranslator().getStatus()
                val detStatus = PluginRegistry.getActiveDetector().getStatus()
                val isDownloading = transStatus is PluginStatus.Downloading || detStatus is PluginStatus.Downloading
                if (!isDownloading) {
                    TranslationCache.markFailed(key)
                    failedRequests[key] = FailedRequest(trimmed, targetLang, System.currentTimeMillis())
                }
            }
            TranslationCache.completeInFlight(key, result)
        }
    }

    fun translateBatchSync(texts: List<String>): List<TranslationResult>? {
        val nonBlank = texts.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()

        val config = getConfig()
        if (!config.enabled.value()) return null

        val targetLang = getTargetLanguage()
        return resolveBatchTranslations(nonBlank, targetLang)
    }

    @Suppress("ReturnCount")
    private fun resolveBatchTranslations(
        texts: List<String>,
        targetLang: String,
    ): List<TranslationResult>? {
        val cachedMap = mutableMapOf<String, TranslationResult>()
        val missing = mutableListOf<String>()

        for (item in texts) {
            val cached = TranslationCache.get(item, targetLang)
            if (cached != null) {
                cachedMap[item] = cached
            } else if (!missing.contains(item)) {
                missing.add(item)
            }
        }

        if (missing.isEmpty()) {
            return texts.mapNotNull { cachedMap[it] }
        }

        if (TranslationCache.isCircuitBreakerOpen()) {
            return null
        }

        val fetched = executeBatchTranslation(missing, targetLang)
            ?: return null

        fetched.forEach { res ->
            TranslationCache.put(res)
            cachedMap[res.originalText] = res
        }

        return texts.map { cachedMap[it] ?: TranslationResult(it, it, targetLang, targetLang, true) }
    }

    fun isSameLanguage(lang1: String?, lang2: String?): Boolean {
        if (lang1.isNullOrBlank() || lang2.isNullOrBlank()) return false
        val clean1 = lang1.trim().lowercase().substringBefore('_').substringBefore('-')
        val clean2 = lang2.trim().lowercase().substringBefore('_').substringBefore('-')
        val invalidLangs = setOf(UNKNOWN_LANG, AUTO_LANG)
        if (clean1 in invalidLangs || clean2 in invalidLangs) {
            return false
        }
        return clean1 == clean2
    }

    fun isUntranslatedFailure(result: TranslationResult): Boolean {
        return isUntranslatedFailure(
            result.originalText,
            result.translatedText,
            result.detectedLanguage,
            result.targetLanguage,
        )
    }

    fun isUntranslatedFailure(
        originalText: String,
        translatedText: String?,
        detectedLang: String?,
        targetLang: String,
    ): Boolean {
        if (translatedText == null) return true
        if (isSameLanguage(detectedLang, targetLang)) return false
        val origTrimmed = originalText.trim()
        val transTrimmed = translatedText.trim()
        if (origTrimmed.none { it.isLetter() }) return false
        return origTrimmed.equals(transTrimmed, ignoreCase = true)
    }

    fun isBatchUntranslatedFailure(
        originalTexts: List<String>,
        translatedTexts: List<String>?,
        detectedLang: String?,
        targetLang: String,
    ): Boolean {
        if (translatedTexts == null || translatedTexts.size != originalTexts.size) return true
        if (isSameLanguage(detectedLang, targetLang)) return false

        val translatableIndices = originalTexts.indices.filter { originalTexts[it].any { ch -> ch.isLetter() } }
        if (translatableIndices.isEmpty()) return false

        val failedCount = translatableIndices.count { idx ->
            originalTexts[idx].trim().equals(translatedTexts[idx].trim(), ignoreCase = true)
        }
        return failedCount == translatableIndices.size
    }

    fun getCandidateTranslators(
        targetLang: String = getTargetLanguage(),
    ): List<com.stellar.lang.plugin.TranslationPlugin> {
        val active = PluginRegistry.getActiveTranslator()
        val fallback = PluginRegistry.getFallbackTranslator(active)
        return listOfNotNull(active, fallback).distinctBy { it.id }.filter { candidate ->
            if (candidate === active) {
                true
            } else if (candidate is com.stellar.lang.plugin.onnx.OnnxTranslationPlugin) {
                com.stellar.lang.plugin.onnx.OnnxModelManager.isTranslationModelReady(targetLang)
            } else if (candidate is LibreTranslatePlugin) {
                val host = getConfig().apiHost.value().trim()
                host.isNotBlank() && !TranslationCache.isCircuitBreakerOpen()
            } else {
                true
            }
        }
    }

    private fun detectLanguage(text: String): String {
        val detector = PluginRegistry.getActiveDetector()
        return kotlinx.coroutines.runBlocking {
            val detected = detector.detectLanguage(text)
            if (!detected.isNullOrBlank() && detected != UNKNOWN_LANG) {
                detected
            } else {
                runCatching {
                    val fallback = PluginRegistry.getFallbackDetector(detector)
                    if (fallback is com.stellar.lang.plugin.onnx.OnnxLanguageDetectorPlugin &&
                        !com.stellar.lang.plugin.onnx.OnnxModelManager.isDetectionModelReady()
                    ) {
                        null
                    } else {
                        fallback?.detectLanguage(text)
                    }
                }.getOrNull() ?: UNKNOWN_LANG
            }
        }
    }

    @Suppress("ReturnCount", "NestedBlockDepth")
    internal fun executeTranslation(
        text: String,
        targetLang: String,
    ): TranslationResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.none { it.isLetter() }) {
            return TranslationResult(
                originalText = text,
                translatedText = text,
                detectedLanguage = targetLang,
                targetLanguage = targetLang,
                isSameLanguage = true,
            )
        }

        return runCatching {
            lastNetworkException = null
            val candidates = getCandidateTranslators(targetLang)
            var finalResult: TranslationResult? = null
            var detected = UNKNOWN_LANG

            for (candidate in candidates) {
                var fatalException = false
                for (attempt in 1..MAX_ATTEMPTS_PER_PROVIDER) {
                    val outcome = runCatching {
                        if (candidate is LibreTranslatePlugin) {
                            executeTranslationWithProvider(candidate, trimmed, detected, targetLang)
                        } else {
                            if (detected == UNKNOWN_LANG) {
                                detected = detectLanguage(trimmed)
                            }
                            if (isSameLanguage(detected, targetLang)) {
                                TranslationResult(
                                    originalText = text,
                                    translatedText = text,
                                    detectedLanguage = detected,
                                    targetLanguage = targetLang,
                                    isSameLanguage = true,
                                )
                            } else {
                                executeTranslationWithProvider(candidate, trimmed, detected, targetLang)
                            }
                        }
                    }.onFailure { ex ->
                        if (isNonRetryableException(ex)) {
                            fatalException = true
                        }
                        logger.warn(
                            "Provider '{}' threw exception for '{}' (attempt {}/{}): {}",
                            candidate.id,
                            trimmed,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                            ex.message,
                        )
                    }.getOrNull()

                    if (outcome != null) {
                        finalResult = outcome
                        break
                    }

                    if (fatalException || isNonRetryableException(lastNetworkException)) {
                        break
                    }

                    if (attempt < MAX_ATTEMPTS_PER_PROVIDER) {
                        logger.warn(
                            "Provider '{}' failed or returned unchanged text for '{}' (attempt {}/{}). Retrying...",
                            candidate.id,
                            trimmed,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                        )
                        sleepQuietly(RETRY_DELAY_MS)
                    }
                }

                if (finalResult != null) {
                    if (candidate !== candidates.first()) {
                        logger.info("Successfully translated '{}' using fallback provider '{}'", trimmed, candidate.id)
                    }
                    break
                }

                if (fatalException || isNonRetryableException(lastNetworkException)) {
                    break
                }

                logger.warn(
                    "Provider '{}' exhausted all retries for '{}'. Attempting fallback provider if available...",
                    candidate.id,
                    trimmed,
                )
            }

            finalResult
        }.onFailure { ex ->
            logger.warn("Translation failed for '{}': {}", text, ex.message)
        }.getOrNull()
    }

    private fun executeTranslationWithProvider(
        provider: com.stellar.lang.plugin.TranslationPlugin,
        text: String,
        detectedLang: String,
        targetLang: String,
    ): TranslationResult? {
        val config = getConfig()
        if (provider is LibreTranslatePlugin) {
            val res = executeTranslation(text, config.apiHost.value(), config.apiKey.value(), targetLang)
            return if (res != null && !isUntranslatedFailure(res)) res else null
        }

        return kotlinx.coroutines.runBlocking {
            val effectiveDetected = if (detectedLang == UNKNOWN_LANG) {
                detectLanguage(text).takeIf { it != UNKNOWN_LANG }
            } else {
                detectedLang
            }
            val translated = provider.translate(
                text,
                effectiveDetected,
                targetLang,
            )
            if (translated != null && !isUntranslatedFailure(text, translated, effectiveDetected, targetLang)) {
                val isSame = isSameLanguage(effectiveDetected, targetLang)
                TranslationResult(
                    originalText = text,
                    translatedText = translated,
                    detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                    targetLanguage = targetLang,
                    isSameLanguage = isSame,
                )
            } else {
                null
            }
        }
    }

    @Suppress("ReturnCount", "NestedBlockDepth")
    internal fun executeBatchTranslation(
        texts: List<String>,
        targetLang: String,
    ): List<TranslationResult>? {
        if (texts.isEmpty()) return emptyList()

        if (texts.all { it.none { ch -> ch.isLetter() } }) {
            return texts.map {
                TranslationResult(
                    originalText = it,
                    translatedText = it,
                    detectedLanguage = targetLang,
                    targetLanguage = targetLang,
                    isSameLanguage = true,
                )
            }
        }

        return runCatching {
            lastNetworkException = null
            val candidates = getCandidateTranslators(targetLang)
            var finalResults: List<TranslationResult>? = null
            var detected = UNKNOWN_LANG

            for (candidate in candidates) {
                var fatalException = false
                for (attempt in 1..MAX_ATTEMPTS_PER_PROVIDER) {
                    val results = runCatching {
                        if (candidate is LibreTranslatePlugin) {
                            executeBatchTranslationWithProvider(candidate, texts, detected, targetLang)
                        } else {
                            if (detected == UNKNOWN_LANG) {
                                val sampleText = texts.firstOrNull { it.any { ch -> ch.isLetter() } } ?: texts.first()
                                detected = detectLanguage(sampleText)
                            }
                            if (isSameLanguage(detected, targetLang)) {
                                texts.map {
                                    TranslationResult(
                                        originalText = it,
                                        translatedText = it,
                                        detectedLanguage = detected,
                                        targetLanguage = targetLang,
                                        isSameLanguage = true,
                                    )
                                }
                            } else {
                                executeBatchTranslationWithProvider(candidate, texts, detected, targetLang)
                            }
                        }
                    }.onFailure { ex ->
                        if (isNonRetryableException(ex)) {
                            fatalException = true
                        }
                        logger.warn(
                            "Batch provider '{}' threw on attempt {}/{}: {}",
                            candidate.id,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                            ex.message,
                        )
                    }.getOrNull()

                    if (results != null) {
                        finalResults = results
                        break
                    }

                    if (fatalException || isNonRetryableException(lastNetworkException)) {
                        break
                    }

                    if (attempt < MAX_ATTEMPTS_PER_PROVIDER) {
                        logger.warn(
                            "Batch provider '{}' failed on attempt {}/{}. Retrying...",
                            candidate.id,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                        )
                        sleepQuietly(RETRY_DELAY_MS)
                    }
                }

                if (finalResults != null) {
                    if (candidate !== candidates.first()) {
                        logger.info("Batch translation succeeded using fallback provider '{}'", candidate.id)
                    }
                    break
                }

                if (fatalException || isNonRetryableException(lastNetworkException)) {
                    break
                }

                logger.warn(
                    "Batch provider '{}' exhausted all retries. Attempting fallback provider if available...",
                    candidate.id,
                )
            }

            if (finalResults != null) {
                finalResults = finalResults.map { res ->
                    if (isUntranslatedFailure(res)) {
                        val singleFallback = executeTranslation(res.originalText, targetLang)
                        if (singleFallback != null && !isUntranslatedFailure(singleFallback)) {
                            singleFallback
                        } else {
                            res
                        }
                    } else {
                        res
                    }
                }
            }

            finalResults
        }.onFailure { ex ->
            logger.warn("Batch translation failed: {}", ex.message)
        }.getOrNull()
    }

    private fun executeBatchTranslationWithProvider(
        provider: com.stellar.lang.plugin.TranslationPlugin,
        texts: List<String>,
        detectedLang: String,
        targetLang: String,
    ): List<TranslationResult>? {
        val config = getConfig()
        if (provider is LibreTranslatePlugin) {
            val results = executeBatchTranslation(texts, config.apiHost.value(), config.apiKey.value(), targetLang)
            val effectiveDetected = results?.firstOrNull()?.detectedLanguage ?: detectedLang
            val isFailure = results == null ||
                isBatchUntranslatedFailure(texts, results.map { it.translatedText }, effectiveDetected, targetLang)
            return if (isFailure) null else results
        }

        return kotlinx.coroutines.runBlocking {
            val effectiveDetected = if (detectedLang == UNKNOWN_LANG) {
                val sample = texts.firstOrNull { it.any { ch -> ch.isLetter() } } ?: texts.first()
                detectLanguage(sample).takeIf { it != UNKNOWN_LANG }
            } else {
                detectedLang
            }
            val translatedList = provider.translateBatch(
                texts,
                effectiveDetected,
                targetLang,
            )
            val isFailure = translatedList == null ||
                isBatchUntranslatedFailure(texts, translatedList, effectiveDetected, targetLang)
            if (isFailure) {
                null
            } else {
                texts.mapIndexed { index, original ->
                    val trans = translatedList.getOrElse(index) { original }
                    val isSame = isSameLanguage(effectiveDetected, targetLang)
                    TranslationResult(
                        originalText = original,
                        translatedText = trans,
                        detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                        targetLanguage = targetLang,
                        isSameLanguage = isSame,
                    )
                }
            }
        }
    }

    private fun isNonRetryableException(ex: Throwable?): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            val isFatal = when (current) {
                is java.net.ConnectException,
                is java.net.UnknownHostException,
                is java.net.PortUnreachableException,
                -> true
                else -> false
            }
            if (isFatal) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun translateBatchAsync(texts: List<String>, callback: (List<TranslationResult>?) -> Unit) {
        executor.execute {
            val results = translateBatchSync(texts)
            callback(results)
        }
    }

    private fun executeTranslation(
        text: String,
        host: String,
        apiKey: String,
        targetLang: String,
    ): TranslationResult? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host))
            val jsonPayload = buildPayload(text, apiKey, targetLang)

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                lastNetworkException = null
                val parsed = parseSingleResponse(response.body(), text, targetLang)
                if (parsed != null && isUntranslatedFailure(parsed)) {
                    logger.warn(
                        "LibreTranslate returned untranslated text for '{}' despite detected language '{}'",
                        text,
                        parsed.detectedLanguage,
                    )
                    null
                } else {
                    parsed
                }
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                logger.warn("LibreTranslate responded with HTTP {}: {}", response.statusCode(), response.body())
                null
            }
        }.getOrElse { ex ->
            lastNetworkException = ex
            val errorDetail = ex.message ?: ex::class.simpleName ?: ex.toString()
            logger.warn("LibreTranslate request failed for '{}': {}", text, errorDetail)
            null
        }
    }

    private fun executeBatchTranslation(
        texts: List<String>,
        host: String,
        apiKey: String,
        targetLang: String,
    ): List<TranslationResult>? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host))
            val jsonPayload = buildBatchPayload(texts, apiKey, targetLang)

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                lastNetworkException = null
                parseBatchResponse(response.body(), texts, targetLang)
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                null
            }
        }.onFailure { ex ->
            lastNetworkException = ex
            val errorDetail = ex.message ?: ex::class.simpleName ?: ex.toString()
            logger.warn("LibreTranslate batch request failed for {} items: {}", texts.size, errorDetail)
        }.getOrNull()
    }

    private fun normalizeEndpoint(host: String): String {
        var trimmed = host.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return if (trimmed.endsWith("/translate")) trimmed else "$trimmed/translate"
    }

    private fun buildPayload(text: String, apiKey: String, targetLang: String): String {
        val root = JsonObject()
        root.addProperty("q", text)
        root.addProperty("source", "auto")
        root.addProperty("target", targetLang)
        root.addProperty("format", "text")
        if (apiKey.isNotBlank()) {
            root.addProperty("api_key", apiKey)
        }
        return gson.toJson(root)
    }

    private fun buildBatchPayload(texts: List<String>, apiKey: String, targetLang: String): String {
        val root = JsonObject()
        val array = JsonArray()
        texts.forEach { array.add(it) }
        root.add("q", array)
        root.addProperty("source", "auto")
        root.addProperty("target", targetLang)
        root.addProperty("format", "text")
        if (apiKey.isNotBlank()) {
            root.addProperty("api_key", apiKey)
        }
        return gson.toJson(root)
    }

    fun parseSingleResponse(body: String, originalText: String, targetLang: String): TranslationResult? {
        return runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            val transElem = json.get("translatedText") ?: return null
            val translated = if (transElem.isJsonArray) {
                transElem.asJsonArray.firstOrNull()?.asString ?: return null
            } else {
                transElem.asString
            }
            val detected = parseDetectedLanguage(json)
            val isSame = isSameLanguage(detected, targetLang)
            TranslationResult(
                originalText = originalText,
                translatedText = translated,
                detectedLanguage = detected,
                targetLanguage = targetLang,
                isSameLanguage = isSame,
            )
        }.getOrNull()
    }

    private fun parseBatchResponse(
        body: String,
        originalTexts: List<String>,
        targetLang: String,
    ): List<TranslationResult>? {
        return runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            val translatedArray = json.get("translatedText")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val detectedArray = json.get("detectedLanguage")?.takeIf { it.isJsonArray }?.asJsonArray
            val defaultDetected = parseDetectedLanguage(json)

            originalTexts.mapIndexed { index, orig ->
                val trans = translatedArray.get(index)?.asString ?: orig
                val detected = if (detectedArray != null && index < detectedArray.size()) {
                    extractLanguageFromElement(detectedArray.get(index))
                } else {
                    defaultDetected
                }
                val isSame = isSameLanguage(detected, targetLang)
                val result = TranslationResult(orig, trans, detected, targetLang, isSame)
                TranslationCache.put(result)
                result
            }
        }.getOrNull()
    }

    private fun extractLanguageFromElement(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return UNKNOWN_LANG
        if (element.isJsonObject) {
            return element.asJsonObject.get("language")?.asString ?: UNKNOWN_LANG
        }
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return element.asString
        }
        return UNKNOWN_LANG
    }

    private fun parseDetectedLanguage(obj: JsonObject): String {
        val element: JsonElement = obj.get("detectedLanguage") ?: return UNKNOWN_LANG
        if (element.isJsonArray) {
            val array = element.asJsonArray
            if (!array.isEmpty) {
                return extractLanguageFromElement(array.get(0))
            }
        }
        return extractLanguageFromElement(element)
    }

    fun isThrottled(key: String): Boolean = TranslationCache.isThrottled(key)

    fun isFailed(text: String): Boolean = isFailed(text, getTargetLanguage())

    fun isFailed(text: String, targetLang: String): Boolean {
        val key = cacheKey(text, targetLang)
        return TranslationCache.isFailed(key) || TranslationCache.isCircuitBreakerOpen()
    }

    fun cacheKey(text: String, targetLang: String): String = TranslationCache.cacheKey(text, targetLang)

    fun addSuccessListener(listener: (TranslationResult) -> Unit) {
        successListeners.add(listener)
    }

    fun removeSuccessListener(listener: (TranslationResult) -> Unit) {
        successListeners.remove(listener)
    }

    internal fun notifySuccess(result: TranslationResult) {
        for (listener in successListeners) {
            runCatching { listener(result) }
        }
    }

    fun retryFailedTranslations() {
        if (TranslationCache.isCircuitBreakerOpen()) return
        val transStatus = PluginRegistry.getActiveTranslator().getStatus()
        val detStatus = PluginRegistry.getActiveDetector().getStatus()
        if (transStatus is PluginStatus.Downloading || detStatus is PluginStatus.Downloading) return

        val currentTargetLang = getTargetLanguage()
        val now = System.currentTimeMillis()
        val entriesToRetry = failedRequests.entries.filter { (_, req) ->
            req.targetLang == currentTargetLang && now - req.failedAt >= TranslationCache.ERROR_COOLDOWN_MS
        }

        for ((key, req) in entriesToRetry) {
            failedRequests.remove(key)
            TranslationCache.removeFailed(key)
            translateAsync(req.text) { /* completion triggers notifySuccess or re-registers failedRequest */ }
        }
    }

    fun detectLanguageQuick(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val cached = TranslationCache.get(trimmed, getTargetLanguage())
        val detector = PluginRegistry.getActiveDetector()
        return cached?.detectedLanguage
            ?: (detector as? com.stellar.lang.plugin.onnx.OnnxLanguageDetectorPlugin)
                ?.let { com.stellar.lang.plugin.onnx.OnnxInferenceEngine.detectLanguage(trimmed) }
    }

    fun clearCache() {
        TranslationCache.clear()
        failedRequests.clear()
    }

    fun testConnection(
        host: String,
        apiKey: String,
        targetLang: String = getTargetLanguage(),
        callback: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val outcome = runCatching {
                val endpoint = URI.create(normalizeEndpoint(host))
                val jsonPayload = buildPayload("Hello", apiKey, targetLang)

                val request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .header(HEADER_ACCEPT, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                    val parsed = parseSingleResponse(response.body(), "Hello", targetLang)
                    parsed?.translatedText ?: "OK (HTTP ${response.statusCode()})"
                } else {
                    val msg = runCatching {
                        JsonParser.parseString(response.body()).asJsonObject.get("error")?.asString
                    }.getOrNull() ?: response.body().take(ERROR_SNIPPET_LENGTH)
                    error("HTTP ${response.statusCode()}: $msg")
                }
            }
            callback(outcome)
        }
    }

    fun testOnnx(
        targetLang: String = getTargetLanguage(),
        callback: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val outcome = runCatching {
                if (!com.stellar.lang.plugin.onnx.OnnxInferenceEngine.isEnvironmentAvailable()) {
                    error("ONNX Runtime native environment unavailable")
                }
                val hasDetection = com.stellar.lang.plugin.onnx.OnnxModelManager.isDetectionModelReady()
                val hasTranslation = com.stellar.lang.plugin.onnx.OnnxModelManager.isTranslationModelReady(targetLang)
                if (!hasDetection && !hasTranslation) {
                    error("No ONNX models found in storage directory")
                }
                val results = mutableListOf<String>()
                if (hasDetection) {
                    val detected = com.stellar.lang.plugin.onnx.OnnxInferenceEngine.detectLanguage(
                        "Bonjour tout le monde",
                    )
                    results.add("Det: 'Bonjour' -> ${detected ?: "unknown"}")
                }
                if (hasTranslation) {
                    if (com.stellar.lang.plugin.onnx.OnnxInferenceEngine.isTranslationModelGenerative(targetLang)) {
                        val translated = com.stellar.lang.plugin.onnx.OnnxInferenceEngine.translate(
                            "Hello world",
                            "en",
                            targetLang,
                        )
                        results.add("Trans: 'Hello' -> ${translated ?: "null"}")
                    } else {
                        results.add("Trans: encoder-only ($targetLang)")
                    }
                }
                results.joinToString("; ")
            }
            callback(outcome)
        }
    }
}
