package com.stellar.lang.geoip

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GeoIpAddressHelperSpec : FunSpec({
    test("cleanHost cleans regular IPv4 address") {
        GeoIpAddressHelper.cleanHost("1.2.3.4") shouldBe "1.2.3.4"
    }

    test("cleanHost cleans IPv4 address with port") {
        GeoIpAddressHelper.cleanHost("1.2.3.4:25565") shouldBe "1.2.3.4"
    }

    test("cleanHost cleans domain names with and without port") {
        GeoIpAddressHelper.cleanHost("mc.hypixel.net") shouldBe "mc.hypixel.net"
        GeoIpAddressHelper.cleanHost("mc.hypixel.net:25565") shouldBe "mc.hypixel.net"
        GeoIpAddressHelper.cleanHost("  PLAY.EXAMPLE.COM:19132  ") shouldBe "play.example.com"
        GeoIpAddressHelper.cleanHost("play.example.com.") shouldBe "play.example.com"
    }

    test("cleanHost cleans IPv6 addresses with and without brackets and ports") {
        GeoIpAddressHelper.cleanHost("[::1]:25565") shouldBe "::1"
        GeoIpAddressHelper.cleanHost("[2001:db8::1]:25565") shouldBe "2001:db8::1"
        GeoIpAddressHelper.cleanHost("[2001:db8::1]") shouldBe "2001:db8::1"
        GeoIpAddressHelper.cleanHost("::1") shouldBe "::1"
    }

    test("cleanHost handles null, empty, and whitespace strings") {
        GeoIpAddressHelper.cleanHost(null) shouldBe ""
        GeoIpAddressHelper.cleanHost("") shouldBe ""
        GeoIpAddressHelper.cleanHost("   ") shouldBe ""
    }

    test("isLocalOrPrivateAddress detects loopback and localhost") {
        GeoIpAddressHelper.isLocalOrPrivateAddress("localhost") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("127.0.0.1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("127.0.1.1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("::1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("0.0.0.0") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("") shouldBe true
    }

    test("isLocalOrPrivateAddress detects private IPv4 ranges") {
        GeoIpAddressHelper.isLocalOrPrivateAddress("10.0.0.1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("10.254.12.3:25565") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("192.168.1.1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("192.168.0.254:25565") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.16.0.1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.24.1.1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.31.255.255") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("169.254.1.1") shouldBe true
    }

    test("isLocalOrPrivateAddress detects local suffixes") {
        GeoIpAddressHelper.isLocalOrPrivateAddress("myserver.local") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("node.internal") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("router.lan") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("nas.home") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("intranet.corp") shouldBe true
    }

    test("isLocalOrPrivateAddress detects private IPv6 addresses") {
        GeoIpAddressHelper.isLocalOrPrivateAddress("fe80::1ff:fe23:4567:890a") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("fc00::1") shouldBe true
        GeoIpAddressHelper.isLocalOrPrivateAddress("fd12:3456:789a::1") shouldBe true
    }

    test("isLocalOrPrivateAddress identifies public IPs and domains as non-local") {
        GeoIpAddressHelper.isLocalOrPrivateAddress("8.8.8.8") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("1.1.1.1:25565") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.15.0.1") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.10.0.1") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.32.0.1") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.40.0.1") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.abc.0.1") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("172.") shouldBe false
        GeoIpAddressHelper.isLocalOrPrivateAddress("mc.hypixel.net") shouldBe false
    }
})
