package com.stellar.lang.geoip

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.InetSocketAddress

class GeoIpResolverSpec : FunSpec({
    test("resolve returns result when primary API succeeds") {
        val resolver = DefaultGeoIpResolver { url ->
            if (url.contains("ip-api.com")) {
                """{"status":"success","country":"Germany","countryCode":"DE"}"""
            } else {
                null
            }
        }

        val result = resolver.resolve("play.hypixel.net")
        result shouldNotBe null
        result?.countryCode shouldBe "de"
        result?.countryName shouldBe "Germany"
    }

    test("resolve handles blank code in primary and falls back") {
        val resolver = DefaultGeoIpResolver { url ->
            if (url.contains("ip-api.com")) {
                """{"status":"success","country":"Unknown","countryCode":""}"""
            } else if (url.contains("country.is")) {
                """{"country":"CA"}"""
            } else {
                null
            }
        }

        val result = resolver.resolve("8.8.8.8")
        result shouldNotBe null
        result?.countryCode shouldBe "ca"
        result?.countryName shouldBe "Canada"
    }

    test("resolve handles blank code in fallback API") {
        val resolver = DefaultGeoIpResolver { url ->
            if (url.contains("ip-api.com")) {
                """{"status":"fail"}"""
            } else {
                """{"country":""}"""
            }
        }

        val result = resolver.resolve("8.8.8.8")
        result shouldBe null
    }

    test("resolve falls back to country.is when primary API fails") {
        val resolver = DefaultGeoIpResolver { url ->
            if (url.contains("ip-api.com")) {
                """{"status":"fail","message":"invalid query"}"""
            } else if (url.contains("country.is")) {
                """{"country":"FR"}"""
            } else {
                null
            }
        }

        val result = resolver.resolve("8.8.8.8")
        result shouldNotBe null
        result?.countryCode shouldBe "fr"
        result?.countryName shouldBe "France"
    }

    test("resolve catches exceptions from httpFetcher and logs debug") {
        val resolver = DefaultGeoIpResolver { _ ->
            error("Simulated network crash")
        }

        val result = resolver.resolve("8.8.8.8")
        result shouldBe null
    }

    test("resolve returns null when both APIs fail or return null") {
        val resolver = DefaultGeoIpResolver { _ -> null }
        val result = resolver.resolve("8.8.8.8")
        result shouldBe null
    }

    test("resolve returns null when primary returns malformed JSON and secondary fails") {
        val resolver = DefaultGeoIpResolver { url ->
            if (url.contains("ip-api.com")) {
                "not json at all"
            } else {
                null
            }
        }

        val result = resolver.resolve("8.8.8.8")
        result shouldBe null
    }

    test("resolve returns null for blank or empty host") {
        val resolver = DefaultGeoIpResolver { _ ->
            """{"status":"success","country":"Germany","countryCode":"DE"}"""
        }

        resolver.resolve("") shouldBe null
        resolver.resolve("   ") shouldBe null
    }

    test("defaultHttpFetch succeeds on 200 response and fails on 500 response") {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/ok") { exchange ->
            val bytes = "{\"status\":\"ok\"}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/error") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()

        try {
            val port = server.address.port
            val okResult = DefaultGeoIpResolver.defaultHttpFetch("http://127.0.0.1:$port/ok")
            okResult shouldBe "{\"status\":\"ok\"}"

            val errResult = DefaultGeoIpResolver.defaultHttpFetch("http://127.0.0.1:$port/error")
            errResult shouldBe null
        } finally {
            server.stop(0)
        }
    }

    test("defaultHttpFetch handles connection failure gracefully") {
        val result = DefaultGeoIpResolver.defaultHttpFetch("http://invalid.local.nonexistent.domain:9999/json")
        result shouldBe null
    }

    test("resolve handles unresolvable host for country.is fallback") {
        val resolver = DefaultGeoIpResolver { url ->
            if (url.contains("ip-api.com")) null else null
        }
        val result = resolver.resolve("invalid.unresolvable.hostname.999999.xyz")
        result shouldBe null
    }

    test("resolve falls back to countryCode uppercase when locale display country is blank") {
        val resolver = DefaultGeoIpResolver { url ->
            if (url.contains("ip-api.com")) {
                """{"status":"fail"}"""
            } else if (url.contains("country.is")) {
                """{"country":"zz"}"""
            } else {
                null
            }
        }
        val result = resolver.resolve("8.8.8.8")
        result shouldNotBe null
        result?.countryCode shouldBe "zz"
    }

    test("DefaultGeoIpResolver instantiates with default fetcher") {
        val resolver = DefaultGeoIpResolver()
        resolver shouldNotBe null
    }
})
