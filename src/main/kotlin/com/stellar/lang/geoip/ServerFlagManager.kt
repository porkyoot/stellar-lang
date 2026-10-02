@file:Suppress("TooGenericExceptionCaught")

package com.stellar.lang.geoip

import com.stellar.lang.badge.LanguageFlagHelper
import com.stellar.lang.service.TranslationService
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates GeoIP resolution and flag badge metadata for multiplayer servers.
 */
object ServerFlagManager {
    private val logger = LoggerFactory.getLogger("StellarLang-ServerFlagManager")
    private val threadIndex = AtomicInteger(1)

    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "StellarLang-GeoIP-${threadIndex.getAndIncrement()}").apply {
            isDaemon = true
        }
    }

    var resolver: GeoIpResolver = DefaultGeoIpResolver()
    var isFeatureEnabled: (() -> Boolean)? = null

    init {
        ServerFlagCache.loadFromDisk()
    }

    fun isEnabled(): Boolean {
        isFeatureEnabled?.let { return it() }
        return runCatching {
            val config = TranslationService.getConfig()
            config.enabled.value() && config.serverCountryFlags.value()
        }.getOrDefault(true)
    }

    fun processServer(serverData: ServerData?): GeoIpResult? {
        val host = resolveCleanHost(serverData) ?: return null
        if (GeoIpAddressHelper.isLocalOrPrivateAddress(host)) {
            ServerFlagCache.put(host, GeoIpResult.LOCAL)
            return GeoIpResult.LOCAL
        }
        return resolveCachedOrFetch(host)
    }

    private fun resolveCleanHost(serverData: ServerData?): String? {
        if (serverData == null || !isEnabled()) return null
        val host = GeoIpAddressHelper.cleanHost(serverData.ip)
        return host.ifBlank { null }
    }

    private fun resolveCachedOrFetch(host: String): GeoIpResult? {
        val cached = ServerFlagCache.get(host)
        if (cached != null) return cached

        if (ServerFlagCache.markInFlight(host)) {
            executor.execute {
                try {
                    val resolved = resolver.resolve(host) ?: GeoIpResult.UNKNOWN
                    ServerFlagCache.put(host, resolved)
                    ServerFlagCache.saveToDisk()
                } catch (ex: Exception) {
                    logger.debug("Failed to resolve GeoIP for {}: {}", host, ex.message)
                    ServerFlagCache.put(host, GeoIpResult.UNKNOWN)
                } finally {
                    ServerFlagCache.removeInFlight(host)
                }
            }
        }
        return null
    }

    fun getFlagChar(serverData: ServerData?): Char? {
        val result = processServer(serverData) ?: return null
        return LanguageFlagHelper.getFlagChar(result.countryCode)
    }

    fun getTooltip(serverData: ServerData?): Component? {
        val result = processServer(serverData) ?: return null
        return Component.literal(result.countryName)
    }

    fun clearCache() {
        ServerFlagCache.clear()
    }
}
