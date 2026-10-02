@file:Suppress("MagicNumber")

package com.stellar.lang.geoip

import java.util.Locale

/**
 * Utility for sanitizing server host strings and detecting private or local addresses.
 */
object GeoIpAddressHelper {
    private const val SECOND_OCTET_MIN = 16
    private const val SECOND_OCTET_MAX = 31

    private val LOCAL_DOMAINS = setOf(
        "localhost",
        "127.0.0.1",
        "::1",
        "0.0.0.0",
    )

    private val LOCAL_SUFFIXES = listOf(
        ".local",
        ".internal",
        ".lan",
        ".home",
        ".corp",
    )

    fun cleanHost(rawAddress: String?): String {
        if (rawAddress.isNullOrBlank()) return ""
        var cleaned = rawAddress.trim()

        if (cleaned.startsWith("[") && cleaned.contains("]")) {
            val endBracket = cleaned.indexOf(']')
            cleaned = cleaned.substring(1, endBracket)
        } else {
            val colonIdx = cleaned.lastIndexOf(':')
            if (colonIdx != -1 && cleaned.indexOf(':') == colonIdx) {
                cleaned = cleaned.substring(0, colonIdx)
            }
        }

        return cleaned.trimEnd('.').lowercase(Locale.ROOT)
    }

    fun isLocalOrPrivateAddress(host: String): Boolean {
        if (host.isBlank()) return true
        val clean = cleanHost(host)
        return isKnownLocalDomain(clean) ||
            isPrivateIpv4(clean) ||
            isPrivateIpv6(clean)
    }

    private fun isKnownLocalDomain(host: String): Boolean {
        return LOCAL_DOMAINS.contains(host) || LOCAL_SUFFIXES.any { host.endsWith(it) }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val isPrefixMatch = host.startsWith("10.") ||
            host.startsWith("127.") ||
            host.startsWith("192.168.") ||
            host.startsWith("169.254.")
        if (isPrefixMatch) return true

        if (host.startsWith("172.")) {
            val parts = host.split('.')
            val second = parts.getOrNull(1)?.toIntOrNull()
            return second != null && second in SECOND_OCTET_MIN..SECOND_OCTET_MAX
        }
        return false
    }

    private fun isPrivateIpv6(host: String): Boolean {
        return host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
    }
}
