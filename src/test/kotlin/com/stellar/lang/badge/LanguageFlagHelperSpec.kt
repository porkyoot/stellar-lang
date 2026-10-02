package com.stellar.lang.badge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class LanguageFlagHelperSpec : FunSpec({
    beforeEach {
        LanguageFlagHelper.clearCache()
    }

    test("resolveCountryCode resolves standard language codes to country flags") {
        LanguageFlagHelper.resolveCountryCode("fr") shouldBe "fr"
        LanguageFlagHelper.resolveCountryCode("en") shouldBe "us"
        LanguageFlagHelper.resolveCountryCode("es") shouldBe "es"
        LanguageFlagHelper.resolveCountryCode("de") shouldBe "de"
        LanguageFlagHelper.resolveCountryCode("ja") shouldBe "jp"
        LanguageFlagHelper.resolveCountryCode("ko") shouldBe "kr"
        LanguageFlagHelper.resolveCountryCode("zh") shouldBe "cn"
        LanguageFlagHelper.resolveCountryCode("ru") shouldBe "ru"
        LanguageFlagHelper.resolveCountryCode("it") shouldBe "it"
        LanguageFlagHelper.resolveCountryCode("pt") shouldBe "pt"
    }

    test("resolveCountryCode handles composite locale codes and region tags") {
        LanguageFlagHelper.resolveCountryCode("en_US") shouldBe "us"
        LanguageFlagHelper.resolveCountryCode("en-GB") shouldBe "gb"
        LanguageFlagHelper.resolveCountryCode("pt_BR") shouldBe "br"
        LanguageFlagHelper.resolveCountryCode("zh_TW") shouldBe "tw"
        LanguageFlagHelper.resolveCountryCode("fr_CA") shouldBe "ca"
        LanguageFlagHelper.resolveCountryCode("es_MX") shouldBe "mx"
    }

    test("resolveCountryCode handles blank, null, or unknown codes") {
        LanguageFlagHelper.resolveCountryCode(null) shouldBe "globe"
        LanguageFlagHelper.resolveCountryCode("") shouldBe "globe"
        LanguageFlagHelper.resolveCountryCode("   ") shouldBe "globe"
        LanguageFlagHelper.resolveCountryCode("unknown_language_xyz") shouldBe "globe"
    }

    test("getFlagChar returns valid custom font characters in PUA range") {
        val globeChar = LanguageFlagHelper.getFlagChar("globe")
        globeChar shouldBe LanguageFlagHelper.FALLBACK_CHAR
        LanguageFlagHelper.isFlagChar(globeChar) shouldBe true

        val frChar = LanguageFlagHelper.getFlagChar("fr")
        LanguageFlagHelper.isFlagChar(frChar) shouldBe true
        frChar shouldNotBe globeChar

        val nullChar = LanguageFlagHelper.getFlagChar(null)
        nullChar shouldBe LanguageFlagHelper.FALLBACK_CHAR

        val blankChar = LanguageFlagHelper.getFlagChar(" ")
        blankChar shouldBe LanguageFlagHelper.FALLBACK_CHAR
    }

    test("getFlagEmoji returns unicode flags via jemoji or fallback") {
        val frEmoji = LanguageFlagHelper.getFlagEmoji("fr")
        frEmoji shouldBe "🇫🇷"

        val usEmoji = LanguageFlagHelper.getFlagEmoji("en")
        usEmoji shouldBe "🇺🇸"

        val esEmoji = LanguageFlagHelper.getFlagEmoji("es")
        esEmoji shouldBe "🇪🇸"

        val globeEmoji = LanguageFlagHelper.getFlagEmoji(null)
        globeEmoji shouldBe "\uD83C\uDF10" // 🌐

        val unknownEmoji = LanguageFlagHelper.getFlagEmoji("unknown")
        unknownEmoji shouldBe "\uD83C\uDF10"
    }

    test("getLanguageName provides display name for language codes") {
        LanguageFlagHelper.getLanguageName("fr") shouldBe "French"
        LanguageFlagHelper.getLanguageName("es") shouldBe "Spanish"
        LanguageFlagHelper.getLanguageName("de") shouldBe "German"
        LanguageFlagHelper.getLanguageName("en") shouldBe "English"
        LanguageFlagHelper.getLanguageName(null) shouldBe "Unknown"
        LanguageFlagHelper.getLanguageName("") shouldBe "Unknown"
        LanguageFlagHelper.getLanguageName("   ") shouldBe "Unknown"
        LanguageFlagHelper.getLanguageName("xyz") shouldBe "XYZ"
        LanguageFlagHelper.getLanguageName("en_US") shouldBe "English (United States)"
        // Cache hit
        LanguageFlagHelper.getLanguageName("fr") shouldBe "French"
    }

    test("isFlagPrefix and stripFlagPrefix recognize and remove flag glyphs") {
        val flag = LanguageFlagHelper.getFlagChar("fr")
        LanguageFlagHelper.isFlagPrefix("$flag Bonjour") shouldBe true
        LanguageFlagHelper.isFlagPrefix("Bonjour") shouldBe false
        LanguageFlagHelper.isFlagPrefix("") shouldBe false

        LanguageFlagHelper.stripFlagPrefix("$flag Bonjour") shouldBe "Bonjour"
        LanguageFlagHelper.stripFlagPrefix("$flag   Hello") shouldBe "Hello"
        LanguageFlagHelper.stripFlagPrefix("$flag") shouldBe ""
        LanguageFlagHelper.stripFlagPrefix("$flag   ") shouldBe ""
        LanguageFlagHelper.stripFlagPrefix("No flag here") shouldBe "No flag here"
    }

    test("createFlagBadge produces component with flag character") {
        val defaultBadge = LanguageFlagHelper.createFlagBadge("fr")
        val frChar = LanguageFlagHelper.getFlagChar("fr")
        defaultBadge.string shouldBe "$frChar "

        val badge = LanguageFlagHelper.createFlagBadge("fr", trailingSpace = true)
        badge.string shouldBe "$frChar "

        val noSpaceBadge = LanguageFlagHelper.createFlagBadge("fr", trailingSpace = false)
        noSpaceBadge.string shouldBe "$frChar"
    }

    test("resolveCountryCode composite locale branch variations") {
        // regionPart not in keys, mappedLang in keys
        LanguageFlagHelper.resolveCountryCode("en_ZZ") shouldBe "us"
        // regionPart not in keys, mappedLang is null, langPart is in keys
        LanguageFlagHelper.resolveCountryCode("fr_ZZ") shouldBe "fr"
        // neither in keys
        LanguageFlagHelper.resolveCountryCode("xx_YY") shouldBe "globe"
        // direct keys
        LanguageFlagHelper.resolveCountryCode("un") shouldBe "un"
        LanguageFlagHelper.resolveCountryCode("eu") shouldBe "eu"
        LanguageFlagHelper.resolveCountryCode("eo") shouldBe "eo"
        LanguageFlagHelper.resolveCountryCode("ad") shouldBe "ad"
    }

    test("getFlagEmoji covers cache hit, regional indicator fallback, and non-2-letter unknown") {
        // Cache hit
        LanguageFlagHelper.getFlagEmoji("fr") shouldBe "🇫🇷"
        LanguageFlagHelper.getFlagEmoji("fr") shouldBe "🇫🇷"

        // Esperanto / regional indicator fallback
        val eoEmoji = LanguageFlagHelper.getFlagEmoji("eo")
        eoEmoji shouldNotBe "\uD83C\uDF10"

        // Non 2-letter unknown fallback
        LanguageFlagHelper.getFlagEmoji("nonexistent_long_code") shouldBe "\uD83C\uDF10"
        // 2-digit non-alpha fallback
        LanguageFlagHelper.getFlagEmoji("12") shouldBe "\uD83C\uDF10"
    }

    test("getLanguageName falls back to uppercase tag when display name matches tag") {
        LanguageFlagHelper.getLanguageName("und") shouldBe "UND"
    }

    test("isFlagChar boundary checks") {
        LanguageFlagHelper.isFlagChar('\uE100') shouldBe true
        LanguageFlagHelper.isFlagChar('\uE1CF') shouldBe true
        LanguageFlagHelper.isFlagChar('\uE0FF') shouldBe false
        LanguageFlagHelper.isFlagChar('\uE1D0') shouldBe false
        LanguageFlagHelper.isFlagChar('A') shouldBe false
    }
})
