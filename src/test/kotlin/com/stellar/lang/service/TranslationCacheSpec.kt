@file:Suppress("LargeClass")

package com.stellar.lang.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TranslationCacheSpec : FunSpec({
    beforeEach {
        TranslationCache.clear()
        TranslationCache.resetForTesting(null, 100)
    }

    test("basic put and get returns cached result") {
        val result = TranslationResult("Bonjour", "Hello", "fr", "en", false)
        TranslationCache.put(result)

        val retrieved = TranslationCache.get("Bonjour", "en")
        retrieved shouldNotBe null
        retrieved?.translatedText shouldBe "Hello"
        retrieved?.detectedLanguage shouldBe "fr"
        TranslationCache.size() shouldBe 1

        val missed = TranslationCache.get("Bonjour", "es")
        missed shouldBe null
    }

    test("LRU eviction respects max capacity") {
        TranslationCache.setMaxEntries(3)

        val item1 = TranslationResult("Un", "One", "fr", "en", false)
        val item2 = TranslationResult("Deux", "Two", "fr", "en", false)
        val item3 = TranslationResult("Trois", "Three", "fr", "en", false)
        val item4 = TranslationResult("Quatre", "Four", "fr", "en", false)

        TranslationCache.put(item1)
        TranslationCache.put(item2)
        TranslationCache.put(item3)
        TranslationCache.size() shouldBe 3

        // Access item1 to mark it recently used
        TranslationCache.get("Un", "en") shouldNotBe null

        // Add 4th item, which should evict the eldest untouched item (item2)
        TranslationCache.put(item4)
        TranslationCache.size() shouldBe 3

        TranslationCache.get("Deux", "en") shouldBe null
        TranslationCache.get("Un", "en") shouldNotBe null
        TranslationCache.get("Trois", "en") shouldNotBe null
        TranslationCache.get("Quatre", "en") shouldNotBe null
    }

    test("setMaxEntries trims existing entries down to new maximum") {
        TranslationCache.setMaxEntries(10)
        for (idx in 1..5) {
            TranslationCache.put(TranslationResult("Text$idx", "Trans$idx", "fr", "en", false))
        }
        TranslationCache.size() shouldBe 5

        TranslationCache.setMaxEntries(2)
        TranslationCache.size() shouldBe 2
    }

    test("in-flight deduplication coalesces concurrent requests for same key") {
        val key = TranslationCache.cacheKey("Concurrent Text", "en")
        val callbackCount = AtomicInteger(0)
        val latch = CountDownLatch(3)

        val first = TranslationCache.queueInFlight(key) { res ->
            if (res?.translatedText == "Translated Concurrent") {
                callbackCount.incrementAndGet()
            }
            latch.countDown()
        }
        first shouldBe true
        TranslationCache.isInFlight(key) shouldBe true

        val second = TranslationCache.queueInFlight(key) { res ->
            if (res?.translatedText == "Translated Concurrent") {
                callbackCount.incrementAndGet()
            }
            latch.countDown()
        }
        second shouldBe false

        val third = TranslationCache.queueInFlight(key) { res ->
            if (res?.translatedText == "Translated Concurrent") {
                callbackCount.incrementAndGet()
            }
            latch.countDown()
        }
        third shouldBe false

        // Complete the in-flight request
        val result = TranslationResult("Concurrent Text", "Translated Concurrent", "fr", "en", false)
        TranslationCache.completeInFlight(key, result)

        val completed = latch.await(2, TimeUnit.SECONDS)
        completed shouldBe true
        callbackCount.get() shouldBe 3
        TranslationCache.isInFlight(key) shouldBe false
    }

    test("circuit breaker trips and resets") {
        TranslationCache.isCircuitBreakerOpen() shouldBe false

        TranslationCache.tripCircuitBreaker(5000L)
        TranslationCache.isCircuitBreakerOpen() shouldBe true

        TranslationCache.resetCircuitBreaker()
        TranslationCache.isCircuitBreakerOpen() shouldBe false
    }

    test("negative caching and throttling cooldown") {
        val key = "en::error"
        TranslationCache.isThrottled(key) shouldBe false

        TranslationCache.markFailed(key)
        TranslationCache.isThrottled(key) shouldBe true

        TranslationCache.clear()
        TranslationCache.isThrottled(key) shouldBe false
    }

    test("disk persistence saves to file and reloads cache") {
        val tempDir = Files.createTempDirectory("stellar_lang_cache_test")
        val cacheFile = tempDir.resolve("test_cache.json")

        TranslationCache.resetForTesting(cacheFile, 100)

        val itemA = TranslationResult("Bonjour", "Hello", "fr", "en", false)
        val itemB = TranslationResult("Merci", "Thanks", "fr", "en", false)
        TranslationCache.put(itemA)
        TranslationCache.put(itemB)
        TranslationCache.isDirty() shouldBe true

        // Flush to disk
        TranslationCache.flush()
        Files.exists(cacheFile) shouldBe true
        TranslationCache.isDirty() shouldBe false

        // Clear memory
        TranslationCache.clear()
        TranslationCache.size() shouldBe 0
        TranslationCache.get("Bonjour", "en") shouldBe null

        // Reload from disk
        TranslationCache.loadFromDisk()
        TranslationCache.size() shouldBe 2
        TranslationCache.get("Bonjour", "en")?.translatedText shouldBe "Hello"
        TranslationCache.get("Merci", "en")?.translatedText shouldBe "Thanks"

        // Cleanup
        Files.deleteIfExists(cacheFile)
        Files.deleteIfExists(tempDir)
    }

    test("loadFromDisk handles missing or corrupted files gracefully") {
        val tempFile = Files.createTempFile("corrupted_cache", ".json")
        Files.writeString(tempFile, "{ this is invalid json !!!")

        TranslationCache.loadFromDisk(tempFile)
        TranslationCache.size() shouldBe 0

        Files.deleteIfExists(tempFile)

        val nonExistent = tempFile.parent.resolve("non_existent_file.json")
        TranslationCache.loadFromDisk(nonExistent)
        TranslationCache.size() shouldBe 0
    }

    test("completeInFlight handles throwing callbacks safely") {
        val key = "en::throw"
        TranslationCache.queueInFlight(key) {
            throw IllegalStateException("Test exception in callback")
        }
        val res = TranslationResult("throw", "throw_trans", "en", "en", true)
        TranslationCache.completeInFlight(key, res)
        TranslationCache.isInFlight(key) shouldBe false
    }

    test("resolveStoragePath and customStoragePath getter setter") {
        TranslationCache.customStoragePath = null
        val defaultPath = TranslationCache.resolveStoragePath()
        defaultPath.toString() shouldNotBe ""

        val tempPath = Files.createTempFile("custom_storage", ".json")
        TranslationCache.customStoragePath = tempPath
        TranslationCache.customStoragePath shouldBe tempPath
        TranslationCache.resolveStoragePath() shouldBe tempPath

        Files.deleteIfExists(tempPath)
        TranslationCache.customStoragePath = null
    }

    test("saveToDisk and resetForTesting default arguments") {
        // Reset using default arguments
        TranslationCache.resetForTesting()
        TranslationCache.size() shouldBe 0

        // saveToDisk default argument when empty
        TranslationCache.saveToDisk()

        // saveToDisk when writing throws IOException (target is a directory)
        val tempDir = Files.createTempDirectory("err_dir_test")
        TranslationCache.put(TranslationResult("err", "err", "en", "es", false))
        TranslationCache.saveToDisk(tempDir)
        Files.deleteIfExists(tempDir)

        // flush when not dirty
        TranslationCache.clear()
        TranslationCache.flush()
    }
})
