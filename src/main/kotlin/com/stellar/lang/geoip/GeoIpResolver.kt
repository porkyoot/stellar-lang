@file:Suppress("MagicNumber", "ReturnCount")

package com.stellar.lang.geoip

import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Strategy interface for resolving an internet host/IP to a GeoIP result.
 */
interface GeoIpResolver {
    fun resolve(host: String): GeoIpResult?
}

/**
 * Standard implementation leveraging free online GeoIP APIs with automatic fallback.
 */
class DefaultGeoIpResolver(
    private val httpFetcher: (String) -> String? = ::defaultHttpFetch,
) : GeoIpResolver {
    private val logger = LoggerFactory.getLogger("StellarLang-GeoIP")

    override fun resolve(host: String): GeoIpResult? {
        val clean = GeoIpAddressHelper.cleanHost(host)
        if (clean.isBlank()) return null

        val primaryResult = queryIpApi(clean)
        if (primaryResult != null) {
            return primaryResult
        }

        return queryCountryIs(clean)
    }

    private fun queryIpApi(host: String): GeoIpResult? {
        val url = "http://ip-api.com/json/$host?fields=status,message,country,countryCode"
        return runCatching {
            val json = httpFetcher(url) ?: return null
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj.get("status")?.asString == "success") {
                val code = obj.get("countryCode")?.asString?.lowercase(Locale.ROOT)
                val country = obj.get("country")?.asString ?: "Unknown"
                if (!code.isNullOrBlank()) {
                    return GeoIpResult(countryCode = code, countryName = country)
                }
            }
            null
        }.onFailure { ex ->
            logger.debug("ip-api query failed for {}: {}", host, ex.message)
        }.getOrNull()
    }

    private fun queryCountryIs(host: String): GeoIpResult? {
        val ip = resolveToIp(host) ?: return null
        val url = "https://api.country.is/$ip"
        return runCatching {
            val json = httpFetcher(url) ?: return null
            val obj = JsonParser.parseString(json).asJsonObject
            val code = obj.get("country")?.asString?.lowercase(Locale.ROOT)
            if (!code.isNullOrBlank()) {
                val country = Locale.of("", code).getDisplayCountry(Locale.ENGLISH).ifBlank {
                    code.uppercase(Locale.ROOT)
                }
                return GeoIpResult(countryCode = code, countryName = country)
            }
            null
        }.onFailure { ex ->
            logger.debug("country.is query failed for {}: {}", host, ex.message)
        }.getOrNull()
    }

    private fun resolveToIp(host: String): String? {
        return runCatching {
            InetAddress.getByName(host).hostAddress
        }.getOrNull()
    }

    companion object {
        private const val TIMEOUT_MS = 3000
        private const val USER_AGENT = "StellarLang-GeoIP/1.0"

        fun defaultHttpFetch(urlStr: String): String? {
            return runCatching {
                val connection = URI.create(urlStr).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")

                if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}
