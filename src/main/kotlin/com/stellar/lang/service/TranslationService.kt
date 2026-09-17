@file:Suppress("LargeClass")

package com.stellar.lang.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

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
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val APPLICATION_JSON = "application/json"
    private const val UNKNOWN_LANG = "unknown"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    private val executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE) { runnable ->
        Thread(runnable, "StellarLang-Worker").apply { isDaemon = true }
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

        val targetLang = config.targetLanguage.value()
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

        dispatchTranslationTask(trimmed, key, config)
    }

    private fun dispatchTranslationTask(
        trimmed: String,
        key: String,
        config: StellarLangConfig,
    ) {
        val targetLang = config.targetLanguage.value()
        executor.execute {
            val result = executeTranslation(trimmed, config.apiHost.value(), config.apiKey.value(), targetLang)
            if (result != null) {
                TranslationCache.put(result)
            } else {
                TranslationCache.markFailed(key)
            }
            TranslationCache.completeInFlight(key, result)
        }
    }

    fun translateBatchSync(texts: List<String>): List<TranslationResult>? {
        val nonBlank = texts.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()

        val config = getConfig()
        if (!config.enabled.value()) return null

        val targetLang = config.targetLanguage.value()
        return resolveBatchTranslations(nonBlank, config, targetLang)
    }

    @Suppress("ReturnCount")
    private fun resolveBatchTranslations(
        texts: List<String>,
        config: StellarLangConfig,
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

        val fetched = executeBatchTranslation(missing, config.apiHost.value(), config.apiKey.value(), targetLang)
            ?: return null

        fetched.forEach { res ->
            TranslationCache.put(res)
            cachedMap[res.originalText] = res
        }

        return texts.map { cachedMap[it] ?: TranslationResult(it, it, targetLang, targetLang, true) }
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
                .header("Content-Type", APPLICATION_JSON)
                .header("Accept", APPLICATION_JSON)
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
            logger.warn("LibreTranslate request failed for '{}': {}", text, ex.message)
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
                .header("Content-Type", APPLICATION_JSON)
                .header("Accept", APPLICATION_JSON)
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
        }.getOrNull()
    }

    private fun normalizeEndpoint(host: String): String {
        val trimmed = host.trim().trimEnd('/')
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
            val json = JsonParser.parseString(body)
            if (json.isJsonObject) {
                val obj = json.asJsonObject
                val translatedElem = obj.get("translatedText")
                val detected = parseDetectedLanguage(obj)
                val isSame = detected.equals(targetLang, ignoreCase = true)
                if (translatedElem != null && translatedElem.isJsonArray) {
                    val array = translatedElem.asJsonArray
                    return originalTexts.mapIndexed { index, orig ->
                        val trans = array.get(index)?.asString ?: orig
                        val result = TranslationResult(orig, trans, detected, targetLang, isSame)
                        TranslationCache.put(result)
                        result
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun parseDetectedLanguage(obj: JsonObject): String {
        val element: JsonElement = obj.get("detectedLanguage") ?: return UNKNOWN_LANG
        if (element.isJsonObject) {
            return element.asJsonObject.get("language")?.asString ?: UNKNOWN_LANG
        }
        if (element.isJsonPrimitive) {
            return element.asString
        }
        return UNKNOWN_LANG
    }

    fun isThrottled(key: String): Boolean = TranslationCache.isThrottled(key)

    fun cacheKey(text: String, targetLang: String): String = TranslationCache.cacheKey(text, targetLang)

    fun clearCache() {
        TranslationCache.clear()
    }
}
