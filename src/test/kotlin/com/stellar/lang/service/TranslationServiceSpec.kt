@file:Suppress("LargeClass")

package com.stellar.lang.service

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TranslationServiceSpec : FunSpec({
    lateinit var server: HttpServer
    var serverPort: Int = 0
    val responseCode = AtomicInteger(200)
    val requestCount = AtomicInteger(0)
    var responseBody: String = """{"translatedText": "Hola", "detectedLanguage": "en"}"""

    beforeSpec {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port
        server.createContext("/translate") { exchange ->
            requestCount.incrementAndGet()
            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseCode.get(), bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.createContext("/v2/translate") { exchange ->
            requestCount.incrementAndGet()
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
        TranslationService.clearCache()
        responseCode.set(200)
        requestCount.set(0)
        responseBody = """{"translatedText": "Hola", "detectedLanguage": "en"}"""
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.apiHost.setValue("http://127.0.0.1:$serverPort", false)
        config.apiKey.setValue("test_api_key", false)
        config.targetLanguage.setValue("es", false)
    }

    test("TranslationResult equality and copy") {
        val r1 = TranslationResult("Hello", "Hola", "en", "es", false)
        val r2 = TranslationResult("Hello", "Hola", "en", "es", false)
        r1 shouldBe r2
        r1.hashCode() shouldBe r2.hashCode()
        r1.toString() shouldNotBe ""
        r1.component1() shouldBe "Hello"
        r1.component2() shouldBe "Hola"
        r1.component3() shouldBe "en"
        r1.component4() shouldBe "es"
        r1.component5() shouldBe false

        val copy = r1.copy(translatedText = "Bonjour", targetLanguage = "fr")
        copy.translatedText shouldBe "Bonjour"
        copy.targetLanguage shouldBe "fr"
    }

    test("parseSingleResponse detects foreign language correctly") {
        val json = """
            {
                "translatedText": "Hola Mundo",
                "detectedLanguage": {
                    "confidence": 95.0,
                    "language": "en"
                }
            }
        """.trimIndent()

        val result = TranslationService.parseSingleResponse(json, "Hello World", "es")
        result shouldNotBe null
        result?.translatedText shouldBe "Hola Mundo"
        result?.detectedLanguage shouldBe "en"
        result?.targetLanguage shouldBe "es"
        result?.isSameLanguage shouldBe false
    }

    test("parseSingleResponse identifies matching target language and skips translation flag") {
        val json = """
            {
                "translatedText": "Hello World",
                "detectedLanguage": {
                    "confidence": 99.0,
                    "language": "en"
                }
            }
        """.trimIndent()

        val result = TranslationService.parseSingleResponse(json, "Hello World", "en")
        result shouldNotBe null
        result?.isSameLanguage shouldBe true
        result?.detectedLanguage shouldBe "en"
    }

    test("parseSingleResponse handles primitive string detectedLanguage") {
        val json = """
            {
                "translatedText": "Bonjour",
                "detectedLanguage": "fr"
            }
        """.trimIndent()

        val result = TranslationService.parseSingleResponse(json, "Hello", "en")
        result shouldNotBe null
        result?.translatedText shouldBe "Bonjour"
        result?.detectedLanguage shouldBe "fr"
        result?.isSameLanguage shouldBe false
    }

    test("parseSingleResponse handles missing detectedLanguage and invalid json") {
        val jsonWithoutLang = """{"translatedText": "Guten Tag"}"""
        val res = TranslationService.parseSingleResponse(jsonWithoutLang, "Good Day", "de")
        res shouldNotBe null
        res?.detectedLanguage shouldBe "unknown"

        val jsonEmptyObj = """{"translatedText": "Guten Tag", "detectedLanguage": {}}"""
        val res2 = TranslationService.parseSingleResponse(jsonEmptyObj, "Good Day", "de")
        res2 shouldNotBe null
        res2?.detectedLanguage shouldBe "unknown"

        TranslationService.parseSingleResponse("invalid json", "Test", "en") shouldBe null
        TranslationService.parseSingleResponse("{}", "Test", "en") shouldBe null
    }

    test("translateAsync returns null for empty or blank text") {
        val latch = CountDownLatch(1)
        var callbackResult: TranslationResult? = TranslationResult("dummy", "dummy", "d", "d", false)
        TranslationService.translateAsync("   ") { res ->
            callbackResult = res
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        callbackResult shouldBe null
    }

    test("translateAsync returns null when mod is disabled") {
        val config = TranslationService.getConfig()
        config.enabled.setValue(false, false)

        val latch = CountDownLatch(1)
        var callbackResult: TranslationResult? = TranslationResult("dummy", "dummy", "d", "d", false)
        TranslationService.translateAsync("Hello") { res ->
            callbackResult = res
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        callbackResult shouldBe null
    }

    test("translateAsync performs successful HTTP request and caches result") {
        val latch = CountDownLatch(1)
        var callbackResult: TranslationResult? = null
        TranslationService.translateAsync("Hello") { res ->
            callbackResult = res
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        callbackResult shouldNotBe null
        callbackResult?.translatedText shouldBe "Hola"

        // Second call should return cached without hitting server
        responseCode.set(500)
        val cacheHit = TranslationService.getCached("Hello", "es")
        cacheHit shouldNotBe null
        cacheHit?.translatedText shouldBe "Hola"

        val latch2 = CountDownLatch(1)
        var secondResult: TranslationResult? = null
        TranslationService.translateAsync("Hello") { res ->
            secondResult = res
            latch2.countDown()
        }
        latch2.await(2, TimeUnit.SECONDS) shouldBe true
        secondResult shouldBe cacheHit
    }

    test("translateAsync throttles on server error") {
        responseCode.set(500)
        responseBody = "Internal Server Error"

        val latch1 = CountDownLatch(1)
        var res1: TranslationResult? = null
        TranslationService.translateAsync("FailMe") { r ->
            res1 = r
            latch1.countDown()
        }
        latch1.await(3, TimeUnit.SECONDS) shouldBe true
        res1 shouldBe null

        // Immediate subsequent call is throttled
        val latch2 = CountDownLatch(1)
        var res2: TranslationResult? = TranslationResult("d", "d", "d", "d", false)
        TranslationService.translateAsync("FailMe") { r ->
            res2 = r
            latch2.countDown()
        }
        latch2.await(2, TimeUnit.SECONDS) shouldBe true
        res2 shouldBe null
    }

    test("translateBatchSync handles empty list and disabled config") {
        TranslationService.translateBatchSync(emptyList())!!.shouldBeEmpty()
        TranslationService.translateBatchSync(listOf("   ", ""))!!.shouldBeEmpty()

        val config = TranslationService.getConfig()
        config.enabled.setValue(false, false)
        TranslationService.translateBatchSync(listOf("Hello")) shouldBe null
    }

    test("translateBatchSync and translateBatchAsync execute batch translation successfully") {
        responseBody = """
            {
                "translatedText": ["Uno", "Dos"],
                "detectedLanguage": "en"
            }
        """.trimIndent()

        val results = TranslationService.translateBatchSync(listOf("One", "Two"))
        results shouldNotBe null
        results!!.size shouldBe 2
        results[0].translatedText shouldBe "Uno"
        results[1].translatedText shouldBe "Dos"

        // Test async batch
        val latch = CountDownLatch(1)
        var asyncResults: List<TranslationResult>? = null
        TranslationService.translateBatchAsync(listOf("One", "Two")) { r ->
            asyncResults = r
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        asyncResults shouldNotBe null
        asyncResults!!.size shouldBe 2
    }

    test("translateBatchSync returns null on server error") {
        responseCode.set(400)
        responseBody = """{"error": "Invalid request"}"""
        val results = TranslationService.translateBatchSync(listOf("Test"))
        results shouldBe null
    }

    test("translateBatchSync handles connection exception gracefully") {
        val config = TranslationService.getConfig()
        config.apiHost.setValue("http://127.0.0.1:1", false)
        val results = TranslationService.translateBatchSync(listOf("Test"))
        results shouldBe null
    }

    test("translateSync handles connection exception gracefully") {
        val config = TranslationService.getConfig()
        config.apiHost.setValue("http://127.0.0.1:1", false)
        val latch = CountDownLatch(1)
        var result: TranslationResult? = TranslationResult("dummy", "dummy", "en", "es", false)
        TranslationService.translateAsync("Test") { res ->
            result = res
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        result shouldBe null
    }

    test("clearCache empties all cached results") {
        TranslationService.translateBatchSync(listOf("CachedText"))
        TranslationService.clearCache()
        TranslationService.getCached("CachedText", "es") shouldBe null
    }

    test("requests work with blank API key") {
        val config = TranslationService.getConfig()
        config.apiKey.setValue("", false)
        responseBody = """{"translatedText": ["SinApiKey"], "detectedLanguage": "en"}"""
        val results = TranslationService.translateBatchSync(listOf("NoApiKey"))
        results shouldNotBe null
        results!!.size shouldBe 1
    }

    test("throttle expiry clears failed attempt") {
        val failedField = TranslationCache::class.java.getDeclaredField("failedAttempts")
        failedField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val failedMap = failedField.get(TranslationCache) as java.util.concurrent.ConcurrentHashMap<String, Long>

        val testKey = "es::ExpiredThrottle"
        // Set failed timestamp to 20 seconds in the past (exceeds initial 15_000ms cooldown)
        failedMap[testKey] = System.currentTimeMillis() - 20_000L

        val latch = CountDownLatch(1)
        var res: TranslationResult? = null
        TranslationService.translateAsync("ExpiredThrottle") { r ->
            res = r
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        res shouldNotBe null
        failedMap.containsKey(testKey) shouldBe false
    }

    test("single translateAsync invokes callback with single result") {
        val latch = CountDownLatch(1)
        var singleResult: TranslationResult? = null
        TranslationService.translateAsync("Single text") { res ->
            singleResult = res
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        singleResult shouldNotBe null
        singleResult!!.originalText shouldBe "Single text"
    }

    test("parseSingleResponse handles primitive string detectedLanguage and unknown formats") {
        val primitiveJson = """{"translatedText": "Hola", "detectedLanguage": "fr"}"""
        val method = TranslationService::class.java.getDeclaredMethod(
            "parseSingleResponse",
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val res1 = method.invoke(TranslationService, primitiveJson, "Bonjour", "es") as TranslationResult
        res1.detectedLanguage shouldBe "fr"

        val arrayJson = """{"translatedText": "Hola", "detectedLanguage": [1, 2, 3]}"""
        val res2 = method.invoke(TranslationService, arrayJson, "Bonjour", "es") as TranslationResult
        res2.detectedLanguage shouldBe "unknown"
    }

    test("executeTranslation catches exceptions and returns null on connection failure") {
        val method = TranslationService::class.java.getDeclaredMethod(
            "executeTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val res = method.invoke(TranslationService, "Hello", "http://127.0.0.1:1", "", "es")
        res shouldBe null
    }

    test("translateBatchSync partitions cached and uncached texts") {
        val cached1 = TranslationResult("Item1", "Articulo1", "en", "es", false)
        TranslationService.putCache(cached1)

        requestCount.set(0)
        responseBody = """{"translatedText": ["Articulo2"], "detectedLanguage": "en"}"""

        val result = TranslationService.translateBatchSync(listOf("Item1", "Item2"))
        result shouldNotBe null
        result!!.size shouldBe 2
        result[0].translatedText shouldBe "Articulo1"
        result[1].translatedText shouldBe "Articulo2"
        requestCount.get() shouldBe 1
    }

    test("HTTP 429 trips circuit breaker and subsequent requests fail fast") {
        responseCode.set(429)
        responseBody = """{"error": "Too Many Requests"}"""

        val latch1 = CountDownLatch(1)
        TranslationService.translateAsync("TriggerRateLimit") {
            latch1.countDown()
        }
        latch1.await(3, TimeUnit.SECONDS) shouldBe true
        TranslationCache.isCircuitBreakerOpen() shouldBe true

        requestCount.set(0)
        val latch2 = CountDownLatch(1)
        var blockedRes: TranslationResult? = TranslationResult("dummy", "dummy", "en", "es", false)
        TranslationService.translateAsync("BlockedRequest") { r ->
            blockedRes = r
            latch2.countDown()
        }
        latch2.await(1, TimeUnit.SECONDS) shouldBe true
        blockedRes shouldBe null
        requestCount.get() shouldBe 0
    }

    test("translateAsync coalesces concurrent requests into single HTTP fetch") {
        requestCount.set(0)
        responseBody = """{"translatedText": "Coalesced Translation", "detectedLanguage": "en"}"""

        val latch = CountDownLatch(5)
        val results = java.util.concurrent.CopyOnWriteArrayList<TranslationResult>()

        repeat(5) {
            TranslationService.translateAsync("SameTextToTranslate") { res ->
                if (res != null) results.add(res)
                latch.countDown()
            }
        }

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        results.size shouldBe 5
        results.forEach { it.translatedText shouldBe "Coalesced Translation" }
        requestCount.get() shouldBe 1
    }

    test("isThrottled and cacheKey forward to TranslationCache") {
        TranslationService.cacheKey("Hello", "es") shouldBe "es::Hello"
        TranslationService.isThrottled("es::TestKey") shouldBe false
        TranslationCache.markFailed("es::TestKey")
        TranslationService.isThrottled("es::TestKey") shouldBe true
    }

    test("translateBatchSync returns null when circuit breaker is open") {
        TranslationCache.tripCircuitBreaker(10_000L)
        val res = TranslationService.translateBatchSync(listOf("CircuitBreakerBatch"))
        res shouldBe null
        TranslationCache.resetCircuitBreaker()
    }

    test("translateBatchSync trips circuit breaker on HTTP 429") {
        responseCode.set(429)
        responseBody = """{"error": "Too Many Requests"}"""
        val res = TranslationService.translateBatchSync(listOf("BatchHTTP429"))
        res shouldBe null
        TranslationCache.isCircuitBreakerOpen() shouldBe true
        TranslationCache.resetCircuitBreaker()
    }

    test("normalizeEndpoint handles urls already ending with /translate") {
        val method = TranslationService::class.java.getDeclaredMethod("normalizeEndpoint", String::class.java)
        method.isAccessible = true
        val res = method.invoke(TranslationService, "https://api.example.com/translate/")
        res shouldBe "https://api.example.com/translate"
    }

    test("normalizeEndpoint prepends http when scheme is missing") {
        val method = TranslationService::class.java.getDeclaredMethod("normalizeEndpoint", String::class.java)
        method.isAccessible = true
        val res = method.invoke(TranslationService, "localhost:5000")
        res shouldBe "http://localhost:5000/translate"
    }

    test("parseBatchResponse handles non-array translatedText gracefully") {
        val method = TranslationService::class.java.getDeclaredMethod(
            "parseBatchResponse",
            String::class.java,
            List::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val badJson = """{"translatedText": "not an array", "detectedLanguage": "en"}"""
        val res = method.invoke(TranslationService, badJson, listOf("Item1"), "es")
        res shouldBe null
    }

    test("parseBatchResponse parses array of detectedLanguages accurately") {
        val method = TranslationService::class.java.getDeclaredMethod(
            "parseBatchResponse",
            String::class.java,
            List::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val batchJson = """
            {
              "translatedText": ["Hello", "World"],
              "detectedLanguage": [
                {"confidence": 95.0, "language": "fr"},
                {"confidence": 90.0, "language": "de"}
              ]
            }
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val res = method.invoke(
            TranslationService,
            batchJson,
            listOf("Bonjour", "Welt"),
            "en",
        ) as List<TranslationResult>

        res.size shouldBe 2
        res[0].detectedLanguage shouldBe "fr"
        res[0].translatedText shouldBe "Hello"
        res[1].detectedLanguage shouldBe "de"
        res[1].translatedText shouldBe "World"
    }

    test("testConnection reports success when API returns valid translation") {
        responseCode.set(200)
        responseBody = """{"translatedText": "Bonjour", "detectedLanguage": "en"}"""
        val latch = CountDownLatch(1)
        var outcome: Result<String>? = null
        TranslationService.testConnection("http://127.0.0.1:$serverPort", "key", "fr") { res ->
            outcome = res
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        outcome?.isSuccess shouldBe true
        outcome?.getOrNull() shouldBe "Bonjour"
    }

    test("testConnection reports failure when server returns HTTP error") {
        responseCode.set(500)
        responseBody = """{"error": "Internal Server Error"}"""
        val latch = CountDownLatch(1)
        var outcome: Result<String>? = null
        TranslationService.testConnection("http://127.0.0.1:$serverPort", "key", "fr") { res ->
            outcome = res
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        outcome?.isFailure shouldBe true
    }

    test("testDeepl reports success and failure properly") {
        responseCode.set(200)
        responseBody = """{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""
        val latch = CountDownLatch(1)
        var outcome: Result<String>? = null
        TranslationService.testDeepl("test_key", "http://127.0.0.1:$serverPort", "de") { res ->
            outcome = res
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        outcome?.isSuccess shouldBe true
        outcome?.getOrNull() shouldBe "Hallo"

        val latchFail = CountDownLatch(1)
        var failOutcome: Result<String>? = null
        TranslationService.testDeepl("", "http://127.0.0.1:$serverPort", "de") { res ->
            failOutcome = res
            latchFail.countDown()
        }
        latchFail.await(5, TimeUnit.SECONDS) shouldBe true
        failOutcome?.isFailure shouldBe true
    }

    test("getCandidateTranslators filters DeepL candidate based on key and status") {
        val config = TranslationService.getConfig()
        val origTrans = config.translationPlugin.value()
        val origKey = config.deeplApiKey.value()
        try {
            val deeplCandidate = com.stellar.lang.plugin.PluginRegistry.getTranslator("deepl")!!
            config.translationPlugin.setValue("onnx", false)
            config.deeplApiKey.setValue("", false)
            com.stellar.lang.plugin.PluginRegistry.setExplicitFallbackTranslator(deeplCandidate)

            val noKeyCandidates = TranslationService.getCandidateTranslators("de")
            noKeyCandidates.contains(deeplCandidate) shouldBe false

            config.deeplApiKey.setValue("key:fx", false)
            val withKeyCandidates = TranslationService.getCandidateTranslators("de")
            withKeyCandidates.contains(deeplCandidate) shouldBe true
        } finally {
            config.translationPlugin.setValue(origTrans, false)
            config.deeplApiKey.setValue(origKey, false)
            com.stellar.lang.plugin.PluginRegistry.setExplicitFallbackTranslator(null)
        }
    }

    test("testOnnx reports success when test models are ready") {
        val testModelsDir = java.io.File("src/test/resources/test_models")
        val config = TranslationService.getConfig()
        config.onnxModelDir.setValue(testModelsDir.absolutePath, false)
        com.stellar.lang.plugin.onnx.OnnxInferenceEngine.resetSessions()

        val latch = CountDownLatch(1)
        var outcome: Result<String>? = null
        TranslationService.testOnnx("es") { res ->
            outcome = res
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        outcome?.isSuccess shouldBe true
        val info = outcome?.getOrNull() ?: ""
        info shouldContain "Det: 'Bonjour' ->"
        info shouldContain "Trans: 'Hello' ->"
    }

    test("testOnnx reports failure when models directory is missing models") {
        val config = TranslationService.getConfig()
        config.onnxModelDir.setValue("build/non_existent_models_dir", false)
        com.stellar.lang.plugin.onnx.OnnxInferenceEngine.resetSessions()

        val latch = CountDownLatch(1)
        var outcome: Result<String>? = null
        TranslationService.testOnnx { res ->
            outcome = res
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        outcome?.isFailure shouldBe true
    }

    test("normalizeLanguageCode maps various Minecraft language codes properly") {
        TranslationService.normalizeLanguageCode("en_us") shouldBe "en"
        TranslationService.normalizeLanguageCode("en_gb") shouldBe "en"
        TranslationService.normalizeLanguageCode("fr_fr") shouldBe "fr"
        TranslationService.normalizeLanguageCode("fr_ca") shouldBe "fr"
        TranslationService.normalizeLanguageCode("de_de") shouldBe "de"
        TranslationService.normalizeLanguageCode("es_es") shouldBe "es"
        TranslationService.normalizeLanguageCode("es_mx") shouldBe "es"
        TranslationService.normalizeLanguageCode("zh_cn") shouldBe "zh"
        TranslationService.normalizeLanguageCode("zh_tw") shouldBe "zh"
        TranslationService.normalizeLanguageCode("ja_jp") shouldBe "ja"
        TranslationService.normalizeLanguageCode("ko_kr") shouldBe "ko"
        TranslationService.normalizeLanguageCode("ru_ru") shouldBe "ru"
        TranslationService.normalizeLanguageCode("pt_br") shouldBe "pt"
        TranslationService.normalizeLanguageCode("pt_pt") shouldBe "pt"
        TranslationService.normalizeLanguageCode("lol_us") shouldBe "en"
        TranslationService.normalizeLanguageCode("en-US") shouldBe "en"
        TranslationService.normalizeLanguageCode("DE") shouldBe "de"
    }

    test("normalizeLanguageCode fallbacks on null or blank or invalid codes") {
        val defaultLang = TranslationService.normalizeLanguageCode(null)
        (defaultLang.length in 2..3) shouldBe true

        val blankLang = TranslationService.normalizeLanguageCode("   ")
        (blankLang.length in 2..3) shouldBe true

        TranslationService.normalizeLanguageCode("toolongcode") shouldBe "en"
        TranslationService.normalizeLanguageCode("x") shouldBe "en"
    }

    test("getTargetLanguage infers from languageProvider when targetLanguage is auto or empty") {
        val config = TranslationService.getConfig()
        config.targetLanguage.setValue("auto", false)

        TranslationService.languageProvider = { "fr_fr" }
        TranslationService.getTargetLanguage() shouldBe "fr"

        TranslationService.languageProvider = { "de_de" }
        TranslationService.getTargetLanguage() shouldBe "de"

        TranslationService.languageProvider = { "es_es" }
        TranslationService.getTargetLanguage() shouldBe "es"

        config.targetLanguage.setValue("", false)
        TranslationService.languageProvider = { "ja_jp" }
        TranslationService.getTargetLanguage() shouldBe "ja"

        // Explicit override ignores game language
        config.targetLanguage.setValue("ru", false)
        TranslationService.getTargetLanguage() shouldBe "ru"

        // Cleanup
        TranslationService.languageProvider = null
    }

    test("inferTargetLanguage falls back when Minecraft is not running in headless test") {
        TranslationService.languageProvider = null
        val inferred = TranslationService.inferTargetLanguage()
        (inferred.length in 2..3) shouldBe true
    }

    test("TranslationService delegates to custom plugins and handles same-language") {
        val customDetector = object : com.stellar.lang.plugin.LanguageDetectorPlugin {
            override val id = "mock_det"
            override val displayName = "Mock Det"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready()
            override suspend fun detectLanguage(text: String): String = if (text.contains("Same")) "es" else "fr"
        }

        val customTranslator = object : com.stellar.lang.plugin.TranslationPlugin {
            override val id = "mock_trans"
            override val displayName = "Mock Trans"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready()
            override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
                return if (text == "ErrorMe") null else "Mocked-$text"
            }
        }

        com.stellar.lang.plugin.PluginRegistry.registerDetector(customDetector)
        com.stellar.lang.plugin.PluginRegistry.registerTranslator(customTranslator)

        val config = TranslationService.getConfig()
        config.detectionPlugin.setValue("mock_det", false)
        config.translationPlugin.setValue("mock_trans", false)
        config.targetLanguage.setValue("es", false)

        // 1. Same language test
        val sameLatch = CountDownLatch(1)
        var sameResult: TranslationResult? = null
        TranslationService.translateAsync("SameText") { r ->
            sameResult = r
            sameLatch.countDown()
        }
        sameLatch.await(3, TimeUnit.SECONDS) shouldBe true
        sameResult shouldNotBe null
        sameResult?.isSameLanguage shouldBe true
        sameResult?.translatedText shouldBe "SameText"

        // 2. Translated test
        val transLatch = CountDownLatch(1)
        var transResult: TranslationResult? = null
        TranslationService.translateAsync("DifferentText") { r ->
            transResult = r
            transLatch.countDown()
        }
        transLatch.await(3, TimeUnit.SECONDS) shouldBe true
        transResult shouldNotBe null
        transResult?.isSameLanguage shouldBe false
        transResult?.translatedText shouldBe "Mocked-DifferentText"

        // 3. Translation error returns null
        val errLatch = CountDownLatch(1)
        var errResult: TranslationResult? = TranslationResult("dummy", "dummy", "en", "es", false)
        TranslationService.translateAsync("ErrorMe") { r ->
            errResult = r
            errLatch.countDown()
        }
        errLatch.await(3, TimeUnit.SECONDS) shouldBe true
        errResult shouldBe null

        // 4. Batch translation with same language
        val batchSame = TranslationService.translateBatchSync(listOf("Same1", "Same2"))
        batchSame shouldNotBe null
        batchSame!!.all { it.isSameLanguage } shouldBe true

        // 5. Batch translation with foreign language
        val batchTrans = TranslationService.translateBatchSync(listOf("ItemA", "ItemB"))
        batchTrans shouldNotBe null
        batchTrans!!.size shouldBe 2
        batchTrans[0].translatedText shouldBe "Mocked-ItemA"
        batchTrans[1].translatedText shouldBe "Mocked-ItemB"

        // 6. Detector returns null or throws
        val throwingDetector = object : com.stellar.lang.plugin.LanguageDetectorPlugin {
            override val id = "throw_det"
            override val displayName = "Throwing"
            override val description = "Throw"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready()
            override suspend fun detectLanguage(text: String): String? {
                if (text == "Throw") error("Det failed")
                return null
            }
        }
        com.stellar.lang.plugin.PluginRegistry.registerDetector(throwingDetector)
        config.detectionPlugin.setValue("throw_det", false)

        val nullLatch = CountDownLatch(1)
        var nullDetResult: TranslationResult? = null
        TranslationService.translateAsync("NullDet") { r ->
            nullDetResult = r
            nullLatch.countDown()
        }
        nullLatch.await(3, TimeUnit.SECONDS)
        nullDetResult shouldNotBe null
        nullDetResult?.detectedLanguage shouldBe "unknown"

        val throwLatch = CountDownLatch(1)
        var throwDetResult: TranslationResult? = TranslationResult("d", "d", "d", "d", false)
        TranslationService.translateAsync("Throw") { r ->
            throwDetResult = r
            throwLatch.countDown()
        }
        throwLatch.await(3, TimeUnit.SECONDS)
        throwDetResult shouldBe null

        // 7. Batch translation when translator returns null or throws
        val failingBatchTranslator = object : com.stellar.lang.plugin.TranslationPlugin {
            override val id = "fail_batch"
            override val displayName = "Fail Batch"
            override val description = "Fail"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready()
            override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? = null
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: String?,
                targetLang: String,
            ): List<String>? {
                if (texts.contains("ThrowBatch")) error("Batch exploded")
                return null
            }
        }
        com.stellar.lang.plugin.PluginRegistry.registerTranslator(failingBatchTranslator)
        config.translationPlugin.setValue("fail_batch", false)

        TranslationService.translateBatchSync(listOf("Fail1", "Fail2")) shouldBe null
        TranslationService.translateBatchSync(listOf("ThrowBatch")) shouldBe null
    }

    test("testConnection with default arguments and normalizeLanguageCode branches") {
        TranslationService.normalizeLanguageCode("lol_us") shouldBe "en"
        TranslationService.normalizeLanguageCode("toolongcode") shouldBe "en"
        TranslationService.normalizeLanguageCode("x") shouldBe "en"
        TranslationService.normalizeLanguageCode("   ") shouldBe "en"
        TranslationService.normalizeLanguageCode(null) shouldBe "en"

        TranslationService.languageProvider shouldBe null

        responseCode.set(200)
        val defaultLatch = CountDownLatch(1)
        var connectionSuccess = false
        TranslationService.testConnection(
            host = "http://127.0.0.1:$serverPort",
            apiKey = "",
        ) { res ->
            connectionSuccess = res.isSuccess
            defaultLatch.countDown()
        }
        defaultLatch.await(3, TimeUnit.SECONDS) shouldBe true
        connectionSuccess shouldBe true

        // Test connection failure branch
        responseCode.set(500)
        val failLatch = CountDownLatch(1)
        var connectionFailure = false
        TranslationService.testConnection(
            host = "http://127.0.0.1:$serverPort",
            apiKey = "",
        ) { res ->
            connectionFailure = res.isFailure
            failLatch.countDown()
        }
        failLatch.await(3, TimeUnit.SECONDS) shouldBe true
        connectionFailure shouldBe true
    }

    test("isFailed returns correct status for 1-arg and 2-arg overloads") {
        TranslationService.isFailed("hello_world") shouldBe false
        TranslationService.isFailed("hello_world", "es") shouldBe false

        val key = TranslationService.cacheKey("hello_world", "es")
        TranslationCache.markFailed(key)

        TranslationService.isFailed("hello_world") shouldBe true
        TranslationService.isFailed("hello_world", "es") shouldBe true

        TranslationCache.clear()
        TranslationService.isFailed("hello_world") shouldBe false

        TranslationCache.tripCircuitBreaker()
        TranslationService.isFailed("hello_world") shouldBe true
        TranslationCache.resetCircuitBreaker()
        TranslationService.isFailed("hello_world") shouldBe false
    }

    test("executeTranslation and executeBatchTranslation delegate to custom plugins correctly") {
        val mockTranslator = object : com.stellar.lang.plugin.TranslationPlugin {
            override val id = "custom_mock"
            override val displayName = "Custom Mock"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready("Ready")
            override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String = "mock_$text"
        }

        val mockDetector = object : com.stellar.lang.plugin.LanguageDetectorPlugin {
            override val id = "custom_mock"
            override val displayName = "Custom Mock"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready("Ready")
            override suspend fun detectLanguage(text: String): String = if (text == "same") "es" else "fr"
        }

        com.stellar.lang.plugin.PluginRegistry.registerTranslator(mockTranslator)
        com.stellar.lang.plugin.PluginRegistry.registerDetector(mockDetector)

        val config = TranslationService.getConfig()
        config.translationPlugin.setValue("custom_mock", false)
        config.detectionPlugin.setValue("custom_mock", false)
        config.targetLanguage.setValue("es", false)

        val latch = CountDownLatch(2)
        var singleRes: TranslationResult? = null
        var sameLangRes: TranslationResult? = null

        TranslationService.translateAsync("bonjour") { res ->
            singleRes = res
            latch.countDown()
        }

        TranslationService.translateAsync("same") { res ->
            sameLangRes = res
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        singleRes shouldNotBe null
        singleRes?.translatedText shouldBe "mock_bonjour"
        singleRes?.isSameLanguage shouldBe false

        sameLangRes shouldNotBe null
        sameLangRes?.isSameLanguage shouldBe true

        val batchRes = TranslationService.translateBatchSync(listOf("bonjour", "salut"))
        batchRes shouldNotBe null
        batchRes?.size shouldBe 2
        batchRes?.get(0)?.translatedText shouldBe "mock_bonjour"
        batchRes?.get(1)?.translatedText shouldBe "mock_salut"

        kotlinx.coroutines.runBlocking {
            val directBatch = mockTranslator.translateBatch(listOf("one", "two"), "en", "es")
            directBatch shouldBe listOf("mock_one", "mock_two")
        }

        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
    }

    test("custom mock plugins handle null and exceptions in executeTranslation and executeBatchTranslation") {
        val failingTranslator = object : com.stellar.lang.plugin.TranslationPlugin {
            override val id = "failing_mock"
            override val displayName = "Failing Mock"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready("Ready")
            override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
                if (text == "explode") error("Boom")
                return null
            }
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: String?,
                targetLang: String,
            ): List<String>? {
                if (texts.contains("explode")) error("Boom batch")
                return null
            }
        }

        val mockDetector = object : com.stellar.lang.plugin.LanguageDetectorPlugin {
            override val id = "detector_mock"
            override val displayName = "Detector Mock"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready("Ready")
            override suspend fun detectLanguage(text: String): String {
                if (text == "detector_error") error("Detection error")
                return "fr"
            }
        }

        com.stellar.lang.plugin.PluginRegistry.registerTranslator(failingTranslator)
        com.stellar.lang.plugin.PluginRegistry.registerDetector(mockDetector)

        val config = TranslationService.getConfig()
        config.translationPlugin.setValue("failing_mock", false)
        config.detectionPlugin.setValue("detector_mock", false)
        config.targetLanguage.setValue("es", false)

        val latch = CountDownLatch(3)
        var nullRes: TranslationResult? = null
        var explodeRes: TranslationResult? = null
        var detErrRes: TranslationResult? = null

        TranslationService.translateAsync("return_null") { res ->
            nullRes = res
            latch.countDown()
        }
        TranslationService.translateAsync("explode") { res ->
            explodeRes = res
            latch.countDown()
        }
        TranslationService.translateAsync("detector_error") { res ->
            detErrRes = res
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        nullRes shouldBe null
        explodeRes shouldBe null
        detErrRes shouldBe null

        val batchNull = TranslationService.translateBatchSync(listOf("return_null_batch"))
        batchNull shouldBe null

        val batchExplode = TranslationService.translateBatchSync(listOf("explode"))
        batchExplode shouldBe null

        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
    }

    test("TranslationService falls back to LibreTranslate when onnx translator cannot translate") {
        val config = TranslationService.getConfig()
        config.translationPlugin.setValue("onnx", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.onnxModelDir.setValue("build/no_models", false)

        responseBody = """{"translatedText": "Fallback Success", "detectedLanguage": "fr"}"""

        val latch = CountDownLatch(1)
        var result: TranslationResult? = null

        TranslationService.translateAsync("Bonjour le monde") { res ->
            result = res
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        result shouldNotBe null
        result?.translatedText shouldBe "Fallback Success"

        responseBody = """{"translatedText": ["Fallback Success"], "detectedLanguage": "fr"}"""
        val batchRes = TranslationService.translateBatchSync(listOf("Bonjour"))
        batchRes shouldNotBe null
        batchRes?.firstOrNull()?.translatedText shouldBe "Fallback Success"

        config.translationPlugin.setValue("libretranslate", false)
    }

    test("TranslationService with custom successful plugins handles single and batch") {
        val successTranslator = object : com.stellar.lang.plugin.TranslationPlugin {
            override val id = "success_mock"
            override val displayName = "Success Mock"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready("Ready")
            override suspend fun translate(text: String, sourceLang: String?, targetLang: String): String? {
                return "Mock: $text"
            }
            override suspend fun translateBatch(
                texts: List<String>,
                sourceLang: String?,
                targetLang: String,
            ): List<String> {
                return texts.map { "BatchMock: $it" }
            }
        }

        val successDetector = object : com.stellar.lang.plugin.LanguageDetectorPlugin {
            override val id = "success_detector"
            override val displayName = "Success Detector"
            override val description = "Mock"
            override fun getStatus() = com.stellar.lang.plugin.PluginStatus.Ready("Ready")
            override suspend fun detectLanguage(text: String): String {
                return if (text == "SameLang") "es" else "fr"
            }
        }

        com.stellar.lang.plugin.PluginRegistry.registerTranslator(successTranslator)
        com.stellar.lang.plugin.PluginRegistry.registerDetector(successDetector)

        val config = TranslationService.getConfig()
        config.translationPlugin.setValue("success_mock", false)
        config.detectionPlugin.setValue("success_detector", false)
        config.targetLanguage.setValue("es", false)

        val latch = CountDownLatch(2)
        var singleRes: TranslationResult? = null
        var sameLangRes: TranslationResult? = null

        TranslationService.translateAsync("Hello") { res ->
            singleRes = res
            latch.countDown()
        }
        TranslationService.translateAsync("SameLang") { res ->
            sameLangRes = res
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        singleRes shouldNotBe null
        singleRes?.translatedText shouldBe "Mock: Hello"
        singleRes?.isSameLanguage shouldBe false

        sameLangRes shouldNotBe null
        sameLangRes?.isSameLanguage shouldBe true

        // Batch different language
        val batchRes = TranslationService.translateBatchSync(listOf("A", "B"))
        batchRes shouldNotBe null
        batchRes?.size shouldBe 2
        batchRes?.get(0)?.translatedText shouldBe "BatchMock: A"
        batchRes?.get(0)?.isSameLanguage shouldBe false

        // Batch same language
        val sameBatchRes = TranslationService.translateBatchSync(listOf("SameLang", "SameLang"))
        sameBatchRes shouldNotBe null
        sameBatchRes?.size shouldBe 2
        sameBatchRes?.get(0)?.isSameLanguage shouldBe true

        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
    }

    test("parseSingleResponse handles json array translatedText or invalid formats") {
        val arrayJson = """{"translatedText": ["Translated"], "detectedLanguage": "es"}"""
        val res1 = TranslationService.parseSingleResponse(arrayJson, "Original", "en")
        res1 shouldNotBe null
        res1?.translatedText shouldBe "Translated"

        val emptyArrayJson = """{"translatedText": [], "detectedLanguage": "es"}"""
        val res2 = TranslationService.parseSingleResponse(emptyArrayJson, "Original", "en")
        res2 shouldBe null

        val missingJson = """{"other": "value"}"""
        val res3 = TranslationService.parseSingleResponse(missingJson, "Original", "en")
        res3 shouldBe null
    }

    test("TranslationService translates through ONNX model end-to-end") {
        val testModelsDir = java.io.File("src/test/resources/test_models")
        val config = TranslationService.getConfig()
        config.translationPlugin.setValue("onnx", false)
        config.detectionPlugin.setValue("onnx", false)
        config.targetLanguage.setValue("es", false)
        config.onnxModelDir.setValue(testModelsDir.absolutePath, false)
        com.stellar.lang.plugin.onnx.OnnxInferenceEngine.resetSessions()

        val latch = CountDownLatch(1)
        var singleRes: TranslationResult? = null

        TranslationService.translateAsync("hello world") { res ->
            singleRes = res
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        singleRes shouldNotBe null
        singleRes?.translatedText shouldBe "hola mundo"
        singleRes?.isSameLanguage shouldBe false
        singleRes?.targetLanguage shouldBe "es"

        val batchRes = TranslationService.translateBatchSync(listOf("hello", "world"))
        batchRes shouldNotBe null
        batchRes?.size shouldBe 2
        batchRes?.get(0)?.translatedText shouldBe "hola"
        batchRes?.get(0)?.isSameLanguage shouldBe false
        batchRes?.get(1)?.translatedText shouldBe "mundo"
        batchRes?.get(1)?.isSameLanguage shouldBe false

        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        com.stellar.lang.plugin.onnx.OnnxInferenceEngine.resetSessions()
    }

    test("clearAllCaches clears cache, disk, and feature caches") {
        val tempDir = java.nio.file.Files.createTempDirectory("stellar_clear_all_test")
        val cacheFile = tempDir.resolve("test_cache.json")
        TranslationCache.resetForTesting(cacheFile, 100)
        TranslationCache.put(TranslationResult("Hello", "Bonjour", "en", "fr", false))
        TranslationCache.flush()
        java.nio.file.Files.exists(cacheFile) shouldBe true

        TranslationService.clearAllCaches()

        TranslationCache.size() shouldBe 0
        java.nio.file.Files.exists(cacheFile) shouldBe false

        java.nio.file.Files.deleteIfExists(tempDir)
    }

    test("getCandidateTranslators and detectLanguage handle circuit-broken remote providers") {
        val config = TranslationService.getConfig()
        config.translationPlugin.setValue("deepl", false)
        config.detectionPlugin.setValue("deepl", false)
        config.deeplApiKey.setValue("test-key", false)

        TranslationCache.tripCircuitBreaker(60_000L)
        try {
            val candidates = TranslationService.getCandidateTranslators("en")
            candidates.any { it is com.stellar.lang.plugin.deepl.DeepLPlugin } shouldBe false

            val detected = TranslationService.detectLanguage("Something in english")
            detected shouldNotBe null
        } finally {
            TranslationCache.resetCircuitBreaker()
            config.translationPlugin.setValue("libretranslate", false)
            config.detectionPlugin.setValue("libretranslate", false)
        }
    }

    test("DeepL single translation through TranslationService") {
        val config = TranslationService.getConfig()
        val origTrans = config.translationPlugin.value()
        val origHost = config.deeplApiHost.value()
        val origKey = config.deeplApiKey.value()
        val origTarget = config.targetLanguage.value()

        try {
            config.translationPlugin.setValue("deepl", false)
            config.deeplApiHost.setValue("http://127.0.0.1:$serverPort", false)
            config.deeplApiKey.setValue("test-key", false)
            config.targetLanguage.setValue("es", false)

            responseBody = """{"translations":[{"detected_source_language":"EN","text":"Hola"}]}"""
            val res = TranslationService.executeTranslation("Hello", "es")
            res shouldNotBe null
            res?.translatedText shouldBe "Hola"
            res?.detectedLanguage shouldBe "en"
            res?.isSameLanguage shouldBe false

            // Same language response
            responseBody = """{"translations":[{"detected_source_language":"ES","text":"Hola"}]}"""
            val sameRes = TranslationService.executeTranslation("Hola", "es")
            sameRes shouldNotBe null
            sameRes?.isSameLanguage shouldBe true

            // Untranslated / error response
            responseBody = """{"translations":[]}"""
            val failRes = TranslationService.executeTranslation("ErrorTest", "es")
            failRes shouldBe null
        } finally {
            config.translationPlugin.setValue(origTrans, false)
            config.deeplApiHost.setValue(origHost, false)
            config.deeplApiKey.setValue(origKey, false)
            config.targetLanguage.setValue(origTarget, false)
        }
    }

    test("DeepL batch translation through TranslationService") {
        val config = TranslationService.getConfig()
        val origTrans = config.translationPlugin.value()
        val origHost = config.deeplApiHost.value()
        val origKey = config.deeplApiKey.value()
        val origTarget = config.targetLanguage.value()

        try {
            config.translationPlugin.setValue("deepl", false)
            config.deeplApiHost.setValue("http://127.0.0.1:$serverPort", false)
            config.deeplApiKey.setValue("test-key", false)
            config.targetLanguage.setValue("es", false)

            responseBody = """{"translations":[""" +
                """{"detected_source_language":"EN","text":"Hola"},""" +
                """{"detected_source_language":"FR","text":"Monde"}]}"""
            val batchRes = TranslationService.executeBatchTranslation(listOf("Hello", "World"), "es")
            batchRes shouldNotBe null
            batchRes?.size shouldBe 2
            batchRes?.get(0)?.translatedText shouldBe "Hola"
            batchRes?.get(0)?.detectedLanguage shouldBe "en"
            batchRes?.get(0)?.isSameLanguage shouldBe false
            batchRes?.get(1)?.translatedText shouldBe "Monde"

            // Batch with same language
            responseBody = """{"translations":[""" +
                """{"detected_source_language":"ES","text":"Hola"},""" +
                """{"detected_source_language":"ES","text":"Mundo"}]}"""
            val sameBatch = TranslationService.executeBatchTranslation(listOf("Hola", "Mundo"), "es")
            sameBatch shouldNotBe null
            sameBatch?.get(0)?.isSameLanguage shouldBe true
            sameBatch?.get(1)?.isSameLanguage shouldBe true

            // Batch failure / empty
            responseBody = """{"translations":[]}"""
            val failBatch = TranslationService.executeBatchTranslation(listOf("Fail1", "Fail2"), "es")
            failBatch shouldBe null
        } finally {
            config.translationPlugin.setValue(origTrans, false)
            config.deeplApiHost.setValue(origHost, false)
            config.deeplApiKey.setValue(origKey, false)
            config.targetLanguage.setValue(origTarget, false)
        }
    }

    test("DeepL translateAsync routes through deepLQueue and flushes successfully") {
        val config = TranslationService.getConfig()
        val origTrans = config.translationPlugin.value()
        val origHost = config.deeplApiHost.value()
        val origKey = config.deeplApiKey.value()
        val origTarget = config.targetLanguage.value()

        try {
            config.translationPlugin.setValue("deepl", false)
            config.deeplApiHost.setValue("http://127.0.0.1:$serverPort", false)
            config.deeplApiKey.setValue("test-key", false)
            config.targetLanguage.setValue("es", false)

            responseBody = """{"translations":[{"detected_source_language":"EN","text":"Hola Mundo"}]}"""

            val latch = CountDownLatch(1)
            var asyncResult: TranslationResult? = null

            TranslationService.translateAsync("Hello World") { res ->
                asyncResult = res
                latch.countDown()
            }

            latch.await(3, TimeUnit.SECONDS) shouldBe true
            asyncResult shouldNotBe null
            asyncResult?.translatedText shouldBe "Hola Mundo"
            asyncResult?.detectedLanguage shouldBe "en"
            TranslationService.getDeepLQueueSize() shouldBe 0
        } finally {
            config.translationPlugin.setValue(origTrans, false)
            config.deeplApiHost.setValue(origHost, false)
            config.deeplApiKey.setValue(origKey, false)
            config.targetLanguage.setValue(origTarget, false)
        }
    }

    test("normalizeLanguageCode handles null, blank, lol_us, and invalid lengths") {
        TranslationService.normalizeLanguageCode(null) shouldNotBe ""
        TranslationService.normalizeLanguageCode("") shouldNotBe ""
        TranslationService.normalizeLanguageCode("   ") shouldNotBe ""
        TranslationService.normalizeLanguageCode("lol_us") shouldBe "en"
        TranslationService.normalizeLanguageCode("x") shouldBe "en"
        TranslationService.normalizeLanguageCode("abcdef") shouldBe "en"
        TranslationService.normalizeLanguageCode("FR_fr") shouldBe "fr"
    }

    test("default arguments for evict, getCandidateTranslators, and testDeepl") {
        TranslationService.evict("sample_text")
        val candidates = TranslationService.getCandidateTranslators()
        candidates shouldNotBe null

        val latch = CountDownLatch(1)
        var deeplResult: Result<String>? = null
        TranslationService.testDeepl("test-key") { res ->
            deeplResult = res
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        deeplResult shouldNotBe null
    }

    test("testConnection handles error responses with json and plain text") {
        val latch1 = CountDownLatch(1)
        var res1: Result<String>? = null
        responseCode.set(500)
        responseBody = """{"error": "custom error message"}"""
        TranslationService.testConnection("http://127.0.0.1:$serverPort", "key") { r ->
            res1 = r
            latch1.countDown()
        }
        latch1.await(2, TimeUnit.SECONDS) shouldBe true
        res1?.isFailure shouldBe true
        res1?.exceptionOrNull()?.message shouldContain "custom error message"

        val latch2 = CountDownLatch(1)
        var res2: Result<String>? = null
        responseBody = "plain error snippet"
        TranslationService.testConnection("http://127.0.0.1:$serverPort", "key") { r ->
            res2 = r
            latch2.countDown()
        }
        latch2.await(2, TimeUnit.SECONDS) shouldBe true
        res2?.isFailure shouldBe true
        res2?.exceptionOrNull()?.message shouldContain "plain error snippet"
    }

    test("detectLanguage uses fallback detector when primary returns null or unknown") {
        val dummyPrimary = object : com.stellar.lang.plugin.LanguageDetectorPlugin {
            override val id = "dummy-primary"
            override val displayName = "Dummy Primary"
            override val description = "Dummy Primary Description"
            override fun getStatus(): com.stellar.lang.plugin.PluginStatus =
                com.stellar.lang.plugin.PluginStatus.Ready()
            override suspend fun detectLanguage(text: String): String? = null
        }
        val dummyFallback = object : com.stellar.lang.plugin.LanguageDetectorPlugin {
            override val id = "dummy-fallback"
            override val displayName = "Dummy Fallback"
            override val description = "Dummy Fallback Description"
            override fun getStatus(): com.stellar.lang.plugin.PluginStatus =
                com.stellar.lang.plugin.PluginStatus.Ready()
            override suspend fun detectLanguage(text: String): String? = "de"
        }

        val config = TranslationService.getConfig()
        val origDetection = config.detectionPlugin.value()
        try {
            com.stellar.lang.plugin.PluginRegistry.registerDetector(dummyPrimary)
            com.stellar.lang.plugin.PluginRegistry.registerDetector(dummyFallback)
            config.detectionPlugin.setValue("dummy-primary", false)
            com.stellar.lang.plugin.PluginRegistry.setExplicitFallbackDetector(dummyFallback)

            val detected = TranslationService.detectLanguage("Zz123 unfamiliar")
            detected shouldBe "de"
        } finally {
            config.detectionPlugin.setValue(origDetection, false)
            com.stellar.lang.plugin.PluginRegistry.setExplicitFallbackDetector(null)
        }
    }

    test("TranslationService DeepL queue helpers and queue eviction") {
        TranslationService.isDeepLActiveFor() shouldBe false
        TranslationService.getDeepLQueueSize() shouldBe 0

        val config = TranslationService.getConfig()
        val origHost = config.deeplApiHost.value()
        try {
            config.deeplApiHost.setValue("https://api.deepl.com", false)
            TranslationService.evict("manual_queue_test", "de")
            TranslationService.isInFlight("manual_queue_test", "de") shouldBe false
        } finally {
            config.deeplApiHost.setValue(origHost, false)
        }
    }

    test("TranslationService DeepL queue processes failure correctly") {
        val config = TranslationService.getConfig()
        val origTranslator = config.translationPlugin.value()
        val origHost = config.deeplApiHost.value()
        try {
            config.translationPlugin.setValue("deepl", false)
            config.deeplApiHost.setValue("https://api.deepl.com", false)

            TranslationService.failedRequests.remove("fail_key_1")
            TranslationService.deepLQueue.enqueue("Failure text", "fail_key_1", "fr")

            var attempts = 0
            while (attempts < 40 && !TranslationService.failedRequests.containsKey("fail_key_1")) {
                Thread.sleep(50)
                attempts++
            }
            TranslationService.failedRequests.containsKey("fail_key_1") shouldBe true
        } finally {
            config.translationPlugin.setValue(origTranslator, false)
            config.deeplApiHost.setValue(origHost, false)
            TranslationService.deepLQueue.clear()
            TranslationService.failedRequests.remove("fail_key_1")
        }
    }
})
