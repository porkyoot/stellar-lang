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

    test("clear resets preview state") {
        SignEditPreviewManager.lastRequestedText = "Some Text"
        SignEditPreviewManager.lastRequestTime = 12_345L
        SignEditPreviewManager.currentTranslatedText = "Translated"
        SignEditPreviewManager.isTranslating.set(true)

        SignEditPreviewManager.clear()

        SignEditPreviewManager.lastRequestedText shouldBe ""
        SignEditPreviewManager.lastRequestTime shouldBe 0L
        SignEditPreviewManager.currentTranslatedText shouldBe ""
        SignEditPreviewManager.isTranslating.get() shouldBe false
    }
})
