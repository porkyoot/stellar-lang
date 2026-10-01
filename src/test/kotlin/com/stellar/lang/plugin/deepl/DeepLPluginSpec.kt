package com.stellar.lang.plugin.deepl

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

class DeepLPluginSpec : FunSpec({
    lateinit var server: HttpServer
    var serverPort: Int = 0
    val responseCode = AtomicInteger(200)
    var responseBody = """{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""
    var lastReceivedBody = ""
    var dynamicBatch = false

    beforeSpec {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/v2/translate") { exchange ->
            lastReceivedBody = exchange.requestBody.reader().readText()
            val body = if (dynamicBatch && responseCode.get() == 200) {
                val count = runCatching {
                    com.google.gson.JsonParser.parseString(lastReceivedBody).asJsonObject.getAsJsonArray("text").size()
                }.getOrDefault(1)
                val items = (1..count).map { """{"detected_source_language":"EN","text":"Trans $it"}""" }
                """{"translations":${items.joinToString(",", "[", "]")}}"""
            } else {
                responseBody
            }
            val bytes = body.toByteArray()
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
        responseBody = """{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""
        lastReceivedBody = ""
        dynamicBatch = false
        TranslationCache.resetCircuitBreaker()

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
        config.deeplApiHost.setValue("http://127.0.0.1:$serverPort", false)
        config.deeplApiKey.setValue("test_key:fx", false)
        config.deeplFormality.setValue("default", false)
    }

    test("DeepLPlugin metadata and status reporting") {
        val plugin = DeepLPlugin()
        plugin.id shouldBe "deepl"
        plugin.displayName shouldBe "DeepL (Official API)"
        plugin.description shouldContain "DeepL"

        val status = plugin.getStatus()
        status shouldBe PluginStatus.Ready("DeepL Free API (http://127.0.0.1:$serverPort)")

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.deeplApiKey.setValue("pro_key_123", false)
        config.deeplApiHost.setValue("auto", false)
        plugin.getStatus() shouldBe PluginStatus.Ready("DeepL Pro API (https://api.deepl.com)")

        config.deeplApiKey.setValue("", false)
        plugin.getStatus() shouldBe PluginStatus.NotConfigured("DeepL API key is not specified")
    }

    test("host resolution and endpoint normalization") {
        val plugin = DeepLPlugin()
        plugin.isFreeKey("abc:fx") shouldBe true
        plugin.isFreeKey("abc:FX") shouldBe true
        plugin.isFreeKey("abc") shouldBe false

        plugin.resolveHost("auto", "key:fx") shouldBe "https://api-free.deepl.com"
        plugin.resolveHost("auto", "key_pro") shouldBe "https://api.deepl.com"
        plugin.resolveHost("my-proxy.com", "key") shouldBe "https://my-proxy.com"
        plugin.resolveHost("http://my-proxy.com/", "key") shouldBe "http://my-proxy.com"

        val freeEndpoint = plugin.normalizeEndpoint("auto", "key:fx", "/v2/translate")
        freeEndpoint shouldBe "https://api-free.deepl.com/v2/translate"

        val localEndpoint = plugin.normalizeEndpoint("http://localhost:8080", "key", "v2/translate")
        localEndpoint shouldBe "http://localhost:8080/v2/translate"

        val existingEndpoint = plugin.normalizeEndpoint("http://localhost:8080/v2/translate", "key", "/v2/translate")
        existingEndpoint shouldBe "http://localhost:8080/v2/translate"
    }

    test("language code normalization for target and source") {
        val plugin = DeepLPlugin()

        plugin.normalizeTargetLanguage("en") shouldBe "EN-US"
        plugin.normalizeTargetLanguage("en-us") shouldBe "EN-US"
        plugin.normalizeTargetLanguage("en_us") shouldBe "EN-US"
        plugin.normalizeTargetLanguage("en-gb") shouldBe "EN-GB"
        plugin.normalizeTargetLanguage("pt") shouldBe "PT-PT"
        plugin.normalizeTargetLanguage("pt-pt") shouldBe "PT-PT"
        plugin.normalizeTargetLanguage("pt-br") shouldBe "PT-BR"
        plugin.normalizeTargetLanguage("zh") shouldBe "ZH-HANS"
        plugin.normalizeTargetLanguage("zh-cn") shouldBe "ZH-HANS"
        plugin.normalizeTargetLanguage("zh-tw") shouldBe "ZH-HANT"
        plugin.normalizeTargetLanguage("nb") shouldBe "NB"
        plugin.normalizeTargetLanguage("no") shouldBe "NB"
        plugin.normalizeTargetLanguage("de") shouldBe "DE"
        plugin.normalizeTargetLanguage("fr-fr") shouldBe "FR"

        plugin.normalizeSourceLanguage(null) shouldBe null
        plugin.normalizeSourceLanguage("") shouldBe null
        plugin.normalizeSourceLanguage("auto") shouldBe null
        plugin.normalizeSourceLanguage("unknown") shouldBe null
        plugin.normalizeSourceLanguage("no") shouldBe "NB"
        plugin.normalizeSourceLanguage("fr") shouldBe "FR"
        plugin.normalizeSourceLanguage("es-es") shouldBe "ES"
    }

    test("translate single text parses valid response and handles empty cases") {
        val plugin = DeepLPlugin()
        plugin.translate("Hello", "en", "de") shouldBe "Hallo"
        lastReceivedBody shouldContain """"text":["Hello"]"""
        lastReceivedBody shouldContain """"target_lang":"DE""""
        lastReceivedBody shouldContain """"source_lang":"EN""""
        lastReceivedBody.contains("tag_handling") shouldBe false

        plugin.translate("<ut>§a</ut>Hello", "en", "de") shouldBe "Hallo"
        lastReceivedBody shouldContain """"tag_handling":"xml""""
        lastReceivedBody shouldContain """"ignore_tags":["ut"]"""

        plugin.translate("   ", "en", "de") shouldBe "   "

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.deeplApiKey.setValue("", false)
        plugin.translate("Hello", "en", "de") shouldBe null
    }

    test("translate single text handles formality and omitted source_lang") {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.deeplFormality.setValue("prefer_more", false)

        val plugin = DeepLPlugin()
        plugin.translate("Hello", "auto", "de") shouldBe "Hallo"
        lastReceivedBody shouldContain """"formality":"prefer_more""""
        lastReceivedBody.contains("source_lang") shouldBe false
    }

    test("translateBatch parses responses and chunks requests exceeding batch limit") {
        val plugin = DeepLPlugin()
        plugin.translateBatch(emptyList(), "en", "de") shouldBe emptyList()

        responseBody = """{"translations":[{"detected_source_language":"EN","text":"Eins"},""" +
            """{"detected_source_language":"EN","text":"Zwei"}]}"""
        val batch = plugin.translateBatch(listOf("One", "Two"), "en", "de")
        batch shouldBe listOf("Eins", "Zwei")

        // Batch > 50 chunking
        dynamicBatch = true
        val largeList = (1..55).map { "Item $it" }
        val chunked = plugin.translateBatch(largeList, null, "de")
        chunked shouldNotBe null
        chunked?.size shouldBe 55
    }

    test("translate and batch error handling and circuit breaker trip") {
        val plugin = DeepLPlugin()

        responseCode.set(429)
        responseBody = """{"message":"Too many requests"}"""
        plugin.translate("Hello", "en", "de") shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true
        TranslationCache.resetCircuitBreaker()

        responseCode.set(456)
        responseBody = """{"message":"Quota exceeded"}"""
        plugin.translateBatch(listOf("A"), "en", "de") shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true
        TranslationCache.resetCircuitBreaker()

        responseCode.set(403)
        responseBody = """{"message":"Forbidden"}"""
        plugin.translate("Hello", "en", "de") shouldBe null

        responseCode.set(200)
        responseBody = """{"translations":[]}"""
        plugin.translateBatch(listOf("A", "B"), "en", "de") shouldBe null

        // Unreachable host
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.deeplApiHost.setValue("http://127.0.0.1:1", false)
        plugin.translate("Fail", "en", "de") shouldBe null
    }

    test("detectLanguage parses detected_source_language and handles non-letter text") {
        val plugin = DeepLPlugin()
        plugin.detectLanguage("   ") shouldBe null
        plugin.detectLanguage("12345 6789") shouldBe null

        responseBody = """{"translations":[{"detected_source_language":"FR","text":"Bonjour"}]}"""
        plugin.detectLanguage("Bonjour le monde") shouldBe "fr"

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.deeplApiKey.setValue("", false)
        plugin.detectLanguage("Bonjour") shouldBe null
        config.deeplApiKey.setValue("test_key", false)

        responseBody = """{"translations":[]}"""
        plugin.detectLanguage("Bonjour") shouldBe null

        responseCode.set(500)
        plugin.detectLanguage("Bonjour") shouldBe null
    }

    test("testConnection validates key, success response, and error responses") {
        val plugin = DeepLPlugin()

        val emptyRes = plugin.testConnection("", "auto", "de")
        emptyRes.isFailure shouldBe true
        emptyRes.exceptionOrNull()?.message shouldContain "empty"

        responseCode.set(200)
        responseBody = """{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""
        val okRes = plugin.testConnection("valid_key", "http://127.0.0.1:$serverPort", "de")
        okRes.isSuccess shouldBe true
        okRes.getOrNull() shouldBe "Hallo"

        responseBody = """{"translations":[]}"""
        val fallbackOk = plugin.testConnection("valid_key", "http://127.0.0.1:$serverPort", "auto")
        fallbackOk.isSuccess shouldBe true
        fallbackOk.getOrNull() shouldBe "OK (HTTP 200)"

        responseCode.set(400)
        responseBody = """{"message":"Value for target_lang not supported"}"""
        val errRes = plugin.testConnection("valid_key", "http://127.0.0.1:$serverPort", "invalid")
        errRes.isFailure shouldBe true
        errRes.exceptionOrNull()?.message shouldContain "Value for target_lang not supported"

        // Non-JSON error body
        responseCode.set(500)
        responseBody = "Internal error raw text"
        val rawErr = plugin.testConnection("valid_key", "http://127.0.0.1:$serverPort", "de")
        rawErr.isFailure shouldBe true
        rawErr.exceptionOrNull()?.message shouldContain "Internal error raw text"

        // Network error
        val netErr = plugin.testConnection("valid_key", "http://127.0.0.1:1")
        netErr.isFailure shouldBe true

        // Default targetLang test with local server
        responseCode.set(200)
        responseBody = """{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""
        val defaultTargetRes = plugin.testConnection("valid_key", "http://127.0.0.1:$serverPort")
        defaultTargetRes.isSuccess shouldBe true

        // Default host ("auto") attempts real remote endpoint and fails in local mock test
        val defaultHostRes = plugin.testConnection("valid_key")
        defaultHostRes.isFailure shouldBe true

        // Direct executeTranslate and executeBatchTranslate with default formality
        val directTrans = plugin.executeTranslate("Hi", "en", "de", "http://127.0.0.1:$serverPort", "valid_key")
        directTrans shouldBe "Hallo"

        val directBatch = plugin.executeBatchTranslate(
            listOf("Hi"),
            "en",
            "de",
            "http://127.0.0.1:$serverPort",
            "valid_key",
        )
        directBatch shouldBe listOf("Hallo")

        // translateBatch with blank apiKey
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.deeplApiKey.setValue("", false)
        plugin.translateBatch(listOf("Hello"), "en", "de") shouldBe null
    }
})
