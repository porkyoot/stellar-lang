@file:Suppress("LargeClass")

package com.stellar.lang.plugin.google

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

class GooglePluginSpec : FunSpec({
    lateinit var server: HttpServer
    var serverPort: Int = 0
    val responseCode = AtomicInteger(200)
    var responseBody = """{"data":{"translations":[{"translatedText":"Hola","detectedSourceLanguage":"en"}]}}"""
    var detectResponseBody = """{"data":{"detections":[[{"language":"fr","confidence":0.95}]]}}"""
    var lastReceivedBody = ""
    var lastReceivedPath = ""
    var lastReceivedQuery = ""
    var dynamicBatch = false
    var tagRejectionTest = false
    val rateLimitAttempts = AtomicInteger(0)
    var retryAfterHeader: String? = null

    beforeSpec {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/language/translate/v2") { exchange ->
            lastReceivedBody = exchange.requestBody.reader().readText()
            lastReceivedPath = exchange.requestURI.path
            lastReceivedQuery = exchange.requestURI.query ?: ""

            if (rateLimitAttempts.getAndDecrement() > 0) {
                if (retryAfterHeader != null) {
                    exchange.responseHeaders.add("Retry-After", retryAfterHeader)
                }
                val bytes = """{"error":{"code":429,"message":"Rate limit"}}""".toByteArray()
                exchange.sendResponseHeaders(429, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            } else if (lastReceivedPath.endsWith("/detect")) {
                val bytes = detectResponseBody.toByteArray()
                exchange.sendResponseHeaders(responseCode.get(), bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            } else {
                val isTagRejection = tagRejectionTest && lastReceivedBody.contains(""""format":"html"""")
                val code = if (isTagRejection) 400 else responseCode.get()
                val body = if (isTagRejection) {
                    """{"error":{"code":400,"message":"Invalid HTML payload"}}"""
                } else if (dynamicBatch && code == 200) {
                    val count = runCatching {
                        com.google.gson.JsonParser.parseString(lastReceivedBody).asJsonObject.getAsJsonArray("q").size()
                    }.getOrDefault(1)
                    val items = (1..count).map { """{"translatedText":"Trans $it","detectedSourceLanguage":"en"}""" }
                    """{"data":{"translations":${items.joinToString(",", "[", "]")}}}"""
                } else {
                    responseBody
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
        }

        server.start()
    }

    afterSpec {
        server.stop(0)
    }

    beforeEach {
        responseCode.set(200)
        responseBody = """{"data":{"translations":[{"translatedText":"Hola","detectedSourceLanguage":"en"}]}}"""
        detectResponseBody = """{"data":{"detections":[[{"language":"fr","confidence":0.95}]]}}"""
        lastReceivedBody = ""
        lastReceivedPath = ""
        lastReceivedQuery = ""
        dynamicBatch = false
        tagRejectionTest = false
        rateLimitAttempts.set(0)
        retryAfterHeader = null
        TranslationCache.resetCircuitBreaker()

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
        config.googleApiHost.setValue("http://127.0.0.1:$serverPort", false)
        config.googleApiKey.setValue("test_google_key", false)
    }

    test("GooglePlugin metadata and status reporting") {
        val plugin = GooglePlugin()
        plugin.id shouldBe "google"
        plugin.displayName shouldBe "Google Translate (Cloud API)"
        plugin.description shouldContain "Google"

        val status = plugin.getStatus()
        status shouldBe PluginStatus.Ready("Google Translate API (http://127.0.0.1:$serverPort)")

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.googleApiKey.setValue("", false)
        plugin.getStatus() shouldBe PluginStatus.NotConfigured("Google API key is not specified")

        config.googleApiKey.setValue("key123", false)
        config.googleApiHost.setValue("auto", false)
        plugin.getStatus() shouldBe PluginStatus.Ready("Google Translate API (https://translation.googleapis.com)")
    }

    test("host resolution and endpoint normalization with API key query parameter") {
        val plugin = GooglePlugin()

        plugin.resolveHost("auto") shouldBe "https://translation.googleapis.com"
        plugin.resolveHost("") shouldBe "https://translation.googleapis.com"
        plugin.resolveHost("   ") shouldBe "https://translation.googleapis.com"
        plugin.resolveHost("my-proxy.internal") shouldBe "https://my-proxy.internal"
        plugin.resolveHost("http://localhost:8080/") shouldBe "http://localhost:8080"

        val normalEndpoint = plugin.normalizeEndpoint("auto", "/language/translate/v2", "my_api_key")
        normalEndpoint shouldBe "https://translation.googleapis.com/language/translate/v2?key=my_api_key"

        val customHostEndpoint = plugin.normalizeEndpoint("http://localhost:8080", "language/translate/v2", "key_1")
        customHostEndpoint shouldBe "http://localhost:8080/language/translate/v2?key=key_1"

        val existingEndpoint = plugin.normalizeEndpoint(
            "http://localhost:8080/language/translate/v2",
            "/language/translate/v2",
            "key_2",
        )
        existingEndpoint shouldBe "http://localhost:8080/language/translate/v2?key=key_2"

        val emptyKeyEndpoint = plugin.normalizeEndpoint("http://localhost:8080", "/v2", "")
        emptyKeyEndpoint shouldBe "http://localhost:8080/v2"

        val keyInUrlEndpoint = plugin.normalizeEndpoint("http://localhost:8080/v2?key=existing", "/v2", "new_key")
        keyInUrlEndpoint shouldBe "http://localhost:8080/v2?key=existing"
    }

    test("language code normalization for Google API") {
        val plugin = GooglePlugin()

        plugin.normalizeTargetLanguage("en") shouldBe "en"
        plugin.normalizeTargetLanguage("en-US") shouldBe "en"
        plugin.normalizeTargetLanguage("en_GB") shouldBe "en"
        plugin.normalizeTargetLanguage("zh") shouldBe "zh-CN"
        plugin.normalizeTargetLanguage("zh-CN") shouldBe "zh-CN"
        plugin.normalizeTargetLanguage("zh-Hans") shouldBe "zh-CN"
        plugin.normalizeTargetLanguage("zh-TW") shouldBe "zh-TW"
        plugin.normalizeTargetLanguage("zh-Hant") shouldBe "zh-TW"
        plugin.normalizeTargetLanguage("zh_HK") shouldBe "zh-TW"
        plugin.normalizeTargetLanguage("pt") shouldBe "pt"
        plugin.normalizeTargetLanguage("pt-BR") shouldBe "pt"
        plugin.normalizeTargetLanguage("pt-PT") shouldBe "pt"
        plugin.normalizeTargetLanguage("nb") shouldBe "no"
        plugin.normalizeTargetLanguage("nn") shouldBe "no"
        plugin.normalizeTargetLanguage("no") shouldBe "no"
        plugin.normalizeTargetLanguage("he") shouldBe "iw"
        plugin.normalizeTargetLanguage("iw") shouldBe "iw"
        plugin.normalizeTargetLanguage("es-ES") shouldBe "es"
        plugin.normalizeTargetLanguage("fr-FR") shouldBe "fr"

        plugin.normalizeSourceLanguage(null) shouldBe null
        plugin.normalizeSourceLanguage("") shouldBe null
        plugin.normalizeSourceLanguage("auto") shouldBe null
        plugin.normalizeSourceLanguage("unknown") shouldBe null
        plugin.normalizeSourceLanguage("zh-CN") shouldBe "zh-CN"
        plugin.normalizeSourceLanguage("zh-TW") shouldBe "zh-TW"
        plugin.normalizeSourceLanguage("nb") shouldBe "no"
        plugin.normalizeSourceLanguage("he") shouldBe "iw"
        plugin.normalizeSourceLanguage("en-US") shouldBe "en"
        plugin.normalizeSourceLanguage("es-MX") shouldBe "es"
        plugin.normalizeSourceLanguage("fr") shouldBe "fr"
    }

    test("unescapeHtml unescapes standard and numeric HTML entities") {
        val plugin = GooglePlugin()

        plugin.unescapeHtml("No entities here") shouldBe "No entities here"
        plugin.unescapeHtml("Fish &amp; Chips") shouldBe "Fish & Chips"
        plugin.unescapeHtml("&quot;Quoted&quot;") shouldBe "\"Quoted\""
        plugin.unescapeHtml("&apos;Single&apos;") shouldBe "'Single'"
        plugin.unescapeHtml("It&#39;s mine") shouldBe "It's mine"
        plugin.unescapeHtml("&lt;tag&gt;") shouldBe "<tag>"
        plugin.unescapeHtml("Non-breaking&nbsp;space") shouldBe "Non-breaking space"
        plugin.unescapeHtml("Char: &#65;&#66;&#67;") shouldBe "Char: ABC"
        plugin.unescapeHtml("Hex: &#x41;&#x42;&#x43;") shouldBe "Hex: ABC"
    }

    test("translate single text parses response and handles edge cases") {
        val plugin = GooglePlugin()

        plugin.translate("Hello", "en", "es") shouldBe "Hola"
        lastReceivedQuery shouldBe "key=test_google_key"
        lastReceivedBody shouldContain """"q":["Hello"]"""
        lastReceivedBody shouldContain """"target":"es""""
        lastReceivedBody shouldContain """"source":"en""""
        lastReceivedBody shouldContain """"format":"text""""

        plugin.translate("<ut>§a</ut>Hello", "en", "es") shouldBe "Hola"
        lastReceivedBody shouldContain """"format":"html""""

        plugin.translate("   ", "en", "es") shouldBe "   "
        plugin.translate(":)", "en", "es") shouldBe ":)"

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.googleApiKey.setValue("", false)
        plugin.translate("Hello", "en", "es") shouldBe null
    }

    test("translateBatch chunks and translates multiple texts") {
        dynamicBatch = true
        val plugin = GooglePlugin()

        val texts = (1..5).map { "Message $it" }
        val results = plugin.translateBatch(texts, "en", "es")

        results shouldNotBe null
        results?.size shouldBe 5
        results?.get(0) shouldBe "Trans 1"
        results?.get(4) shouldBe "Trans 5"
    }

    test("translateBatch with empty input returns empty list") {
        val plugin = GooglePlugin()
        plugin.translateBatch(emptyList(), "en", "es") shouldBe emptyList()
    }

    test("HTML tag rejection retries chunk as sanitized plain text") {
        tagRejectionTest = true
        val plugin = GooglePlugin()

        val result = plugin.translate("<ut>§e</ut>Text", "en", "es")
        result shouldBe "Hola"
    }

    test("detectLanguage uses Google detect endpoint") {
        val plugin = GooglePlugin()
        val detected = plugin.detectLanguage("Bonjour le monde")

        detected shouldBe "fr"
        lastReceivedPath shouldBe "/language/translate/v2/detect"
        lastReceivedQuery shouldBe "key=test_google_key"

        plugin.detectLanguage("   ") shouldBe null
        plugin.detectLanguage("12345 !@#") shouldBe null
        plugin.detectLanguage("gg wp") shouldBe null

        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.googleApiKey.setValue("", false)
        plugin.detectLanguage("Bonjour le monde") shouldBe null
    }

    test("testConnection validates API key and connectivity") {
        val plugin = GooglePlugin()

        val ok = plugin.testConnection("test_google_key", "http://127.0.0.1:$serverPort", "es")
        ok.isSuccess shouldBe true
        ok.getOrNull() shouldBe "Hola"

        val emptyKey = plugin.testConnection("", "http://127.0.0.1:$serverPort", "es")
        emptyKey.isFailure shouldBe true

        responseCode.set(403)
        responseBody = """{"error":{"code":403,"message":"API key not valid. Please pass a valid API key."}}"""
        val fail = plugin.testConnection("bad_key", "http://127.0.0.1:$serverPort", "es")
        fail.isFailure shouldBe true
        fail.exceptionOrNull()?.message shouldContain "API key not valid"
    }

    test("handleHttpError trips circuit breaker on quota exhaustion and 429") {
        val plugin = GooglePlugin()

        responseCode.set(403)
        responseBody = """{
            "error": {
                "code": 403,
                "message": "Daily Limit Exceeded",
                "errors": [{"reason": "dailyLimitExceeded"}]
            }
        }"""
        plugin.translate("Test", "en", "es") shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true

        TranslationCache.resetCircuitBreaker()
        responseCode.set(429)
        responseBody = """{"error":{"code":429,"message":"Resource Exhausted"}}"""
        plugin.translate("Test", "en", "es") shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true
    }

    test("parseGoogleDetect respects language heuristics") {
        val plugin = GooglePlugin()

        plugin.parseGoogleDetect("es", "Hola amigo como estas") shouldBe "es"
        plugin.parseGoogleDetect("en", "Hello friend") shouldBe "en"
        plugin.parseGoogleDetect("unknown", "Sample") shouldBe null
        plugin.parseGoogleDetect("", "Sample") shouldBe null
        plugin.parseGoogleDetect(null, "Sample") shouldBe null
        plugin.parseGoogleDetect("xx", "Some alien text") shouldBe null

        // Universal slang detected as non-en should be filtered out
        plugin.parseGoogleDetect("fr", "lol") shouldBe null
    }

    test("parseRetryAfterMs parses seconds and date format") {
        val plugin = GooglePlugin()

        plugin.parseRetryAfterMs("120") shouldBe 120_000L
        plugin.parseRetryAfterMs(null) shouldBe null
        plugin.parseRetryAfterMs("") shouldBe null
        plugin.parseRetryAfterMs("invalid") shouldBe null

        val future = java.time.ZonedDateTime.now().plusSeconds(10)
        val formatted = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(future)
        val ms = plugin.parseRetryAfterMs(formatted)
        ms shouldNotBe null
    }

    test("isLocalHost detects localhost and 127.0.0.1") {
        val plugin = GooglePlugin()

        plugin.isLocalHost("http://localhost:8080") shouldBe true
        plugin.isLocalHost("http://127.0.0.1:5000") shouldBe true
        plugin.isLocalHost("https://translation.googleapis.com") shouldBe false
    }

    test("getPacingInterval returns zero for localhost and configured value for remote") {
        val plugin = GooglePlugin()

        plugin.getPacingInterval("http://localhost:8080") shouldBe 0L
        plugin.getPacingInterval("https://translation.googleapis.com") shouldBe
            StellarLangConfig.DEFAULT_GOOGLE_REQUEST_INTERVAL_MS.toLong()

        plugin.testPacingIntervalMs = 50L
        plugin.getPacingInterval("https://translation.googleapis.com") shouldBe 50L
    }

    test("sendGoogleRequestWithRateLimit retries on 429 and succeeds") {
        val plugin = GooglePlugin()
        plugin.testBackoffMs = 5L
        rateLimitAttempts.set(1)
        retryAfterHeader = "0"

        val result = plugin.translate("Hello Retry", "en", "es")
        result shouldBe "Hola"
    }

    test("sendGoogleRequestWithRateLimit exhausts retries on persistent 429") {
        val plugin = GooglePlugin()
        plugin.testBackoffMs = 5L
        rateLimitAttempts.set(5)

        val result = plugin.translate("Hello Exhausted", "en", "es")
        result shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true
    }

    test("translate skips slang when target is English") {
        val plugin = GooglePlugin()
        plugin.translate("lol", null, "en") shouldBe "lol"
        plugin.translate("bruh", null, "en-US") shouldBe "bruh"
    }

    test("translate handles malformed or missing JSON structures") {
        val plugin = GooglePlugin()
        responseBody = "{}"
        plugin.translate("Test", "en", "es") shouldBe null

        responseBody = """{"data":{}}"""
        plugin.translate("Test", "en", "es") shouldBe null

        responseBody = """{"data":{"translations":[]}}"""
        plugin.translate("Test", "en", "es") shouldBe null
    }

    test("detectLanguage handles empty detections array or internal errors") {
        val plugin = GooglePlugin()

        detectResponseBody = """{"data":{"detections":[]}}"""
        plugin.detectLanguage("Unrecognised line") shouldBe null

        detectResponseBody = """{"data":{"detections":[[]]}}"""
        plugin.detectLanguage("Unrecognised line 2") shouldBe null

        responseCode.set(500)
        detectResponseBody = """{"error":{"code":500,"message":"Server error"}}"""
        plugin.detectLanguage("Unrecognised line 3") shouldBe null
    }

    test("detectLanguage returns en immediately for quick detect") {
        val plugin = GooglePlugin()
        plugin.detectLanguage("The quick brown fox jumps over the lazy dog") shouldBe "en"
    }

    test("testConnection handles default target, empty translations, and plain error") {
        val plugin = GooglePlugin()

        responseBody = """{"data":{"translations":[]}}"""
        val emptyTrans = plugin.testConnection("test_key", "http://127.0.0.1:$serverPort", "auto")
        emptyTrans.isSuccess shouldBe true
        emptyTrans.getOrNull() shouldBe "OK (HTTP 200)"

        responseCode.set(500)
        responseBody = "Plain text internal error"
        val plainErr = plugin.testConnection("test_key", "http://127.0.0.1:$serverPort", "es")
        plainErr.isFailure shouldBe true
        plainErr.exceptionOrNull()?.message shouldContain "Plain text internal error"
    }

    test("parseGoogleDetect additional heuristics and edge cases") {
        val plugin = GooglePlugin()

        plugin.parseGoogleDetect("de", "sooooooo goooood") shouldBe null
        plugin.parseGoogleDetect("pl", "hi there") shouldBe null
        plugin.parseGoogleDetect("fr", "ça va très bien") shouldBe "fr"
        plugin.parseGoogleDetect("   ", "Text") shouldBe null
    }

    test("unescapeHtml edge cases") {
        val plugin = GooglePlugin()

        plugin.unescapeHtml("&#;") shouldBe "&#;"
        plugin.unescapeHtml("&#x;") shouldBe "&#x;"
        plugin.unescapeHtml("&#99999999999999999999999;") shouldBe "&#99999999999999999999999;"
        plugin.unescapeHtml("&#xGG;") shouldBe "&#xGG;"
    }

    test("testConnection with default arguments") {
        val plugin = GooglePlugin()
        val res = plugin.testConnection("test_key")
        res.isFailure shouldBe true
    }

    test("network exceptions in translate and detectLanguage are handled gracefully") {
        val plugin = GooglePlugin()
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        val origHost = config.googleApiHost.value()
        try {
            config.googleApiHost.setValue("http://127.0.0.1:1", false)

            val trans = plugin.translate("Hello Fail", "en", "es")
            trans shouldBe null

            val detect = plugin.detectLanguage("Bonjour tout le monde de test")
            detect shouldBe null
        } finally {
            config.googleApiHost.setValue(origHost, false)
        }
    }

    test("HTTP 403 with quota in error message trips circuit breaker") {
        val plugin = GooglePlugin()
        responseCode.set(403)
        responseBody = """{"error":{"code":403,"message":"Quota exceeded for project"}}"""
        plugin.translate("Test Quota", "en", "es") shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true
    }

    test("GooglePlugin properties and endpoint query without key") {
        val plugin = GooglePlugin()
        plugin.lastRequestTime = 999L
        plugin.lastRequestTime shouldBe 999L

        val ep = plugin.normalizeEndpoint("http://localhost:8080/v2?param=1", "/v2", "")
        ep shouldBe "http://localhost:8080/v2?param=1"
    }

    test("executeDetect directly handles slang and quick english") {
        val plugin = GooglePlugin()
        plugin.executeDetect("lol", "auto", "test_key") shouldBe null
        plugin.executeDetect("The quick brown fox", "auto", "test_key") shouldBe "en"
    }

    test("unescapeHtml handles entities beyond unicode code points") {
        val plugin = GooglePlugin()
        plugin.unescapeHtml("&#x120000;") shouldBe "&#x120000;"
        plugin.unescapeHtml("&#1200000;") shouldBe "&#1200000;"
    }

    test("translate handles missing fields in item translation gracefully") {
        val plugin = GooglePlugin()
        responseBody = """{"data":{"translations":[{}]}}"""
        val result = plugin.translate("Original text", "en", "es")
        result shouldBe "Original text"
    }

    test("sleepQuietly handles thread interruption gracefully") {
        val plugin = GooglePlugin()
        plugin.lastRequestTime = System.currentTimeMillis()
        plugin.testPacingIntervalMs = 50L

        Thread.currentThread().interrupt()
        try {
            plugin.translate("Interrupt test", "en", "es")
        } finally {
            Thread.interrupted()
        }
    }
})
