package com.stellar.lang.geoip

import com.stellar.lang.badge.LanguageFlagHelper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.client.multiplayer.ServerData
import java.nio.file.Files

class ServerFlagManagerSpec : FunSpec({
    val tempDir = Files.createTempDirectory("server_flag_manager_test")
    val testCachePath = tempDir.resolve("flags_cache.json")

    beforeSpec {
        net.minecraft.SharedConstants.tryDetectVersion()
    }

    beforeEach {
        ServerFlagCache.customStoragePath = testCachePath
        ServerFlagManager.clearCache()
        ServerFlagManager.isFeatureEnabled = null
        ServerFlagManager.resolver = object : GeoIpResolver {
            override fun resolve(host: String): GeoIpResult? {
                return when (host) {
                    "fr.server.com" -> GeoIpResult(countryCode = "fr", countryName = "France")
                    "us.server.com" -> GeoIpResult(countryCode = "us", countryName = "United States")
                    "de.server.com" -> GeoIpResult(countryCode = "de", countryName = "Germany")
                    "error.server.com" -> error("Simulated resolver error")
                    "null.server.com" -> null
                    else -> GeoIpResult.UNKNOWN
                }
            }
        }
    }

    afterSpec {
        ServerFlagManager.clearCache()
        Files.deleteIfExists(testCachePath)
        Files.deleteIfExists(tempDir)
    }

    test("isEnabled returns false when feature toggle is false") {
        ServerFlagManager.isFeatureEnabled = { false }
        ServerFlagManager.isEnabled() shouldBe false

        val server = ServerData("Test", "fr.server.com", ServerData.Type.OTHER)
        ServerFlagManager.processServer(server) shouldBe null
        ServerFlagManager.getFlagChar(server) shouldBe null
        ServerFlagManager.getTooltip(server) shouldBe null
    }

    test("isEnabled defaults to true when no override is set") {
        ServerFlagManager.isFeatureEnabled = null
        ServerFlagManager.isEnabled() shouldBe true

        ServerFlagManager.getFlagChar(null) shouldBe null
        ServerFlagManager.getTooltip(null) shouldBe null
    }

    test("processServer returns null for null or blank server") {
        ServerFlagManager.processServer(null) shouldBe null

        val emptyServer = ServerData("Empty", "", ServerData.Type.OTHER)
        ServerFlagManager.processServer(emptyServer) shouldBe null

        val spacesServer = ServerData("Spaces", "   ", ServerData.Type.OTHER)
        ServerFlagManager.processServer(spacesServer) shouldBe null
    }

    test("processServer returns LOCAL for local or LAN addresses") {
        val localhostServer = ServerData("Localhost", "localhost:25565", ServerData.Type.OTHER)
        val result = ServerFlagManager.processServer(localhostServer)

        result shouldBe GeoIpResult.LOCAL
        result?.isLocal shouldBe true
        result?.countryCode shouldBe "globe"

        val flagChar = ServerFlagManager.getFlagChar(localhostServer)
        flagChar shouldBe LanguageFlagHelper.FALLBACK_CHAR

        val tooltip = ServerFlagManager.getTooltip(localhostServer)
        tooltip?.string shouldBe GeoIpResult.LOCAL_COUNTRY_NAME
    }

    test("processServer resolves and caches remote server flags") {
        val server = ServerData("FrenchServer", "fr.server.com:25565", ServerData.Type.OTHER)

        // First call triggers background resolution and returns null initially
        ServerFlagManager.processServer(server) shouldBe null
        // Wait a brief moment for worker thread to complete
        Thread.sleep(100)

        val resolved = ServerFlagManager.processServer(server)
        resolved shouldNotBe null
        resolved?.countryCode shouldBe "fr"
        resolved?.countryName shouldBe "France"

        val flagChar = ServerFlagManager.getFlagChar(server)
        flagChar shouldBe LanguageFlagHelper.getFlagChar("fr")

        val tooltip = ServerFlagManager.getTooltip(server)
        tooltip?.string shouldBe "France"
    }

    test("processServer handles resolver error by storing UNKNOWN") {
        val server = ServerData("ErrorServer", "error.server.com", ServerData.Type.OTHER)
        ServerFlagManager.processServer(server) shouldBe null
        Thread.sleep(100)

        val result = ServerFlagManager.processServer(server)
        result shouldBe GeoIpResult.UNKNOWN
    }

    test("processServer handles resolver returning null by storing UNKNOWN") {
        val server = ServerData("NullServer", "null.server.com", ServerData.Type.OTHER)
        ServerFlagManager.processServer(server) shouldBe null
        Thread.sleep(100)

        val result = ServerFlagManager.processServer(server)
        result shouldBe GeoIpResult.UNKNOWN
    }

    test("clearCache flushes cached server entries") {
        val server = ServerData("GermanServer", "de.server.com", ServerData.Type.OTHER)
        ServerFlagCache.put("de.server.com", GeoIpResult(countryCode = "de", countryName = "Germany"))

        ServerFlagManager.processServer(server)?.countryCode shouldBe "de"

        ServerFlagManager.clearCache()
        ServerFlagCache.size() shouldBe 0
    }

    test("property accessors and unknown country flag handling") {
        ServerFlagManager.resolver shouldNotBe null
        ServerFlagManager.isFeatureEnabled shouldBe null
        ServerFlagManager.isFeatureEnabled = { true }
        ServerFlagManager.isEnabled() shouldBe true
        ServerFlagManager.isFeatureEnabled = null

        ServerFlagCache.put("unknown.server.test", GeoIpResult.UNKNOWN)
        val unknownServer = ServerData("Unknown", "unknown.server.test", ServerData.Type.OTHER)
        ServerFlagManager.getFlagChar(unknownServer) shouldBe LanguageFlagHelper.FALLBACK_CHAR
        ServerFlagManager.getTooltip(unknownServer)?.string shouldBe "Unknown Location"
    }
})
