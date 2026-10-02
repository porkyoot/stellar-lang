@file:Suppress("LargeClass")

package com.stellar.lang.plugin.deepl

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginStatus
import com.stellar.lang.service.TranslationCache
import com.stellar.lang.service.TranslationService
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
    var tagRejectionTest = false

    beforeSpec {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/v2/translate") { exchange ->
            lastReceivedBody = exchange.requestBody.reader().readText()
            val isTagRejection = tagRejectionTest && lastReceivedBody.contains("tag_handling")
            val code = if (isTagRejection) 400 else responseCode.get()
            val body = if (isTagRejection) {
                """{"message":"Tag handling parsing failed"}"""
            } else if (dynamicBatch && code == 200) {
                val count = runCatching {
                    com.google.gson.JsonParser.parseString(lastReceivedBody).asJsonObject.getAsJsonArray("text").size()
                }.getOrDefault(1)
                val items = (1..count).map { """{"detected_source_language":"EN","text":"Trans $it"}""" }
                """{"translations":${items.joinToString(",", "[", "]")}}"""
            } else {
                responseBody
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
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
        tagRejectionTest = false
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

    test("translate handles HTTP 400 tag rejection by falling back to plain text") {
        val plugin = DeepLPlugin()
        tagRejectionTest = true
        val result = plugin.translate("<ut>§a</ut>Hello", "en", "de")
        result shouldBe "Hallo"
        lastReceivedBody.contains("tag_handling") shouldBe false
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

    test("parseDeepLDetect enforces thresholds and rejects slang/broken English") {
        val plugin = DeepLPlugin()

        // Null / blank / unsupported
        plugin.parseDeepLDetect(null, "text") shouldBe null
        plugin.parseDeepLDetect("   ", "text") shouldBe null
        plugin.parseDeepLDetect("xx", "text") shouldBe null
        plugin.parseDeepLDetect("klingon", "text") shouldBe null

        // English input
        plugin.parseDeepLDetect("EN", "Hello world") shouldBe "en"
        plugin.parseDeepLDetect("EN-US", "Hello world") shouldBe "en"
        plugin.parseDeepLDetect("en-gb", "Colour") shouldBe "en"

        // Universal slang and textmojis rejected
        plugin.parseDeepLDetect("ID", "ok") shouldBe null
        plugin.parseDeepLDetect("ID", "lmao") shouldBe null
        plugin.parseDeepLDetect("ID", "lol") shouldBe null
        plugin.parseDeepLDetect("ID", "xd") shouldBe null
        plugin.parseDeepLDetect("JA", "¯\\_(ツ)_/¯") shouldBe null
        plugin.parseDeepLDetect("JA", "(╯°□°)╯︵ ┻━┻") shouldBe null
        plugin.parseDeepLDetect("FR", "looooool") shouldBe null

        // Broken English rejected
        plugin.parseDeepLDetect("ID", "why u kill me") shouldBe null
        plugin.parseDeepLDetect("ID", "wat r u doing") shouldBe null
        plugin.parseDeepLDetect("TL", "pls come base") shouldBe null
        plugin.parseDeepLDetect("IT", "no u") shouldBe null

        // Repeating characters with plain Latin rejected
        plugin.parseDeepLDetect("NL", "heeeelp") shouldBe null
        plugin.parseDeepLDetect("ET", "noooooo") shouldBe null

        // Obscure non-European language on short plain Latin text rejected
        plugin.parseDeepLDetect("ID", "short text") shouldBe null
        plugin.parseDeepLDetect("TL", "some words here") shouldBe null

        // Genuine French, Spanish, German accepted
        plugin.parseDeepLDetect("FR", "Bonjour le monde") shouldBe "fr"
        plugin.parseDeepLDetect("ES", "Hola amigo") shouldBe "es"
        plugin.parseDeepLDetect("DE", "Wie gehts") shouldBe "de"
        plugin.parseDeepLDetect("FR", "Ta mère est pas là") shouldBe "fr"
        plugin.parseDeepLDetect("DE", "Schöne Grüße") shouldBe "de"

        // Genuine non-Latin scripts accepted
        plugin.parseDeepLDetect("RU", "Привет мир") shouldBe "ru"
        plugin.parseDeepLDetect("JA", "こんにちは") shouldBe "ja"
    }

    test("translate immediately returns textmojis and universal slang for English target without network") {
        val plugin = DeepLPlugin()

        plugin.translate("¯\\_(ツ)_/¯", null, "de") shouldBe "¯\\_(ツ)_/¯"
        plugin.translate("(╯°□°)╯︵ ┻━┻", null, "fr") shouldBe "(╯°□°)╯︵ ┻━┻"
        plugin.translate("ok", null, "en") shouldBe "ok"
        plugin.translate("lmao", null, "en") shouldBe "lmao"
    }

    test("detectLanguage handles universal slang and broken English") {
        val plugin = DeepLPlugin()

        plugin.detectLanguage("¯\\_(ツ)_/¯") shouldBe null
        plugin.detectLanguage("ok") shouldBe null
        plugin.detectLanguage("lmao") shouldBe null
        plugin.detectLanguage("why u kill me") shouldBe "en"
    }

    test("executeBatchTranslateDetailed applies parseDeepLDetect filtering on detected source language") {
        val plugin = DeepLPlugin()

        responseBody = """{"translations":[{"detected_source_language":"ID","text":"translated"}]}"""
        val results = plugin.executeBatchTranslateDetailed(
            listOf("why u kill me"),
            null,
            "en",
            "http://127.0.0.1:$serverPort",
            "test_key:fx",
        )
        results shouldNotBe null
        results?.firstOrNull()?.detectedSourceLanguage shouldBe null

        responseBody = """{"translations":[{"detected_source_language":"FR","text":"Hello world"}]}"""
        val frResults = plugin.executeBatchTranslateDetailed(
            listOf("Bonjour le monde"),
            null,
            "en",
            "http://127.0.0.1:$serverPort",
            "test_key:fx",
        )
        frResults shouldNotBe null
        frResults?.firstOrNull()?.detectedSourceLanguage shouldBe "fr"
        frResults?.firstOrNull()?.translatedText shouldBe "Hello world"
    }

    test("TranslationService with DeepL handles broken English, textmojis, and genuine translation") {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.deeplApiHost.setValue("http://127.0.0.1:$serverPort", false)
        config.deeplApiKey.setValue("test_key:fx", false)
        config.translationPlugin.setValue("deepl", false)
        config.targetLanguage.setValue("en", false)

        TranslationCache.clear()

        // 1. Textmoji: should be returned as same language
        val textmojiRes = TranslationService.translateBatchSync(listOf("¯\\_(ツ)_/¯"))?.firstOrNull()
        textmojiRes shouldNotBe null
        textmojiRes?.translatedText shouldBe "¯\\_(ツ)_/¯"
        textmojiRes?.isSameLanguage shouldBe true

        // 2. Slang: should be returned as same language
        val slangRes = TranslationService.translateBatchSync(listOf("lmao"))?.firstOrNull()
        slangRes shouldNotBe null
        slangRes?.translatedText shouldBe "lmao"
        slangRes?.isSameLanguage shouldBe true

        // 3. Broken English: should be returned as same language
        val brokenEngRes = TranslationService.translateBatchSync(listOf("why u kill me"))?.firstOrNull()
        brokenEngRes shouldNotBe null
        brokenEngRes?.translatedText shouldBe "why u kill me"
        brokenEngRes?.isSameLanguage shouldBe true

        // 4. Batch translation with DeepL
        responseBody = """{"translations":[{"detected_source_language":"FR","text":"Hello world"}]}"""
        val batchRes = TranslationService.translateBatchSync(listOf("Bonjour le monde"))
        batchRes shouldNotBe null
        batchRes?.firstOrNull()?.translatedText shouldBe "Hello world"
        batchRes?.firstOrNull()?.detectedLanguage shouldBe "fr"
        batchRes?.firstOrNull()?.isSameLanguage shouldBe false
    }

    test("parseRetryAfterMs correctly parses integer seconds and handles null or invalid values") {
        val plugin = DeepLPlugin()
        plugin.parseRetryAfterMs(null) shouldBe null
        plugin.parseRetryAfterMs("") shouldBe null
        plugin.parseRetryAfterMs("   ") shouldBe null
        plugin.parseRetryAfterMs("not_a_number") shouldBe null

        plugin.parseRetryAfterMs("5") shouldBe 5000L
        plugin.parseRetryAfterMs("120") shouldBe 120_000L
        plugin.parseRetryAfterMs("0") shouldBe 0L
    }

    test("isLocalHost detects localhost and loopback addresses accurately") {
        val plugin = DeepLPlugin()
        plugin.isLocalHost("http://127.0.0.1:8080") shouldBe true
        plugin.isLocalHost("127.0.0.1") shouldBe true
        plugin.isLocalHost("http://localhost:5000") shouldBe true
        plugin.isLocalHost("localhost") shouldBe true
        plugin.isLocalHost("https://api.deepl.com") shouldBe false
        plugin.isLocalHost("https://api-free.deepl.com") shouldBe false
    }

    test("DeepLPlugin recovers from HTTP 429 when retry succeeds without tripping circuit breaker") {
        val plugin = DeepLPlugin()
        val attempts = AtomicInteger(0)

        // Reset server context to simulate 429 on first attempt, then 200 on retry
        server.removeContext("/v2/translate")
        server.createContext("/v2/translate") { exchange ->
            val count = attempts.incrementAndGet()
            if (count == 1) {
                val body = """{"message":"Too many requests"}"""
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(429, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            } else {
                val body = """{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
        }

        try {
            val result = plugin.translate("Hello", "en", "de")
            result shouldBe "Hallo"
            attempts.get() shouldBe 2
            TranslationCache.isCircuitBreakerOpen() shouldBe false
        } finally {
            // Restore default server context
            server.removeContext("/v2/translate")
            server.createContext("/v2/translate") { exchange ->
                lastReceivedBody = exchange.requestBody.reader().readText()
                val isTagRejection = tagRejectionTest && lastReceivedBody.contains("tag_handling")
                val code = if (isTagRejection) 400 else responseCode.get()
                val body = if (isTagRejection) {
                    """{"message":"Tag handling parsing failed"}"""
                } else if (dynamicBatch && code == 200) {
                    val count = runCatching {
                        val parsedObj = com.google.gson.JsonParser.parseString(lastReceivedBody).asJsonObject
                        parsedObj.getAsJsonArray("text").size()
                    }.getOrDefault(1)
                    val items = (1..count).map { """{"detected_source_language":"EN","text":"Trans $it"}""" }
                    """{"translations":${items.joinToString(",", "[", "]")}}"""
                } else {
                    responseBody
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
        }
    }

    test("DeepLPlugin enforces test pacing interval between requests") {
        val plugin = DeepLPlugin()
        plugin.testPacingIntervalMs = 50L

        try {
            val t1 = System.currentTimeMillis()
            plugin.translate("First", "en", "de")
            plugin.translate("Second", "en", "de")
            val elapsed = System.currentTimeMillis() - t1
            (elapsed >= 45L) shouldBe true
        } finally {
            plugin.testPacingIntervalMs = null
        }
    }

    test("DeepLPlugin parseRetryAfterMs and state properties") {
        val plugin = DeepLPlugin()
        plugin.parseRetryAfterMs(null) shouldBe null
        plugin.parseRetryAfterMs("   ") shouldBe null
        plugin.parseRetryAfterMs("25") shouldBe 25_000L

        val dateStr = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.ZonedDateTime.now().plusSeconds(10),
        )
        val ms = plugin.parseRetryAfterMs(dateStr)
        (ms != null && ms >= 0L) shouldBe true
        plugin.parseRetryAfterMs("not-a-valid-date") shouldBe null

        plugin.lastRequestTime = 9999L
        plugin.lastRequestTime shouldBe 9999L

        plugin.getPacingInterval("http://localhost:8080") shouldBe 0L
    }
})
