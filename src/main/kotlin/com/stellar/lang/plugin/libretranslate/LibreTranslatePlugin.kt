@file:Suppress(
    "TooGenericExceptionCaught",
    "LongParameterList",
    "CognitiveComplexMethod",
    "StringLiteralDuplication",
)

package com.stellar.lang.plugin.libretranslate

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
 * Translation and Language Detection plugin backed by a remote or local LibreTranslate HTTP server.
 */
class LibreTranslatePlugin(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
) : TranslationPlugin, LanguageDetectorPlugin {
    override val id: String = "libretranslate"
    override val displayName: String = "LibreTranslate (HTTP API)"
    override val description: String =
        "Translates and detects language using a remote or self-hosted LibreTranslate server."

    private val logger: Logger = LoggerFactory.getLogger("StellarLang-LibreTranslate")
    private val gson = Gson()

    override fun getStatus(): PluginStatus {
        val config = getConfig()
        val host = config.apiHost.value().trim()
        return if (host.isBlank()) {
            PluginStatus.NotConfigured("API host is not specified")
        } else {
            PluginStatus.Ready("Host: $host")
        }
    }

    override suspend fun detectLanguage(text: String): String? {
        val config = getConfig()
        val host = config.apiHost.value()
        val apiKey = config.apiKey.value()
        return executeDetect(text, host, apiKey)
    }

    override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
        val config = getConfig()
        val host = config.apiHost.value()
        val apiKey = config.apiKey.value()
        return executeTranslate(text, sourceLang ?: "auto", targetLang, host, apiKey)
    }

    override suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String?,
        targetLang: String,
    ): List<String>? {
        val config = getConfig()
        val host = config.apiHost.value()
        val apiKey = config.apiKey.value()
        return executeBatchTranslate(texts, sourceLang ?: "auto", targetLang, host, apiKey)
    }

    fun executeTranslate(
        text: String,
        sourceLang: String,
        targetLang: String,
        host: String,
        apiKey: String,
    ): String? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host, "/translate"))
            val payload = JsonObject().apply {
                addProperty("q", text)
                addProperty("source", sourceLang)
                addProperty("target", targetLang)
                addProperty("format", "text")
                if (apiKey.isNotBlank()) addProperty("api_key", apiKey)
            }

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                json.get("translatedText")?.asString
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                logger.warn("LibreTranslate HTTP {}: {}", response.statusCode(), response.body())
                null
            }
        }.getOrElse { ex ->
            logger.warn("LibreTranslate translate failed for '{}': {}", text, ex.message)
            null
        }
    }

    fun executeBatchTranslate(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        host: String,
        apiKey: String,
    ): List<String>? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host, "/translate"))
            val payload = JsonObject().apply {
                val array = JsonArray()
                texts.forEach { array.add(it) }
                add("q", array)
                addProperty("source", sourceLang)
                addProperty("target", targetLang)
                addProperty("format", "text")
                if (apiKey.isNotBlank()) addProperty("api_key", apiKey)
            }

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                val transArray = json.get("translatedText")?.asJsonArray ?: return null
                texts.indices.map { idx ->
                    transArray.get(idx)?.asString ?: texts[idx]
                }
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                null
            }
        }.getOrElse { ex ->
            logger.warn("LibreTranslate batch failed: {}", ex.message)
            null
        }
    }

    fun executeDetect(text: String, host: String, apiKey: String): String? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host, "/detect"))
            val payload = JsonObject().apply {
                addProperty("q", text)
                if (apiKey.isNotBlank()) addProperty("api_key", apiKey)
            }

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                val json = JsonParser.parseString(response.body())
                if (json.isJsonArray && !json.asJsonArray.isEmpty) {
                    val first = json.asJsonArray.get(0).asJsonObject
                    first.get("language")?.asString
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }

    fun normalizeEndpoint(host: String, path: String): String {
        var trimmed = host.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return if (trimmed.endsWith(path)) trimmed else "$trimmed$path"
    }

    private fun getConfig(): StellarLangConfig {
        return ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 4L
        private const val TIMEOUT_SECONDS = 5L
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val APPLICATION_JSON = "application/json"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_ACCEPT = "Accept"
    }
}
