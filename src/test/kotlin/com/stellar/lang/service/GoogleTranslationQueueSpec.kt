package com.stellar.lang.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GoogleTranslationQueueSpec : FunSpec({
    test("GoogleTranslationQueue batches items for the same target language") {
        val completedResults = ConcurrentHashMap<String, TranslationResult?>()
        val batchExecutionCount = AtomicInteger(0)
        val latch = CountDownLatch(5)

        val queue = GoogleTranslationQueue(
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

    test("GoogleTranslationQueue respects pacing interval between consecutive flushes") {
        val batchExecutionTimes = mutableListOf<Long>()
        val latch = CountDownLatch(2)

        val queue = GoogleTranslationQueue(
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

    test("GoogleTranslationQueue deduplicates pending items with same key") {
        val latch = CountDownLatch(1)
        val batchSizes = mutableListOf<Int>()

        val queue = GoogleTranslationQueue(
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

        queue.enqueue("Dup", "es::Dup", "es")
        queue.isEnqueued("es::Dup") shouldBe true
        queue.enqueue("Dup", "es::Dup", "es")

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        batchSizes.firstOrNull() shouldBe 1
    }

    test("GoogleTranslationQueue handles batch failures and untranslated results") {
        val failedItems = mutableListOf<String>()
        val completedKeys = mutableListOf<String>()
        val latch = CountDownLatch(2)

        val queue = GoogleTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { _, _ -> null },
            onSuccess = {},
            onFailure = { text, _, _ ->
                synchronized(failedItems) { failedItems.add(text) }
            },
            onComplete = { key, _ ->
                synchronized(completedKeys) { completedKeys.add(key) }
                latch.countDown()
            },
        )

        queue.enqueue("Fail1", "fr::Fail1", "fr")
        queue.enqueue("Fail2", "fr::Fail2", "fr")

        latch.await(3, TimeUnit.SECONDS) shouldBe true
        failedItems.size shouldBe 2
        completedKeys.size shouldBe 2
    }

    test("GoogleTranslationQueue remove and clear operations") {
        val queue = GoogleTranslationQueue(
            pacingIntervalProvider = { 10_000L },
            batchExecutor = { _, _ -> emptyList() },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> },
        )

        queue.enqueue("Hold1", "hold1", "de")
        queue.enqueue("Hold2", "hold2", "de")
        queue.isEnqueued("hold1") shouldBe true
        queue.remove("hold1")
        queue.isEnqueued("hold1") shouldBe false

        queue.clear()
        queue.size() shouldBe 0
        queue.isEnqueued("hold2") shouldBe false
    }

    test("GoogleTranslationQueue handles runtime exceptions in batchExecutor gracefully") {
        val queue = GoogleTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { _, _ -> throw IllegalStateException("Simulated worker failure") },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> },
        )

        queue.enqueue("Text", "key", "es")
        Thread.sleep(100)
    }

    test("GoogleTranslationQueue handles empty batch results") {
        val latch = CountDownLatch(1)
        val failed = mutableListOf<String>()

        val queue = GoogleTranslationQueue(
            pacingIntervalProvider = { 0L },
            batchExecutor = { _, _ -> emptyList() },
            onSuccess = {},
            onFailure = { text, _, _ ->
                failed.add(text)
            },
            onComplete = { _, _ ->
                latch.countDown()
            },
        )

        queue.enqueue("EmptyBatchItem", "empty_key", "es")
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        failed.size shouldBe 1
        failed.first() shouldBe "EmptyBatchItem"
    }

    test("GoogleTranslationQueue lastBatchTime property") {
        val queue = GoogleTranslationQueue(
            pacingIntervalProvider = { 50L },
            batchExecutor = { texts, _ -> texts.map { TranslationResult(it, it, "en", "es", false) } },
            onSuccess = {},
            onFailure = { _, _, _ -> },
            onComplete = { _, _ -> },
        )
        queue.lastBatchTime = 12_345L
        queue.lastBatchTime shouldBe 12_345L
    }
})
