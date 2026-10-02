@file:Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
    "TooGenericExceptionCaught",
    "LoopWithTooManyJumpStatements",
    "NestedBlockDepth",
)

package com.stellar.lang.service

import com.stellar.lang.plugin.google.GooglePlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe queue that coalesces and flushes asynchronous Google Translation API requests gradually.
 * Slows down request throughput and batches pending texts to respect rate limits and quota.
 */
class GoogleTranslationQueue(
    private val pacingIntervalProvider: () -> Long,
    private val batchExecutor: (texts: List<String>, targetLang: String) -> List<TranslationResult>?,
    private val onSuccess: (TranslationResult) -> Unit,
    private val onFailure: (text: String, targetLang: String, key: String) -> Unit,
    private val onComplete: (key: String, result: TranslationResult?) -> Unit,
) {
    private val logger: Logger = LoggerFactory.getLogger("StellarLang-GoogleQueue")

    data class QueuedItem(
        val text: String,
        val key: String,
        val targetLang: String,
    )

    private val pendingQueue = ConcurrentLinkedQueue<QueuedItem>()
    private val enqueuedKeys = ConcurrentHashMap.newKeySet<String>()
    private val isFlushing = AtomicBoolean(false)

    @Volatile
    var lastBatchTime: Long = 0L

    private val queueExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "StellarLang-GoogleQueueWorker").apply { isDaemon = true }
    }

    fun enqueue(text: String, key: String, targetLang: String) {
        if (!enqueuedKeys.add(key)) {
            return
        }
        pendingQueue.add(QueuedItem(text, key, targetLang))
        triggerFlush()
    }

    fun isEnqueued(key: String): Boolean = enqueuedKeys.contains(key)

    fun size(): Int = pendingQueue.size

    fun clear() {
        enqueuedKeys.clear()
        pendingQueue.clear()
    }

    fun remove(key: String) {
        enqueuedKeys.remove(key)
        pendingQueue.removeIf { it.key == key }
    }

    fun triggerFlush() {
        if (isFlushing.compareAndSet(false, true)) {
            queueExecutor.execute {
                runCatching { processQueue() }
            }
        }
    }

    private fun drainNextBatch(): Pair<String, List<QueuedItem>>? {
        val first = pendingQueue.peek() ?: return null
        val targetLang = first.targetLang
        val batch = mutableListOf<QueuedItem>()
        val iterator = pendingQueue.iterator()

        while (iterator.hasNext() && batch.size < GooglePlugin.MAX_BATCH_SIZE) {
            val item = iterator.next()
            if (item.targetLang.equals(targetLang, ignoreCase = true)) {
                batch.add(item)
                iterator.remove()
                enqueuedKeys.remove(item.key)
            }
        }

        return if (batch.isEmpty()) null else targetLang to batch
    }

    private fun enforcePacing() {
        val interval = pacingIntervalProvider()
        val now = System.currentTimeMillis()
        val elapsed = now - lastBatchTime
        if (lastBatchTime > 0 && elapsed < interval) {
            sleepQuietly(interval - elapsed)
        }
    }

    private fun dispatchBatch(batch: List<QueuedItem>, targetLang: String) {
        val texts = batch.map { it.text }
        val results = batchExecutor(texts, targetLang)
        lastBatchTime = System.currentTimeMillis()

        for (i in batch.indices) {
            val item = batch[i]
            val result = results?.getOrNull(i)
            if (result != null && !TranslationService.isUntranslatedFailure(result)) {
                onSuccess(result)
                onComplete(item.key, result)
            } else {
                onFailure(item.text, item.targetLang, item.key)
                onComplete(item.key, null)
            }
        }
    }

    private fun processQueue() {
        try {
            while (pendingQueue.isNotEmpty()) {
                val nextBatch = drainNextBatch() ?: break
                enforcePacing()
                dispatchBatch(nextBatch.second, nextBatch.first)
            }
        } catch (ex: Exception) {
            logger.warn("Google translation queue encountered an unexpected error: {}", ex.message)
        } finally {
            isFlushing.set(false)
            if (pendingQueue.isNotEmpty()) {
                triggerFlush()
            }
        }
    }

    private fun sleepQuietly(millis: Long) {
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
