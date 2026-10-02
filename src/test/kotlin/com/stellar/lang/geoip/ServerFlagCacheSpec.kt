package com.stellar.lang.geoip

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ServerFlagCacheSpec : FunSpec({
    val tempDir = Files.createTempDirectory("server_flag_cache_test")
    val testCachePath = tempDir.resolve("nested/dir/flags_cache.json")

    beforeEach {
        ServerFlagCache.customStoragePath = testCachePath
        ServerFlagCache.clear()
    }

    afterSpec {
        ServerFlagCache.clear()
        ServerFlagCache.customStoragePath = null
        Files.deleteIfExists(testCachePath)
        Files.deleteIfExists(tempDir.resolve("nested/dir"))
        Files.deleteIfExists(tempDir.resolve("nested"))
        Files.deleteIfExists(tempDir)
    }

    test("put and get retrieves stored GeoIpResult") {
        val entry = GeoIpResult(countryCode = "jp", countryName = "Japan")
        ServerFlagCache.put("hypixel.net:25565", entry)

        val retrieved = ServerFlagCache.get("hypixel.net")
        retrieved shouldBe entry
        retrieved?.countryCode shouldBe "jp"
        retrieved?.countryName shouldBe "Japan"

        // Blank host put should be ignored
        ServerFlagCache.put("", entry)
    }

    test("in-flight tracking correctly marks and removes entries") {
        ServerFlagCache.isInFlight("example.com") shouldBe false

        ServerFlagCache.markInFlight("example.com") shouldBe true
        ServerFlagCache.isInFlight("example.com") shouldBe true

        // Duplicate mark should return false
        ServerFlagCache.markInFlight("example.com") shouldBe false

        ServerFlagCache.removeInFlight("example.com")
        ServerFlagCache.isInFlight("example.com") shouldBe false

        // Blank host markInFlight returns false
        ServerFlagCache.markInFlight("") shouldBe false
        ServerFlagCache.isInFlight("") shouldBe false
        ServerFlagCache.removeInFlight("")
    }

    test("saveToDisk when cache is empty returns early without error") {
        ServerFlagCache.clear()
        ServerFlagCache.saveToDisk()
        Files.exists(testCachePath) shouldBe false
    }

    test("saveToDisk creates parent directories and persists entries across restarts") {
        val entry1 = GeoIpResult(countryCode = "us", countryName = "United States")
        val entry2 = GeoIpResult(countryCode = "ca", countryName = "Canada")

        ServerFlagCache.put("server1.net", entry1)
        ServerFlagCache.put("server2.net", entry2)
        ServerFlagCache.saveToDisk()

        Files.exists(testCachePath) shouldBe true

        ServerFlagCache.clear()
        ServerFlagCache.put("server1.net", entry1)
        ServerFlagCache.saveToDisk()

        ServerFlagCache.loadFromDisk()
        ServerFlagCache.get("server1.net") shouldBe entry1
    }

    test("loadFromDisk returns early if file does not exist") {
        Files.deleteIfExists(testCachePath)
        ServerFlagCache.loadFromDisk()
    }

    test("loadFromDisk handles corrupted file safely") {
        Files.createDirectories(testCachePath.parent)
        Files.writeString(testCachePath, "corrupted { invalid json")
        ServerFlagCache.loadFromDisk() // should not throw
    }

    test("clear removes both in-memory cache and file on disk") {
        ServerFlagCache.put("server.com", GeoIpResult(countryCode = "de", countryName = "Germany"))
        ServerFlagCache.saveToDisk()
        Files.exists(testCachePath) shouldBe true

        ServerFlagCache.clear()
        ServerFlagCache.size() shouldBe 0
        Files.exists(testCachePath) shouldBe false
    }

    test("customStoragePath getter and null fallback operate properly") {
        ServerFlagCache.customStoragePath shouldBe testCachePath
        ServerFlagCache.customStoragePath = null
        ServerFlagCache.customStoragePath shouldBe null
        ServerFlagCache.loadFromDisk() // exercises default path branch
    }

    test("saveToDisk handles file system write failure safely") {
        ServerFlagCache.put("error.server", GeoIpResult(countryCode = "de", countryName = "Germany"))
        ServerFlagCache.customStoragePath = java.nio.file.Paths.get("/proc/nonexistent/sub/path/cache.json")
        ServerFlagCache.saveToDisk() // should catch and log warning without crashing
    }
})
