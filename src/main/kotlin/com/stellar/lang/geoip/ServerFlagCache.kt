@file:Suppress("TooGenericExceptionCaught")

package com.stellar.lang.geoip

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory and disk persistent cache for resolved server GeoIP locations.
 */
object ServerFlagCache {
    private val logger = LoggerFactory.getLogger("StellarLang-ServerFlagCache")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val cache = ConcurrentHashMap<String, GeoIpResult>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val diskLock = Any()

    @Volatile
    var customStoragePath: Path? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread(
                { saveToDisk() },
                "StellarLang-ServerFlagsShutdownSaver",
            ),
        )
    }

    private fun getStoragePath(): Path {
        return customStoragePath ?: Paths.get("config", "stellar_lang", "server_flags_cache.json")
    }

    fun get(host: String): GeoIpResult? {
        val clean = GeoIpAddressHelper.cleanHost(host)
        return cache[clean]
    }

    fun put(host: String, result: GeoIpResult) {
        val clean = GeoIpAddressHelper.cleanHost(host)
        if (clean.isNotBlank()) {
            cache[clean] = result
        }
    }

    fun isInFlight(host: String): Boolean {
        val clean = GeoIpAddressHelper.cleanHost(host)
        return inFlight.contains(clean)
    }

    fun markInFlight(host: String): Boolean {
        val clean = GeoIpAddressHelper.cleanHost(host)
        if (clean.isBlank()) return false
        return inFlight.add(clean)
    }

    fun removeInFlight(host: String) {
        val clean = GeoIpAddressHelper.cleanHost(host)
        inFlight.remove(clean)
    }

    fun loadFromDisk() {
        val path = getStoragePath()
        if (!Files.exists(path)) return

        synchronized(diskLock) {
            runCatching {
                Files.newBufferedReader(path).use { reader ->
                    val type = object : TypeToken<Map<String, GeoIpResult>>() {}.type
                    val loaded: Map<String, GeoIpResult>? = gson.fromJson(reader, type)
                    if (loaded != null) {
                        cache.putAll(loaded)
                    }
                }
            }.onFailure { ex ->
                logger.warn("Failed to load server flags cache from disk: {}", ex.message)
            }
        }
    }

    fun saveToDisk() {
        if (cache.isEmpty()) return
        val path = getStoragePath()

        synchronized(diskLock) {
            runCatching {
                val parent = path.parent
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent)
                }
                val json = gson.toJson(cache)
                Files.writeString(
                    path,
                    json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }.onFailure { ex ->
                logger.warn("Failed to save server flags cache to disk: {}", ex.message)
            }
        }
    }

    fun clear() {
        cache.clear()
        inFlight.clear()
        val path = getStoragePath()
        synchronized(diskLock) {
            runCatching {
                Files.deleteIfExists(path)
            }
        }
    }

    fun size(): Int = cache.size
}
