@file:Suppress("LargeClass")

package com.stellar.lang.service

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
        responseBody = """{"translatedText": ["NoApiKey"], "detectedLanguage": "en"}"""
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
        // Set failed timestamp to 20 seconds in the past (exceeds 10_000ms cooldown)
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
})
