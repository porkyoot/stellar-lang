@file:Suppress(
    "TooGenericExceptionCaught",
    "LongParameterList",
    "CognitiveComplexMethod",
    "StringLiteralDuplication",
    "LargeClass",
    "LongMethod",
    "ReturnCount",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "NestedBlockDepth",
    "TooManyFunctions",
    "MagicNumber",
    "UnnecessaryParentheses",
)

package com.stellar.lang.plugin.google

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.LanguageDetectionHelper
import com.stellar.lang.plugin.LanguageDetectorPlugin
import com.stellar.lang.plugin.PluginStatus
import com.stellar.lang.plugin.TranslationPlugin
import com.stellar.lang.service.TranslationCache
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Translation and Language Detection plugin backed by Google Cloud Translation API (Basic / v2)
 * using simple API key authentication (key=API_KEY).
 */
class GooglePlugin(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
) : TranslationPlugin, LanguageDetectorPlugin {
    override val id: String = "google"
    override val displayName: String = "Google Translate (Cloud API)"
    override val description: String =
        "Translates and detects language using Google Cloud Translation API with simple API key authentication."

    private val logger: Logger = LoggerFactory.getLogger("StellarLang-Google")
    private val gson = Gson()
    private val requestLock = Any()

    @Volatile
    var lastRequestTime: Long = 0L

    @Volatile
    var testPacingIntervalMs: Long? = null

    @Volatile
    var testBackoffMs: Long? = null

    override fun getStatus(): PluginStatus {
        val config = getConfig()
        val apiKey = config.googleApiKey.value().trim()
        return if (apiKey.isBlank()) {
            PluginStatus.NotConfigured("Google API key is not specified")
        } else {
            val endpoint = resolveHost(config.googleApiHost.value())
            PluginStatus.Ready("Google Translate API ($endpoint)")
        }
    }

    override suspend fun detectLanguage(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isLetter() }) return null
        if (LanguageDetectionHelper.isUniversalSlang(trimmed)) return null

        val quick = LanguageDetectionHelper.detectQuick(trimmed)
        if (quick == "en") return "en"

        val config = getConfig()
        val apiKey = config.googleApiKey.value().trim()
        if (apiKey.isBlank()) return null

        val host = config.googleApiHost.value()
        return executeDetect(trimmed, host, apiKey)
    }

    override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || LanguageDetectionHelper.isEmoticonOrKaomoji(trimmed)) {
            return text
        }
        if (LanguageDetectionHelper.isUniversalSlang(trimmed) && targetLang.lowercase().startsWith("en")) {
            return text
        }

        val config = getConfig()
        val apiKey = config.googleApiKey.value().trim()
        if (apiKey.isBlank()) return null

        val host = config.googleApiHost.value()
        return executeTranslate(text, sourceLang, targetLang, host, apiKey)
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
    ): List<String>? {
        if (texts.isEmpty()) return emptyList()

        val config = getConfig()
        val apiKey = config.googleApiKey.value().trim()
        if (apiKey.isBlank()) return null

        val host = config.googleApiHost.value()
        return executeBatchTranslate(texts, sourceLang, targetLang, host, apiKey)
    }

    fun executeTranslate(
        text: String,
        sourceLang: String?,
        targetLang: String,
        host: String,
        apiKey: String,
    ): String? {
        val results = executeBatchTranslate(listOf(text), sourceLang, targetLang, host, apiKey)
        return results?.firstOrNull()
    }

    data class GoogleTranslationResult(
        val translatedText: String,
        val detectedSourceLanguage: String?,
    )

    fun executeBatchTranslate(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
        host: String,
        apiKey: String,
    ): List<String>? {
        return executeBatchTranslateDetailed(texts, sourceLang, targetLang, host, apiKey)
            ?.map { it.translatedText }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun executeBatchTranslateDetailed(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
        host: String,
        apiKey: String,
    ): List<GoogleTranslationResult>? {
        if (texts.isEmpty()) return emptyList()
        val normTarget = normalizeTargetLanguage(targetLang)
        val normSource = normalizeSourceLanguage(sourceLang)

        return runCatching {
            val chunks = texts.chunked(MAX_BATCH_SIZE)
            val translatedResults = mutableListOf<GoogleTranslationResult>()
            val endpoint = URI.create(normalizeEndpoint(host, TRANSLATE_PATH, apiKey))

            for (chunk in chunks) {
                val hasTags = chunk.any { it.contains("<ut>", ignoreCase = true) }
                val payload = buildChunkPayload(chunk, normSource, normTarget, isHtml = hasTags)
                var response = sendGoogleRequestWithRateLimit(endpoint, payload, host)

                // If Google returned HTTP 400 with tags, retry chunk as plain text
                if (response.statusCode() == HTTP_BAD_REQUEST && hasTags) {
                    logger.warn("Google API rejected HTML tags with HTTP 400. Retrying chunk as plain text...")
                    val plainChunk = chunk.map {
                        com.stellar.lang.format.FormattingTagHelper.decodeFromUntranslatableTags(it)
                    }
                    val fallbackPayload = buildChunkPayload(
                        plainChunk,
                        normSource,
                        normTarget,
                        isHtml = false,
                    )
                    response = sendGoogleRequestWithRateLimit(endpoint, fallbackPayload, host)
                }

                if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                    val json = JsonParser.parseString(response.body()).asJsonObject
                    val parsed = parseTranslationResults(json, chunk) ?: return null
                    translatedResults.addAll(parsed)
                } else {
                    handleHttpError(response.statusCode(), response.body())
                    return null
                }
            }
            translatedResults
        }.getOrElse { ex ->
            logger.warn("Google translation failed: {}", ex.message)
            val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyException(
                providerId = id,
                throwable = ex,
                host = host,
            )
            com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
            null
        }
    }

    private fun parseTranslationResults(
        json: JsonObject,
        chunk: List<String>,
    ): List<GoogleTranslationResult>? {
        val dataObj = json.getAsJsonObject("data") ?: return null
        val transArray = dataObj.getAsJsonArray("translations") ?: return null
        if (transArray.size() != chunk.size) {
            logger.warn("Google Translate returned {} translations for {} items", transArray.size(), chunk.size)
            return null
        }
        val results = mutableListOf<GoogleTranslationResult>()
        for (i in 0 until transArray.size()) {
            val item = transArray.get(i).asJsonObject
            val raw = item.get("translatedText")?.asString ?: chunk[i]
            val translated = unescapeHtml(raw)
            val detectedRaw = item.get("detectedSourceLanguage")?.asString
            val detected = parseGoogleDetect(detectedRaw, chunk[i])
            results.add(GoogleTranslationResult(translated, detected))
        }
        return results
    }

    fun parseGoogleDetect(rawLang: String?, text: String): String? {
        if (rawLang.isNullOrBlank()) return null
        val cleanLang = rawLang.trim().lowercase().substringBefore('-')
        if (cleanLang !in LanguageDetectionHelper.SUPPORTED_LANGUAGES) {
            return null
        }

        if (cleanLang != "en") {
            if (LanguageDetectionHelper.isUniversalSlang(text) ||
                LanguageDetectionHelper.detectQuick(text) == "en"
            ) {
                return null
            }
            if (!LanguageDetectionHelper.hasForeignMarkers(text)) {
                val hasRepeating = text.contains(Regex("(.)\\1{2,}"))
                val isShort = text.length <= SHORT_TEXT_THRESHOLD ||
                    text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size <= SHORT_TEXT_WORD_COUNT
                val quickMatch = LanguageDetectionHelper.detectQuick(text)
                if (hasRepeating || isShort && quickMatch != cleanLang && cleanLang !in COMMON_EUROPEAN_LANGS) {
                    return null
                }
            }
        }

        return cleanLang
    }

    fun isLocalHost(host: String): Boolean {
        val clean = host.lowercase().trim()
        return clean.contains("127.0.0.1") || clean.contains("localhost")
    }

    fun parseRetryAfterMs(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        val seconds = headerValue.trim().toLongOrNull()
        if (seconds != null && seconds >= 0) {
            return seconds * 1000L
        }
        return runCatching {
            val date = DateTimeFormatter.RFC_1123_DATE_TIME.parse(headerValue.trim())
            val instant = Instant.from(date)
            val diff = Duration.between(Instant.now(), instant).toMillis()
            diff.coerceAtLeast(0L)
        }.getOrNull()
    }

    internal fun getPacingInterval(host: String): Long {
        testPacingIntervalMs?.let { return it }
        if (isLocalHost(host)) return 0L
        return getConfig().googleRequestIntervalMs.value().toLong()
    }

    internal fun sendGoogleRequestWithRateLimit(
        endpoint: URI,
        payload: JsonObject,
        host: String,
    ): HttpResponse<String> {
        synchronized(requestLock) {
            val interval = getPacingInterval(host)
            val now = System.currentTimeMillis()
            val timeSinceLast = now - lastRequestTime
            if (lastRequestTime > 0 && timeSinceLast < interval) {
                sleepQuietly(interval - timeSinceLast)
            }

            var attempt = 1
            var backoffMs = if (isLocalHost(host)) 0L else INITIAL_BACKOFF_MS

            while (true) {
                val response = sendGoogleRequest(endpoint, payload)
                lastRequestTime = System.currentTimeMillis()

                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    if (attempt >= MAX_RETRIES) {
                        return response
                    }
                    val retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null)
                    val retryAfterMs = parseRetryAfterMs(retryAfterHeader)
                    val waitMs = testBackoffMs ?: if (isLocalHost(host)) 0L else (retryAfterMs ?: backoffMs)

                    logger.warn(
                        "Google Translate returned HTTP 429 Too Many Requests. Retrying in {} ms (attempt {}/{})",
                        waitMs,
                        attempt,
                        MAX_RETRIES,
                    )

                    if (waitMs > 0) {
                        sleepQuietly(waitMs)
                    }

                    backoffMs = (backoffMs * BACKOFF_MULTIPLIER).coerceAtMost(MAX_BACKOFF_MS)
                    attempt++
                } else {
                    return response
                }
            }
        }
    }

    private fun sleepQuietly(millis: Long) {
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun sendGoogleRequest(
        endpoint: URI,
        payload: JsonObject,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
            .header(HEADER_ACCEPT, APPLICATION_JSON)
            .header(HEADER_USER_AGENT, USER_AGENT_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun executeDetect(text: String, host: String, apiKey: String): String? {
        if (LanguageDetectionHelper.isUniversalSlang(text)) {
            return null
        }
        val quick = LanguageDetectionHelper.detectQuick(text)
        if (quick == "en") {
            return "en"
        }

        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host, DETECT_PATH, apiKey))
            val payload = JsonObject().apply {
                val textArray = JsonArray()
                textArray.add(text)
                add("q", textArray)
            }

            val response = sendGoogleRequestWithRateLimit(endpoint, payload, host)
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                val dataObj = json.getAsJsonObject("data")
                val detections = dataObj?.getAsJsonArray("detections")
                if (detections != null && !detections.isEmpty) {
                    val firstArray = detections.get(0).asJsonArray
                    if (!firstArray.isEmpty) {
                        val firstDetect = firstArray.get(0).asJsonObject
                        val raw = firstDetect.get("language")?.asString
                        parseGoogleDetect(raw, text)
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                handleHttpError(response.statusCode(), response.body())
                null
            }
        }.getOrElse { ex ->
            val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyException(
                providerId = id,
                throwable = ex,
                host = host,
            )
            com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
            null
        }
    }

    fun testConnection(
        apiKey: String,
        host: String = "auto",
        targetLang: String = "auto",
    ): Result<String> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Google API key is empty"))
        }

        val effectiveTarget = if (targetLang.isBlank() || targetLang.equals("auto", ignoreCase = true)) {
            "en"
        } else {
            normalizeTargetLanguage(targetLang)
        }

        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host, TRANSLATE_PATH, cleanKey))
            val payload = JsonObject().apply {
                val textArray = JsonArray()
                textArray.add("Hello")
                add("q", textArray)
                addProperty("target", effectiveTarget)
                addProperty("format", "text")
            }

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .header(HEADER_USER_AGENT, USER_AGENT_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                val dataObj = json.getAsJsonObject("data")
                val transArray = dataObj?.getAsJsonArray("translations")
                if (transArray != null && !transArray.isEmpty) {
                    val raw = transArray.get(0).asJsonObject.get("translatedText")?.asString ?: "OK"
                    unescapeHtml(raw)
                } else {
                    "OK (HTTP ${response.statusCode()})"
                }
            } else {
                val errMsg = extractErrorMessage(response.body())
                error("HTTP ${response.statusCode()}: $errMsg")
            }
        }
    }

    private fun extractErrorMessage(responseBody: String): String {
        return runCatching {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val errObj = json.getAsJsonObject("error")
            errObj.get("message")?.asString
        }.getOrNull() ?: responseBody.take(MAX_ERROR_SNIPPET_LENGTH)
    }

    private fun handleHttpError(statusCode: Int, responseBody: String) {
        val (message, reason) = runCatching {
            val json = JsonParser.parseString(responseBody).asJsonObject
            val err = json.getAsJsonObject("error")
            val msg = err?.get("message")?.asString ?: responseBody.take(MAX_ERROR_SNIPPET_LENGTH)
            val rsn = err?.getAsJsonArray("errors")?.firstOrNull()?.asJsonObject?.get("reason")?.asString
            msg to rsn
        }.getOrDefault(responseBody.take(MAX_ERROR_SNIPPET_LENGTH) to null)

        val isQuotaReason = reason?.let {
            it.contains("dailyLimit", ignoreCase = true) ||
                it.contains("quota", ignoreCase = true) ||
                it.contains("rateLimit", ignoreCase = true)
        } ?: false
        val isQuotaMessage = message.contains("quota", ignoreCase = true) ||
            message.contains("daily limit", ignoreCase = true)
        val isQuota = statusCode == HTTP_TOO_MANY_REQUESTS ||
            (statusCode == HTTP_FORBIDDEN && (isQuotaReason || isQuotaMessage))

        if (isQuota) {
            TranslationCache.tripCircuitBreaker()
        }

        logger.warn("Google Translate HTTP {}: {} (reason: {})", statusCode, message, reason ?: "none")
        val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyHttpStatus(
            providerId = id,
            statusCode = statusCode,
            responseBody = "$message (reason: ${reason ?: "none"})",
        )
        com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
    }

    fun resolveHost(host: String): String {
        val trimmed = host.trim()
        if (trimmed.isNotBlank() && !trimmed.equals("auto", ignoreCase = true)) {
            var formatted = trimmed.trimEnd('/')
            if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
                formatted = "https://$formatted"
            }
            return formatted
        }
        return DEFAULT_API_HOST
    }

    fun normalizeEndpoint(host: String, path: String, apiKey: String): String {
        val base = resolveHost(host)
        val baseWithoutQuery = base.substringBefore('?')
        val existingQuery = if (base.contains('?')) base.substringAfter('?') else null
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val pathUri = if (baseWithoutQuery.endsWith(cleanPath)) baseWithoutQuery else "$baseWithoutQuery$cleanPath"

        val hasExistingKey = existingQuery?.contains("key=") == true
        return if (hasExistingKey) {
            "$pathUri?$existingQuery"
        } else if (apiKey.isNotBlank()) {
            val encodedKey = URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8)
            val query = if (existingQuery != null) "$existingQuery&key=$encodedKey" else "key=$encodedKey"
            "$pathUri?$query"
        } else if (existingQuery != null) {
            "$pathUri?$existingQuery"
        } else {
            pathUri
        }
    }

    fun normalizeTargetLanguage(lang: String): String {
        val clean = lang.trim().lowercase().replace('_', '-')
        return when {
            clean == "zh" || clean == "zh-cn" || clean == "zh-hans" -> "zh-CN"
            clean == "zh-tw" || clean == "zh-hk" || clean == "zh-hant" -> "zh-TW"
            clean == "pt-br" || clean == "pt-pt" -> "pt"
            clean == "en-us" || clean == "en-gb" -> "en"
            clean == "nb" || clean == "nn" -> "no"
            clean == "he" || clean == "iw" -> "iw"
            clean.contains('-') -> clean.substringBefore('-')
            else -> clean
        }
    }

    fun normalizeSourceLanguage(lang: String?): String? {
        if (lang.isNullOrBlank()) return null
        val clean = lang.trim().lowercase().replace('_', '-')
        if (clean == "auto" || clean == "unknown") return null
        return when {
            clean == "zh" || clean == "zh-cn" || clean == "zh-hans" -> "zh-CN"
            clean == "zh-tw" || clean == "zh-hk" || clean == "zh-hant" -> "zh-TW"
            clean == "nb" || clean == "nn" -> "no"
            clean == "he" || clean == "iw" -> "iw"
            clean.contains('-') -> clean.substringBefore('-')
            else -> clean
        }
    }

    fun unescapeHtml(text: String): String {
        if (!text.contains('&')) return text
        var result = text
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

        if (result.contains("&#")) {
            result = ENTITY_DEC_REGEX.replace(result) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null && code in Character.MIN_CODE_POINT..Character.MAX_CODE_POINT) {
                    String(Character.toChars(code))
                } else {
                    match.value
                }
            }
            result = ENTITY_HEX_REGEX.replace(result) { match ->
                val code = match.groupValues[1].toIntOrNull(HEX_RADIX)
                if (code != null && code in Character.MIN_CODE_POINT..Character.MAX_CODE_POINT) {
                    String(Character.toChars(code))
                } else {
                    match.value
                }
            }
        }
        return result
    }

    private fun getConfig(): StellarLangConfig {
        return ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
    }

    companion object {
        const val DEFAULT_API_HOST = "https://translation.googleapis.com"
        const val TRANSLATE_PATH = "/language/translate/v2"
        const val DETECT_PATH = "/language/translate/v2/detect"
        internal const val MAX_BATCH_SIZE = 50
        internal const val MAX_RETRIES = 3
        internal const val INITIAL_BACKOFF_MS = 1000L
        internal const val BACKOFF_MULTIPLIER = 2L
        internal const val MAX_BACKOFF_MS = 16_000L
        private const val CONNECT_TIMEOUT_SECONDS = 4L
        private const val TIMEOUT_SECONDS = 5L
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MAX_ERROR_SNIPPET_LENGTH = 120
        private const val APPLICATION_JSON = "application/json"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val USER_AGENT_VALUE = "StellarLang/1.0.0"
        private const val SHORT_TEXT_THRESHOLD = 25
        private const val SHORT_TEXT_WORD_COUNT = 3
        private const val HEX_RADIX = 16
        private val COMMON_EUROPEAN_LANGS = setOf("fr", "de", "es")
        private val ENTITY_DEC_REGEX = Regex("&#(\\d+);")
        private val ENTITY_HEX_REGEX = Regex("&#x([0-9a-fA-F]+);")
    }
}

private fun buildChunkPayload(
    chunk: List<String>,
    normSource: String?,
    normTarget: String,
    isHtml: Boolean,
): JsonObject {
    val payload = JsonObject()
    val textArray = JsonArray()
    for (item in chunk) {
        textArray.add(item)
    }
    payload.add("q", textArray)
    payload.addProperty("target", normTarget)
    if (normSource != null) {
        payload.addProperty("source", normSource)
    }
    payload.addProperty("format", if (isHtml) "html" else "text")
    return payload
}
