package com.stellar.lang.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance, persistent LRU translation cache with in-flight deduplication and circuit-breaker protection.
 */
@Suppress("TooManyFunctions")
object TranslationCache {
    private val logger: Logger = LoggerFactory.getLogger(StellarLangMod.MOD_ID)
    private val gson = Gson()
    private const val ERROR_COOLDOWN_MS = 15_000L
    private const val DEFAULT_CIRCUIT_BREAKER_MS = 60_000L
    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f

    private class LruMap<K, V>(private var maxEntries: Int) : LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }

        fun setMax(max: Int) {
            maxEntries = max
        }
    }

    private val cacheLock = Any()
    private val inFlightLock = Any()
    private val lruCache = LruMap<String, TranslationResult>(StellarLangConfig.DEFAULT_MAX_CACHE_ENTRIES)
    private val inFlight = mutableMapOf<String, MutableList<(TranslationResult?) -> Unit>>()
    private val failedAttempts = ConcurrentHashMap<String, Long>()

    @Volatile
    private var isDirty = false

    @Volatile
    private var initialized = false

    @Volatile
    private var circuitBreakerOpenUntil: Long = 0L

    @Volatile
    var customStoragePath: Path? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                { flush() },
                "StellarLang-CacheShutdownSaver",
            ),
        )
    }

    fun cacheKey(text: String, targetLang: String): String = "$targetLang::$text"

    fun ensureInitialized() {
        if (initialized) return
        synchronized(cacheLock) {
            if (initialized) return
            initialized = true
            val config = TranslationService.getConfig()
            lruCache.setMax(config.maxCacheEntries.value())
            if (config.cacheToDisk.value()) {
                loadFromDisk()
            }
        }
    }

    fun get(text: String, targetLang: String): TranslationResult? {
        ensureInitialized()
        val key = cacheKey(text, targetLang)
        synchronized(cacheLock) {
            return lruCache[key]
        }
    }

    fun put(result: TranslationResult) {
        if (!result.isSameLanguage && result.originalText.equals(result.translatedText, ignoreCase = true)) {
            return
        }
        ensureInitialized()
        val key = cacheKey(result.originalText, result.targetLanguage)
        synchronized(cacheLock) {
            lruCache[key] = result
            isDirty = true
        }
    }

    fun size(): Int {
        synchronized(cacheLock) {
            return lruCache.size
        }
    }

    fun setMaxEntries(max: Int) {
        synchronized(cacheLock) {
            lruCache.setMax(max)
            while (lruCache.size > max && lruCache.isNotEmpty()) {
                val iterator = lruCache.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    fun queueInFlight(key: String, callback: (TranslationResult?) -> Unit): Boolean {
        synchronized(inFlightLock) {
            val pendingList = inFlight[key]
            if (pendingList != null) {
                pendingList.add(callback)
                return false
            }
            inFlight[key] = mutableListOf(callback)
            return true
        }
    }

    fun completeInFlight(key: String, result: TranslationResult?) {
        val callbacks: List<(TranslationResult?) -> Unit>
        synchronized(inFlightLock) {
            callbacks = inFlight.remove(key) ?: emptyList()
        }
        callbacks.forEach { cb ->
            runCatching { cb(result) }
        }
    }

    fun isInFlight(key: String): Boolean {
        synchronized(inFlightLock) {
            return inFlight.containsKey(key)
        }
    }

    fun tripCircuitBreaker(durationMs: Long = DEFAULT_CIRCUIT_BREAKER_MS) {
        circuitBreakerOpenUntil = System.currentTimeMillis() + durationMs
        logger.warn("Circuit breaker tripped for {} ms due to API rate limit/failure.", durationMs)
    }

    fun isCircuitBreakerOpen(): Boolean {
        return System.currentTimeMillis() < circuitBreakerOpenUntil
    }

    fun resetCircuitBreaker() {
        circuitBreakerOpenUntil = 0L
    }

    fun markFailed(key: String) {
        failedAttempts[key] = System.currentTimeMillis()
    }

    fun isThrottled(key: String): Boolean {
        val lastFail = failedAttempts[key] ?: return false
        val elapsed = System.currentTimeMillis() - lastFail
        if (elapsed < ERROR_COOLDOWN_MS) {
            return true
        }
        failedAttempts.remove(key)
        return false
    }

    fun isFailed(key: String): Boolean = isThrottled(key)

    fun clear() {
        synchronized(cacheLock) {
            lruCache.clear()
            isDirty = false
        }
        synchronized(inFlightLock) {
            inFlight.clear()
        }
        failedAttempts.clear()
        resetCircuitBreaker()
    }

    fun resolveStoragePath(): Path {
        customStoragePath?.let { return it }
        val quiltGameDir = runCatching {
            org.quiltmc.loader.api.QuiltLoader.getGameDir()
        }.getOrNull()
        if (quiltGameDir != null) {
            return quiltGameDir.resolve("config").resolve("stellar_lang").resolve("cache.json")
        }
        return Paths.get("config", "stellar_lang", "cache.json")
    }

    fun saveToDisk(targetPath: Path? = null) {
        val path = targetPath ?: resolveStoragePath()
        synchronized(cacheLock) {
            if (lruCache.isEmpty() && !Files.exists(path)) return
            runCatching {
                val parent = path.parent
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent)
                }
                val entries = lruCache.values.toList()
                val json = gson.toJson(entries)
                Files.writeString(
                    path,
                    json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                isDirty = false
            }.onFailure { ex ->
                logger.warn("Failed to save translation cache to {}: {}", path, ex.message)
            }
        }
    }

    fun loadFromDisk(targetPath: Path? = null) {
        val path = targetPath ?: resolveStoragePath()
        if (!Files.exists(path)) return
        synchronized(cacheLock) {
            runCatching {
                val json = Files.readString(path)
                val type = object : TypeToken<List<TranslationResult>>() {}.type
                val list: List<TranslationResult>? = gson.fromJson(json, type)
                list?.forEach { res ->
                    val isBogus = !res.isSameLanguage && res.originalText.equals(res.translatedText, ignoreCase = true)
                    if (!isBogus) {
                        val key = cacheKey(res.originalText, res.targetLanguage)
                        lruCache[key] = res
                    }
                }
                isDirty = false
            }.onFailure { ex ->
                logger.warn("Failed to load translation cache from {}: {}", path, ex.message)
            }
        }
    }

    fun flush(targetPath: Path? = null) {
        synchronized(cacheLock) {
            val config = TranslationService.getConfig()
            if (config.cacheToDisk.value() && isDirty) {
                saveToDisk(targetPath)
            }
        }
    }

    fun isDirty(): Boolean = isDirty

    fun resetForTesting(customPath: Path? = null, maxEntries: Int = 100) {
        customStoragePath = customPath
        synchronized(cacheLock) {
            lruCache.clear()
            lruCache.setMax(maxEntries)
            isDirty = false
            initialized = true
        }
        synchronized(inFlightLock) {
            inFlight.clear()
        }
        failedAttempts.clear()
        resetCircuitBreaker()
    }
}
