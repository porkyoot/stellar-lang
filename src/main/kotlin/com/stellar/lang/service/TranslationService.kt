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
@Suppress("TooManyFunctions")
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

    @Suppress("ReturnCount")
    fun translateAsync(text: String, callback: (TranslationResult?) -> Unit) {
        val trimmed = text.trim()
        val config = getConfig()
        if (trimmed.isEmpty() || !config.enabled.value()) {
            callback(null)
            return
        }

        val targetLang = getTargetLanguage()
        val cached = TranslationCache.get(trimmed, targetLang)
        if (cached != null) {
            callback(cached)
            return
        }

        val key = TranslationCache.cacheKey(trimmed, targetLang)
        if (TranslationCache.isCircuitBreakerOpen() || TranslationCache.isThrottled(key)) {
            callback(null)
            return
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

    private fun executeTranslation(
        text: String,
        targetLang: String,
    ): TranslationResult? {
        val config = getConfig()
        val translator = PluginRegistry.getActiveTranslator()
        val detector = PluginRegistry.getActiveDetector()

        if (translator is LibreTranslatePlugin && detector is LibreTranslatePlugin) {
            return executeTranslation(text, config.apiHost.value(), config.apiKey.value(), targetLang)
        }

        return kotlinx.coroutines.runBlocking {
            runCatching {
                val detected = detector.detectLanguage(text) ?: UNKNOWN_LANG
                val isSame = detected.equals(targetLang, ignoreCase = true)
                if (isSame) {
                    TranslationResult(
                        originalText = text,
                        translatedText = text,
                        detectedLanguage = detected,
                        targetLanguage = targetLang,
                        isSameLanguage = true,
                    )
                } else {
                    val translated = translator.translate(text, detected, targetLang)
                    if (translated != null) {
                        TranslationResult(
                            originalText = text,
                            translatedText = translated,
                            detectedLanguage = detected,
                            targetLanguage = targetLang,
                            isSameLanguage = false,
                        )
                    } else {
                        null
                    }
                }
            }.onFailure { ex ->
                logger.warn("Translation failed for '{}': {}", text, ex.message)
            }.getOrNull()
        }
    }

    private fun executeBatchTranslation(
        texts: List<String>,
        targetLang: String,
    ): List<TranslationResult>? {
        val config = getConfig()
        val translator = PluginRegistry.getActiveTranslator()
        val detector = PluginRegistry.getActiveDetector()

        if (translator is LibreTranslatePlugin && detector is LibreTranslatePlugin) {
            return executeBatchTranslation(texts, config.apiHost.value(), config.apiKey.value(), targetLang)
        }

        return kotlinx.coroutines.runBlocking {
            runCatching {
                val detected = detector.detectLanguage(texts.firstOrNull() ?: "") ?: UNKNOWN_LANG
                val isSame = detected.equals(targetLang, ignoreCase = true)
                if (isSame) {
                    texts.map {
                        TranslationResult(it, it, detected, targetLang, true)
                    }
                } else {
                    val translatedList = translator.translateBatch(texts, detected, targetLang)
                    if (translatedList != null) {
                        texts.mapIndexed { index, original ->
                            val trans = translatedList.getOrElse(index) { original }
                            TranslationResult(
                                originalText = original,
                                translatedText = trans,
                                detectedLanguage = detected,
                                targetLanguage = targetLang,
                                isSameLanguage = false,
                            )
                        }
                    } else {
                        null
                    }
                }
            }.onFailure { ex ->
                logger.warn("Batch translation failed: {}", ex.message)
            }.getOrNull()
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
                parseSingleResponse(response.body(), text, targetLang)
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                logger.warn("LibreTranslate responded with HTTP {}: {}", response.statusCode(), response.body())
                null
            }
        }.getOrElse { ex ->
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
                parseBatchResponse(response.body(), texts, targetLang)
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                null
            }
        }.onFailure { ex ->
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
            val translated = json.get("translatedText")?.asString ?: return null
            val detected = parseDetectedLanguage(json)
            val isSame = detected.equals(targetLang, ignoreCase = true)
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
                val isSame = detected.equals(targetLang, ignoreCase = true)
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
}
