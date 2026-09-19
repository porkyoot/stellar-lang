package com.stellar.lang.plugin.libretranslate

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginStatus
import com.stellar.lang.service.TranslationCache
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class LibreTranslatePluginSpec : FunSpec({
    lateinit var server: HttpServer
    var serverPort: Int = 0
    val responseCode = AtomicInteger(200)
    var responseBody = """{"translatedText": "Bonjour", "detectedLanguage": "en"}"""

    beforeSpec {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/translate") { exchange ->
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseCode.get(), bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        server.createContext("/detect") { exchange ->
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseCode.get(), bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        server.start()
    }

    afterSpec {
        server.stop(0)
    }

    beforeEach {
        responseCode.set(200)
        responseBody = """{"translatedText": "Bonjour", "detectedLanguage": "en"}"""
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
        config.apiHost.setValue("http://127.0.0.1:$serverPort", false)
        config.apiKey.setValue("test_key", false)
    }

    test("LibreTranslatePlugin metadata and status") {
        val plugin = LibreTranslatePlugin()
        plugin.id shouldBe "libretranslate"
        plugin.displayName shouldBe "LibreTranslate (HTTP API)"
        plugin.description shouldContain "LibreTranslate"

        val status = plugin.getStatus()
        status shouldBe PluginStatus.Ready("Host: http://127.0.0.1:$serverPort")

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.apiHost.setValue("", false)
        plugin.getStatus() shouldBe PluginStatus.NotConfigured("API host is not specified")
    }

    test("normalizeEndpoint handles various URLs") {
        val plugin = LibreTranslatePlugin()
        plugin.normalizeEndpoint("localhost:5000", "/translate") shouldBe "http://localhost:5000/translate"
        plugin.normalizeEndpoint(
            "https://example.com/translate/",
            "/translate",
        ) shouldBe "https://example.com/translate"
        plugin.normalizeEndpoint("http://example.com/api", "/detect") shouldBe "http://example.com/api/detect"
    }

    test("detectLanguage parses successful response and handles empty or error responses") {
        val plugin = LibreTranslatePlugin()
        responseBody = """[{"confidence": 98.5, "language": "es"}]"""
        plugin.detectLanguage("Hola mundo") shouldBe "es"

        responseBody = "[]"
        plugin.detectLanguage("Unknown") shouldBe null

        responseCode.set(500)
        plugin.detectLanguage("Fail") shouldBe null

        // Unreachable host
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.apiHost.setValue("http://127.0.0.1:1", false)
        plugin.detectLanguage("Error") shouldBe null
    }

    test("translate executes translation and handles error codes") {
        val plugin = LibreTranslatePlugin()
        responseBody = """{"translatedText": "Bonjour"}"""
        plugin.translate("Hello", "en", "fr") shouldBe "Bonjour"

        responseCode.set(429)
        responseBody = """{"error": "Too Many Requests"}"""
        plugin.translate("RateLimit", "en", "fr") shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true
        TranslationCache.resetCircuitBreaker()

        responseCode.set(500)
        plugin.translate("Error", "en", "fr") shouldBe null

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.apiHost.setValue("http://127.0.0.1:1", false)
        plugin.translate("NetworkError", "en", "fr") shouldBe null
    }

    test("translateBatch executes batch translation and handles errors") {
        val plugin = LibreTranslatePlugin()
        responseBody = """{"translatedText": ["Uno", "Dos"]}"""
        val result = plugin.translateBatch(listOf("One", "Two"), "en", "es")
        result shouldNotBe null
        result shouldBe listOf("Uno", "Dos")

        responseCode.set(429)
        plugin.translateBatch(listOf("A", "B"), "en", "es") shouldBe null
        TranslationCache.resetCircuitBreaker()

        responseCode.set(500)
        plugin.translateBatch(listOf("A", "B"), "en", "es") shouldBe null

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.apiHost.setValue("http://127.0.0.1:1", false)
        plugin.translateBatch(listOf("A", "B"), "en", "es") shouldBe null
    }
})
