package com.stellar.lang.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeepLTranslationQueueSpec : FunSpec({
    test("DeepLTranslationQueue batches items for the same target language") {
        val completedResults = ConcurrentHashMap<String, TranslationResult?>()
        val batchExecutionCount = AtomicInteger(0)
        val latch = CountDownLatch(5)

        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { texts, targetLang ->
                batchExecutionCount.incrementAndGet()
                texts.map { text ->
                    TranslationResult(
                        originalText = text,
                        translatedText = "Translated $text",
                        detectedLanguage = "en",
                        targetLanguage = targetLang,
                        isSameLanguage = false,
                    )
                }
            },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { key, result ->
                completedResults[key] = result
                latch.countDown()
            },
        )

        for (i in 1..5) {
            queue.enqueue("Text$i", "es::Text$i", "es")
        }

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        batchExecutionCount.get() shouldBe 1
        completedResults.size shouldBe 5
        completedResults["es::Text1"]?.translatedText shouldBe "Translated Text1"
        completedResults["es::Text5"]?.translatedText shouldBe "Translated Text5"
        queue.size() shouldBe 0
    }

    test("DeepLTranslationQueue respects pacing interval between consecutive flushes") {
        val batchExecutionTimes = mutableListOf<Long>()
        val latch = CountDownLatch(2)

        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 60L },
            batchExecutor = { texts, targetLang ->
                synchronized(batchExecutionTimes) {
                    batchExecutionTimes.add(System.currentTimeMillis())
                }
                texts.map { text ->
                    TranslationResult(
                        originalText = text,
                        translatedText = "Trans $text",
                        detectedLanguage = "en",
                        targetLanguage = targetLang,
                        isSameLanguage = false,
                    )
                }
            },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> latch.countDown() },
        )

        queue.enqueue("First", "es::First", "es")
        Thread.sleep(10)
        queue.enqueue("Second", "es::Second", "es")

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        queue.size() shouldBe 0
    }

    test("DeepLTranslationQueue deduplicates pending items with same key") {
        val latch = CountDownLatch(1)
        val batchSizes = mutableListOf<Int>()

        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { texts, targetLang ->
                batchSizes.add(texts.size)
                texts.map { text ->
                    TranslationResult(
                        originalText = text,
                        translatedText = "Trans $text",
                        detectedLanguage = "en",
                        targetLanguage = targetLang,
                        isSameLanguage = false,
                    )
                }
            },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> latch.countDown() },
        )

        queue.enqueue("Duplicate", "es::Duplicate", "es")
        queue.enqueue("Duplicate", "es::Duplicate", "es")

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        batchSizes.firstOrNull() shouldBe 1
    }

    test("DeepLTranslationQueue handles batch failure properly") {
        val failures = ConcurrentHashMap<String, String>()
        val completions = java.util.Collections.synchronizedMap(mutableMapOf<String, TranslationResult?>())
        val latch = CountDownLatch(2)

        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { _, _ -> null },
            onSuccess = {},
            onFailure = { text, targetLang, key ->
                failures[key] = "$text->$targetLang"
            },
            onComplete = { key, result ->
                completions[key] = result
                latch.countDown()
            },
        )

        queue.enqueue("Fail1", "es::Fail1", "es")
        queue.enqueue("Fail2", "es::Fail2", "es")

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        failures.size shouldBe 2
        failures["es::Fail1"] shouldBe "Fail1->es"
        failures["es::Fail2"] shouldBe "Fail2->es"
        completions["es::Fail1"] shouldBe null
        completions["es::Fail2"] shouldBe null
    }

    test("DeepLTranslationQueue groups by target language") {
        val batches = mutableListOf<Pair<String, List<String>>>()
        val latch = CountDownLatch(4)

        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { texts, targetLang ->
                synchronized(batches) {
                    batches.add(targetLang to texts)
                }
                texts.map { text ->
                    TranslationResult(
                        originalText = text,
                        translatedText = "$targetLang:$text",
                        detectedLanguage = "en",
                        targetLanguage = targetLang,
                        isSameLanguage = false,
                    )
                }
            },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> latch.countDown() },
        )

        queue.enqueue("Es1", "es::Es1", "es")
        queue.enqueue("Fr1", "fr::Fr1", "fr")
        queue.enqueue("Es2", "es::Es2", "es")
        queue.enqueue("Fr2", "fr::Fr2", "fr")

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        batches.size shouldBe 2
        val langs = batches.map { it.first }.toSet()
        langs shouldBe setOf("es", "fr")
    }

    test("DeepLTranslationQueue clear and remove methods work") {
        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 1000L },
            batchExecutor = { texts, targetLang ->
                texts.map { text ->
                    TranslationResult(text, text, targetLang, targetLang, true)
                }
            },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> },
        )

        queue.enqueue("A", "es::A", "es")
        queue.isEnqueued("es::A") shouldBe true
        queue.remove("es::A")
        queue.isEnqueued("es::A") shouldBe false

        queue.clear()
        queue.size() shouldBe 0
    }

    test("DeepLTranslationQueue triggers onFailure when batchExecutor returns null") {
        var failureOccurred = false
        val latch = CountDownLatch(1)
        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { _, _ -> null },
            onSuccess = {},
            onFailure = { text, targetLang, key ->
                failureOccurred = true
            },
            onComplete = { _, _ ->
                latch.countDown()
            },
        )

        queue.enqueue("Hello", "fail_key", "de")
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        failureOccurred shouldBe true

        queue.lastBatchTime = 500L
        queue.lastBatchTime shouldBe 500L
    }

    test("DeepLTranslationQueue enforces rate limiting via TokenBucket") {
        val limiter = com.stellar.core.ratelimit.TokenBucket.forRequestsPerSecond(
            requestsPerSecond = 50.0,
            burstSize = 2.0,
        )
        val latch = CountDownLatch(2)
        val queue = DeepLTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { texts, _ -> texts.map { TranslationResult(it, it, "en", "es", false) } },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> latch.countDown() },
            rateLimiter = limiter,
        )

        queue.enqueue("Item1", "k1", "es")
        queue.enqueue("Item2", "k2", "es")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        queue.size() shouldBe 0
    }
})
