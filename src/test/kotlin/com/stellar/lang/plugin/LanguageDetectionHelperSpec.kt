package com.stellar.lang.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LanguageDetectionHelperSpec : FunSpec({
    test("detectQuick detects French greetings and common vocabulary") {
        LanguageDetectionHelper.detectQuick("Bonjour") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("bonjour_") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("Salut!") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("Merci beaucoup") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("Pantoufle") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("Fantome") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("fantôme") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("Au revoir") shouldBe "fr"
    }

    test("detectQuick detects Spanish, German, Italian, and English greetings") {
        LanguageDetectionHelper.detectQuick("Hola") shouldBe "es"
        LanguageDetectionHelper.detectQuick("Buenos días") shouldBe "es"
        LanguageDetectionHelper.detectQuick("Gracias") shouldBe "es"
        LanguageDetectionHelper.detectQuick("Hallo") shouldBe "de"
        LanguageDetectionHelper.detectQuick("Guten Tag") shouldBe "de"
        LanguageDetectionHelper.detectQuick("Danke schön") shouldBe "de"
        LanguageDetectionHelper.detectQuick("Ciao") shouldBe "it"
        LanguageDetectionHelper.detectQuick("Buongiorno") shouldBe "it"
        LanguageDetectionHelper.detectQuick("Hello") shouldBe "en"
        LanguageDetectionHelper.detectQuick("Good morning") shouldBe "en"
        LanguageDetectionHelper.detectQuick("Thank you") shouldBe "en"
    }

    test("detectQuick detects non-Latin scripts accurately") {
        LanguageDetectionHelper.detectQuick("Привет") shouldBe "ru"
        LanguageDetectionHelper.detectQuick("Привіт") shouldBe "uk"
        LanguageDetectionHelper.detectQuick("こんにちは") shouldBe "ja"
        LanguageDetectionHelper.detectQuick("你好") shouldBe "zh"
        LanguageDetectionHelper.detectQuick("안녕하세요") shouldBe "ko"
        LanguageDetectionHelper.detectQuick("مرحبا") shouldBe "ar"
        LanguageDetectionHelper.detectQuick("שלום") shouldBe "he"
        LanguageDetectionHelper.detectQuick("Γεια σας") shouldBe "el"
        LanguageDetectionHelper.detectQuick("สวัสดี") shouldBe "th"
        LanguageDetectionHelper.detectQuick("नमस्ते") shouldBe "hi"
    }

    test("detectQuick detects short French phrases with grammatical function words") {
        LanguageDetectionHelper.detectQuick("Ta mère est pas la") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("Ta mère est pas prête") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("sur le paradis") shouldBe "fr"
        LanguageDetectionHelper.detectQuick("Le parasol de tournepluie") shouldBe "fr"
    }

    test("selectBestLanguage penalizes unsupported obscure dialects and applies accent bonus") {
        val idToLanguage = arrayOf("bm", "fur", "fr", "en", "es", "sc")
        // fur has higher raw logit (12.17) than fr (11.26), but fur is an unsupported dialect
        val logits = floatArrayOf(5.0f, 12.17f, 11.26f, 4.0f, 3.0f, 5.0f)
        val selected = LanguageDetectionHelper.selectBestLanguage(
            logits,
            idToLanguage,
            "Trops de pluie sur le paradis",
        )
        selected shouldBe "fr"

        // French accent bonus should boost fr
        val accentedLogits = floatArrayOf(1.0f, 1.0f, 10.0f, 11.0f, 1.0f, 1.0f)
        val selectedAccented = LanguageDetectionHelper.selectBestLanguage(
            accentedLogits,
            idToLanguage,
            "Ta mère",
        )
        selectedAccented shouldBe "fr"
    }

    test("getQuickTranslation translates common greetings directly") {
        LanguageDetectionHelper.getQuickTranslation("Bonjour", "en") shouldBe "Hello"
        LanguageDetectionHelper.getQuickTranslation("bonjour", "en") shouldBe "hello"
        LanguageDetectionHelper.getQuickTranslation("Bonjour_", "en") shouldBe "Hello"
        LanguageDetectionHelper.getQuickTranslation("bonjour_", "en") shouldBe "hello"
        LanguageDetectionHelper.getQuickTranslation("Merci", "en") shouldBe "Thank you"
        LanguageDetectionHelper.getQuickTranslation("Hola", "en") shouldBe "Hello"
        LanguageDetectionHelper.getQuickTranslation("Danke", "en") shouldBe "Thank you"
        LanguageDetectionHelper.getQuickTranslation("Ciao", "en") shouldBe "Hello"
        LanguageDetectionHelper.getQuickTranslation("Unknown phrase", "en") shouldBe null
        LanguageDetectionHelper.getQuickTranslation("Bonjour", "es") shouldBe null
        LanguageDetectionHelper.getQuickTranslation("salut", "en") shouldBe "hi"
        LanguageDetectionHelper.getQuickTranslation("merci beaucoup", "en") shouldBe "thank you very much"
        LanguageDetectionHelper.getQuickTranslation("de rien", "en") shouldBe "you're welcome"
        LanguageDetectionHelper.getQuickTranslation("au revoir", "en") shouldBe "goodbye"
        LanguageDetectionHelper.getQuickTranslation("bonne nuit", "en") shouldBe "good night"
        LanguageDetectionHelper.getQuickTranslation("bonsoir", "en") shouldBe "good evening"
        LanguageDetectionHelper.getQuickTranslation("s'il vous plait", "en") shouldBe "please"
        LanguageDetectionHelper.getQuickTranslation("oui", "en") shouldBe "yes"
        LanguageDetectionHelper.getQuickTranslation("non", "en") shouldBe "no"
        LanguageDetectionHelper.getQuickTranslation("gracias", "en") shouldBe "thank you"
        LanguageDetectionHelper.getQuickTranslation("muchas gracias", "en") shouldBe "thank you very much"
        LanguageDetectionHelper.getQuickTranslation("de nada", "en") shouldBe "you're welcome"
        LanguageDetectionHelper.getQuickTranslation("por favor", "en") shouldBe "please"
        LanguageDetectionHelper.getQuickTranslation("adios", "en") shouldBe "goodbye"
        LanguageDetectionHelper.getQuickTranslation("buenas noches", "en") shouldBe "good night"
        LanguageDetectionHelper.getQuickTranslation("buenos dias", "en") shouldBe "good morning"
        LanguageDetectionHelper.getQuickTranslation("hallo", "en") shouldBe "hello"
        LanguageDetectionHelper.getQuickTranslation("vielen dank", "en") shouldBe "thank you very much"
        LanguageDetectionHelper.getQuickTranslation("bitte", "en") shouldBe "please"
        LanguageDetectionHelper.getQuickTranslation("tschuss", "en") shouldBe "bye"
        LanguageDetectionHelper.getQuickTranslation("auf wiedersehen", "en") shouldBe "goodbye"
        LanguageDetectionHelper.getQuickTranslation("grazie", "en") shouldBe "thank you"
        LanguageDetectionHelper.getQuickTranslation("s'il vous plaît", "en") shouldBe "please"
        LanguageDetectionHelper.getQuickTranslation("s'il te plait", "en") shouldBe "please"
        LanguageDetectionHelper.getQuickTranslation("s'il te plaît", "en") shouldBe "please"
        LanguageDetectionHelper.getQuickTranslation("adiós", "en") shouldBe "goodbye"
        LanguageDetectionHelper.getQuickTranslation("tschüss", "en") shouldBe "bye"
        LanguageDetectionHelper.getQuickTranslation("buenos días", "en") shouldBe "good morning"
    }

    test("detectQuick edge cases and script coverage") {
        LanguageDetectionHelper.detectQuick("") shouldBe null
        LanguageDetectionHelper.detectQuick("   ") shouldBe null
        LanguageDetectionHelper.detectQuick("abcdefgh") shouldBe null
        LanguageDetectionHelper.detectQuick("їжак") shouldBe "uk"
        LanguageDetectionHelper.detectQuick("єнот") shouldBe "uk"
        LanguageDetectionHelper.detectQuick("ґанок") shouldBe "uk"
    }

    test("selectBestLanguage handles empty inputs and accent bonuses for Spanish and German") {
        LanguageDetectionHelper.selectBestLanguage(floatArrayOf(), arrayOf("en"), "test") shouldBe null
        LanguageDetectionHelper.selectBestLanguage(floatArrayOf(1.0f), arrayOf(), "test") shouldBe null

        val idToLanguage = arrayOf("en", "es", "de")
        val spanishLogits = floatArrayOf(5.0f, 4.0f, 3.0f)
        val selectedEs = LanguageDetectionHelper.selectBestLanguage(spanishLogits, idToLanguage, "mañana")
        selectedEs shouldBe "es"

        val germanLogits = floatArrayOf(5.0f, 3.0f, 4.0f)
        val selectedDe = LanguageDetectionHelper.selectBestLanguage(germanLogits, idToLanguage, "schön")
        selectedDe shouldBe "de"
    }
})
