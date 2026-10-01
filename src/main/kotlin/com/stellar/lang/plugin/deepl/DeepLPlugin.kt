@file:Suppress(
    "TooGenericExceptionCaught",
    "LongParameterList",
    "CognitiveComplexMethod",
    "StringLiteralDuplication",
    "LargeClass",
    "LongMethod",
    "ReturnCount",
)

package com.stellar.lang.plugin.deepl

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.LanguageDetectorPlugin
import com.stellar.lang.plugin.PluginStatus
import com.stellar.lang.plugin.TranslationPlugin
import com.stellar.lang.service.TranslationCache
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Translation and Language Detection plugin backed by DeepL API (Free or Pro).
 */
class DeepLPlugin(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
) : TranslationPlugin, LanguageDetectorPlugin {
    override val id: String = "deepl"
    override val displayName: String = "DeepL (Official API)"
    override val description: String =
        "Translates and detects language using DeepL's high-accuracy neural translation API (Free or Pro)."

    private val logger: Logger = LoggerFactory.getLogger("StellarLang-DeepL")
    private val gson = Gson()

    override fun getStatus(): PluginStatus {
        val config = getConfig()
        val apiKey = config.deeplApiKey.value().trim()
        return if (apiKey.isBlank()) {
            PluginStatus.NotConfigured("DeepL API key is not specified")
        } else {
            val endpoint = resolveHost(config.deeplApiHost.value(), apiKey)
            val tier = if (isFreeKey(apiKey)) "Free" else "Pro"
            PluginStatus.Ready("DeepL $tier API ($endpoint)")
        }
    }

    override suspend fun detectLanguage(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isLetter() }) return null

        val config = getConfig()
        val apiKey = config.deeplApiKey.value().trim()
        if (apiKey.isBlank()) return null

        val host = config.deeplApiHost.value()
        return executeDetect(trimmed, host, apiKey)
    }

    override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text

        val config = getConfig()
        val apiKey = config.deeplApiKey.value().trim()
        if (apiKey.isBlank()) return null

        val host = config.deeplApiHost.value()
        val formality = config.deeplFormality.value()
        return executeTranslate(text, sourceLang, targetLang, host, apiKey, formality)
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
    ): List<String>? {
        if (texts.isEmpty()) return emptyList()

        val config = getConfig()
        val apiKey = config.deeplApiKey.value().trim()
        if (apiKey.isBlank()) return null

        val host = config.deeplApiHost.value()
        val formality = config.deeplFormality.value()
        return executeBatchTranslate(texts, sourceLang, targetLang, host, apiKey, formality)
    }

    fun executeTranslate(
        text: String,
        sourceLang: String?,
        targetLang: String,
        host: String,
        apiKey: String,
        formality: String = "default",
    ): String? {
        val results = executeBatchTranslate(listOf(text), sourceLang, targetLang, host, apiKey, formality)
        return results?.firstOrNull()
    }

    data class DeepLTranslationResult(
        val translatedText: String,
        val detectedSourceLanguage: String?,
    )

    fun executeBatchTranslate(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
        host: String,
        apiKey: String,
        formality: String = "default",
    ): List<String>? {
        return executeBatchTranslateDetailed(texts, sourceLang, targetLang, host, apiKey, formality)
            ?.map { it.translatedText }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun executeBatchTranslateDetailed(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
        host: String,
        apiKey: String,
        formality: String = "default",
    ): List<DeepLTranslationResult>? {
        if (texts.isEmpty()) return emptyList()
        val normTarget = normalizeTargetLanguage(targetLang)
        val normSource = normalizeSourceLanguage(sourceLang)

        return runCatching {
            val chunks = texts.chunked(MAX_BATCH_SIZE)
            val translatedResults = mutableListOf<DeepLTranslationResult>()
            val endpoint = URI.create(normalizeEndpoint(host, apiKey, "/v2/translate"))

            for (chunk in chunks) {
                val hasTags = chunk.any { it.contains("<ut>", ignoreCase = true) }
                val payload = buildChunkPayload(chunk, normSource, normTarget, formality, hasTags)
                var response = sendDeepLRequest(endpoint, apiKey, payload)

                // If DeepL returned HTTP 400 on XML tag handling, retry chunk with plain text
                if (response.statusCode() == HTTP_BAD_REQUEST && hasTags) {
                    logger.warn("DeepL rejected XML tags with HTTP 400. Retrying chunk as sanitized plain text...")
                    val plainChunk = chunk.map {
                        com.stellar.lang.format.FormattingTagHelper.decodeFromUntranslatableTags(it)
                    }
                    val fallbackPayload = buildChunkPayload(
                        plainChunk,
                        normSource,
                        normTarget,
                        formality,
                        includeTags = false,
                    )
                    response = sendDeepLRequest(endpoint, apiKey, fallbackPayload)
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
            logger.warn("DeepL translation failed: {}", ex.message)
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
    ): List<DeepLTranslationResult>? {
        val transArray = json.getAsJsonArray("translations") ?: return null
        if (transArray.size() != chunk.size) {
            logger.warn("DeepL returned {} translations for {} items", transArray.size(), chunk.size)
            return null
        }
        val results = mutableListOf<DeepLTranslationResult>()
        for (i in 0 until transArray.size()) {
            val item = transArray.get(i).asJsonObject
            val translated = item.get("text")?.asString ?: chunk[i]
            val detected = item.get("detected_source_language")?.asString?.lowercase()
            results.add(DeepLTranslationResult(translated, detected))
        }
        return results
    }

    private fun sendDeepLRequest(
        endpoint: URI,
        apiKey: String,
        payload: JsonObject,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header(HEADER_AUTHORIZATION, "DeepL-Auth-Key $apiKey")
            .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
            .header(HEADER_ACCEPT, APPLICATION_JSON)
            .header(HEADER_USER_AGENT, USER_AGENT_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    fun executeDetect(text: String, host: String, apiKey: String): String? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host, apiKey, "/v2/translate"))
            val payload = JsonObject().apply {
                val textArray = JsonArray()
                textArray.add(text)
                add("text", textArray)
                addProperty("target_lang", "EN-US")
            }

            val response = sendDeepLRequest(endpoint, apiKey, payload)
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                val transArray = json.getAsJsonArray("translations")
                if (transArray != null && !transArray.isEmpty) {
                    val first = transArray.get(0).asJsonObject
                    first.get("detected_source_language")?.asString?.lowercase()
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
            return Result.failure(IllegalArgumentException("DeepL API key is empty"))
        }

        val effectiveTarget = if (targetLang.isBlank() || targetLang.equals("auto", ignoreCase = true)) {
            "EN-US"
        } else {
            normalizeTargetLanguage(targetLang)
        }

        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host, cleanKey, "/v2/translate"))
            val payload = JsonObject().apply {
                val textArray = JsonArray()
                textArray.add("Hello")
                add("text", textArray)
                addProperty("target_lang", effectiveTarget)
            }

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_AUTHORIZATION, "DeepL-Auth-Key $cleanKey")
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .header(HEADER_USER_AGENT, USER_AGENT_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                val transArray = json.getAsJsonArray("translations")
                if (transArray != null && !transArray.isEmpty) {
                    transArray.get(0).asJsonObject.get("text").asString
                } else {
                    "OK (HTTP ${response.statusCode()})"
                }
            } else {
                val errMsg = runCatching {
                    JsonParser.parseString(response.body()).asJsonObject.get("message")?.asString
                }.getOrNull() ?: response.body().take(MAX_ERROR_SNIPPET_LENGTH)
                error("HTTP ${response.statusCode()}: $errMsg")
            }
        }
    }

    private fun handleHttpError(statusCode: Int, responseBody: String) {
        if (statusCode == HTTP_TOO_MANY_REQUESTS || statusCode == HTTP_QUOTA_EXCEEDED) {
            TranslationCache.tripCircuitBreaker()
        }
        val detail = runCatching {
            JsonParser.parseString(responseBody).asJsonObject.get("message")?.asString
        }.getOrNull() ?: responseBody.take(MAX_ERROR_SNIPPET_LENGTH)
        logger.warn("DeepL HTTP {}: {}", statusCode, detail)
        val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyHttpStatus(
            providerId = id,
            statusCode = statusCode,
            responseBody = detail,
        )
        com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
    }

    fun resolveHost(host: String, apiKey: String): String {
        val trimmed = host.trim()
        if (trimmed.isNotBlank() && !trimmed.equals("auto", ignoreCase = true)) {
            var formatted = trimmed.trimEnd('/')
            if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
                formatted = "https://$formatted"
            }
            return formatted
        }
        return if (isFreeKey(apiKey)) DEFAULT_FREE_API_HOST else DEFAULT_PRO_API_HOST
    }

    fun normalizeEndpoint(host: String, apiKey: String, path: String): String {
        val base = resolveHost(host, apiKey)
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return if (base.endsWith(cleanPath)) base else "$base$cleanPath"
    }

    fun isFreeKey(apiKey: String): Boolean {
        return apiKey.trim().lowercase().endsWith(":fx")
    }

    fun normalizeTargetLanguage(lang: String): String {
        val clean = lang.trim().lowercase().replace('_', '-')
        return when (clean) {
            "en", "en-us" -> "EN-US"
            "en-gb" -> "EN-GB"
            "pt", "pt-pt" -> "PT-PT"
            "pt-br" -> "PT-BR"
            "zh", "zh-cn", "zh-hans" -> "ZH-HANS"
            "zh-tw", "zh-hk", "zh-hant" -> "ZH-HANT"
            "nb", "no" -> "NB"
            else -> clean.substringBefore('-').uppercase()
        }
    }

    fun normalizeSourceLanguage(lang: String?): String? {
        if (lang.isNullOrBlank()) return null
        val clean = lang.trim().lowercase().replace('_', '-')
        if (clean == "auto" || clean == "unknown") return null
        val primary = clean.substringBefore('-')
        return when (primary) {
            "no" -> "NB"
            else -> primary.uppercase()
        }
    }

    private fun getConfig(): StellarLangConfig {
        return ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
    }

    companion object {
        const val DEFAULT_FREE_API_HOST = "https://api-free.deepl.com"
        const val DEFAULT_PRO_API_HOST = "https://api.deepl.com"
        private const val CONNECT_TIMEOUT_SECONDS = 4L
        private const val TIMEOUT_SECONDS = 5L
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_QUOTA_EXCEEDED = 456
        private const val MAX_BATCH_SIZE = 50
        private const val MAX_ERROR_SNIPPET_LENGTH = 100
        private const val APPLICATION_JSON = "application/json"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_ACCEPT = "Accept"
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val USER_AGENT_VALUE = "StellarLang/1.0.0"
    }
}

private val VALID_FORMALITIES = setOf("more", "less", "prefer_more", "prefer_less")

private fun isValidFormality(formality: String): Boolean {
    return formality.trim().lowercase() in VALID_FORMALITIES
}

private fun buildChunkPayload(
    chunk: List<String>,
    normSource: String?,
    normTarget: String,
    formality: String,
    includeTags: Boolean,
): JsonObject {
    val payload = JsonObject()
    val textArray = JsonArray()
    for (item in chunk) {
        textArray.add(item)
    }
    payload.add("text", textArray)
    payload.addProperty("target_lang", normTarget)
    if (normSource != null) {
        payload.addProperty("source_lang", normSource)
    }
    if (isValidFormality(formality)) {
        payload.addProperty("formality", formality.trim().lowercase())
    }
    if (includeTags) {
        payload.addProperty("tag_handling", "xml")
        val ignoreTagsArray = JsonArray()
        ignoreTagsArray.add("ut")
        payload.add("ignore_tags", ignoreTagsArray)
    }
    return payload
}
