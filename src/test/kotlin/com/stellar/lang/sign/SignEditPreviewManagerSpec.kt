package com.stellar.lang.sign

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SignEditPreviewManagerSpec : FunSpec({
    beforeEach {
        SignEditPreviewManager.clear()
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.translateSigns.setValue(true, false)
        config.targetLanguage.setValue("en", false)
    }

    test("updateRealtimeTranslation handles empty and blank input") {
        SignEditPreviewManager.updateRealtimeTranslation("") shouldBe ""
        SignEditPreviewManager.updateRealtimeTranslation("   ") shouldBe ""
        SignEditPreviewManager.lastRequestedText shouldBe ""
    }

    test("updateRealtimeTranslation returns cached translation immediately") {
        val fakeResult = TranslationResult("Bonjour", "Hello", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        val result = SignEditPreviewManager.updateRealtimeTranslation("Bonjour")
        result shouldBe "Hello"
        SignEditPreviewManager.currentTranslatedText shouldBe "Hello"
        SignEditPreviewManager.lastRequestedText shouldBe "Bonjour"
    }

    test("updateRealtimeTranslation debounces uncached input before dispatching async") {
        var serverReceived: String? = null
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/translate") { exchange ->
            serverReceived = "hit"
            val body = """{"translatedText": "Welcome Traveler", "detectedLanguage": "fr"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()

        try {
            val config = TranslationService.getConfig()
            config.apiHost.setValue("http://127.0.0.1:$port", false)

            // First call sets lastRequestTime
            val initial = SignEditPreviewManager.updateRealtimeTranslation("Bienvenue Voyageur")
            initial shouldBe ""
            serverReceived shouldBe null

            // Calling immediately before debounce period should not trigger
            SignEditPreviewManager.updateRealtimeTranslation("Bienvenue Voyageur")
            serverReceived shouldBe null

            // Fast-forward lastRequestTime to exceed debounce period
            SignEditPreviewManager.lastRequestTime = System.currentTimeMillis() - 300L
            SignEditPreviewManager.updateRealtimeTranslation("Bienvenue Voyageur")

            // Wait for async response
            var attempts = 0
            while (attempts++ < 30 && SignEditPreviewManager.currentTranslatedText.isEmpty()) {
                Thread.sleep(50)
            }
            SignEditPreviewManager.currentTranslatedText shouldBe "Welcome Traveler"
        } finally {
            server.stop(0)
        }
    }

    test("updateRealtimeTranslation sets hasFailed when translation fails") {
        val config = TranslationService.getConfig()
        config.apiHost.setValue("http://127.0.0.1:1", false) // Unreachable port

        SignEditPreviewManager.updateRealtimeTranslation("Bonjour le monde")
        SignEditPreviewManager.lastRequestTime = System.currentTimeMillis() - 300L
        SignEditPreviewManager.updateRealtimeTranslation("Bonjour le monde")

        var attempts = 0
        while (attempts++ < 30 && SignEditPreviewManager.isTranslating.get()) {
            Thread.sleep(50)
        }

        SignEditPreviewManager.hasFailed.get() shouldBe true
        SignEditPreviewManager.currentTranslatedText shouldBe ""

        // Calling again while in failed state should set hasFailed from TranslationService.isFailed
        SignEditPreviewManager.hasFailed.set(false)
        SignEditPreviewManager.updateRealtimeTranslation("Bonjour le monde")
        SignEditPreviewManager.hasFailed.get() shouldBe true

        // Calling after cooldown should trigger force retry
        SignEditPreviewManager.lastRequestTime = System.currentTimeMillis() - 6_000L
        SignEditPreviewManager.updateRealtimeTranslation("Bonjour le monde")
        SignEditPreviewManager.isTranslating.get() shouldBe true
    }

    test("clear resets preview state including hasFailed") {
        SignEditPreviewManager.lastRequestedText = "Some Text"
        SignEditPreviewManager.lastRequestTime = 12_345L
        SignEditPreviewManager.currentTranslatedText = "Translated"
        SignEditPreviewManager.isTranslating.set(true)
        SignEditPreviewManager.hasFailed.set(true)

        SignEditPreviewManager.clear()

        SignEditPreviewManager.lastRequestedText shouldBe ""
        SignEditPreviewManager.lastRequestTime shouldBe 0L
        SignEditPreviewManager.currentTranslatedText shouldBe ""
        SignEditPreviewManager.isTranslating.get() shouldBe false
        SignEditPreviewManager.hasFailed.get() shouldBe false
    }
})
