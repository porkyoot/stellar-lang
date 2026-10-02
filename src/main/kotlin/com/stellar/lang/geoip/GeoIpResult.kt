package com.stellar.lang.geoip

/**
 * Result of a GeoIP location resolution for a Minecraft server.
 */
data class GeoIpResult(
    val countryCode: String,
    val countryName: String,
    val isLocal: Boolean = false,
) {
    companion object {
        const val LOCAL_COUNTRY_CODE: String = "globe"
        const val UNKNOWN_COUNTRY_CODE: String = "globe"
        const val LOCAL_COUNTRY_NAME: String = "Local / LAN"
        const val UNKNOWN_COUNTRY_NAME: String = "Unknown Location"

        val LOCAL = GeoIpResult(
            countryCode = LOCAL_COUNTRY_CODE,
            countryName = LOCAL_COUNTRY_NAME,
            isLocal = true,
        )

        val UNKNOWN = GeoIpResult(
            countryCode = UNKNOWN_COUNTRY_CODE,
            countryName = UNKNOWN_COUNTRY_NAME,
            isLocal = false,
        )
    }
}
