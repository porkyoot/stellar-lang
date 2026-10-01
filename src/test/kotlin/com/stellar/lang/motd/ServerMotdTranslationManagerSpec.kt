package com.stellar.lang.motd

import com.stellar.lang.service.TranslationCache
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component

class ServerMotdTranslationManagerSpec : FunSpec({
    beforeEach {
        ServerMotdTranslationManager.clearCache()
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.targetLanguage.setValue("fr", false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.hideIndicators.setValue(false, false)
    }

    test("processMotd skips null serverData or empty motd") {
        ServerMotdTranslationManager.processMotd(null)
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.empty()
        ServerMotdTranslationManager.processMotd(data)
        data.motd.string shouldBe ""
    }

    test("processMotd skips null motd") {
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        ServerData::class.java.getDeclaredField("motd").apply {
            isAccessible = true
            set(data, null)
        }
        ServerMotdTranslationManager.processMotd(data)
        ServerMotdTranslationManager.getOriginalMotd(data) shouldBe null
    }

    test("processMotd skips blank or short MOTD") {
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        val motd = Component.literal(" ")
        data.motd = motd
        ServerMotdTranslationManager.processMotd(data)
        data.motd shouldBe motd
    }

    test("processMotd skips when mod is disabled") {
        TranslationService.getConfig().enabled.setValue(false, false)
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        val motd = Component.literal("Welcome to our server!")
        data.motd = motd
        ServerMotdTranslationManager.processMotd(data)
        data.motd shouldBe motd
    }

    test("processMotd applies cached translation with badge") {
        val original = "Welcome to our server!"
        val result = TranslationResult(original, "Bienvenue sur notre serveur !", "en", "fr", false)
        TranslationService.putCache(result)

        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.literal(original)

        ServerMotdTranslationManager.processMotd(data)
        data.motd.string shouldContain "[T] "
        data.motd.string shouldContain "Bienvenue sur notre serveur !"
        ServerMotdTranslationManager.getOriginalMotd(data)?.string shouldBe original
    }

    test("processMotd applies cached translation without badge when hideIndicators is true") {
        TranslationService.getConfig().hideIndicators.setValue(true, false)
        val original = "Welcome to our server!"
        val result = TranslationResult(original, "Bienvenue sur notre serveur !", "en", "fr", false)
        TranslationService.putCache(result)

        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.literal(original)

        ServerMotdTranslationManager.processMotd(data)
        data.motd.string shouldBe "Bienvenue sur notre serveur !"
    }

    test("processMotd skips same-language translation") {
        val original = "Bonjour le serveur"
        val result = TranslationResult(original, original, "fr", "fr", true)
        TranslationService.putCache(result)

        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        val motd = Component.literal(original)
        data.motd = motd

        ServerMotdTranslationManager.processMotd(data)
        data.motd shouldBe motd
    }

    test("processMotd triggers async translation when not cached") {
        val text = "Exciting new survival world!"
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.literal(text)

        ServerMotdTranslationManager.processMotd(data)

        val serviceKey = TranslationService.cacheKey(text, "fr")
        val result = TranslationResult(text, "Monde de survie passionnant !", "en", "fr", false)
        TranslationCache.completeInFlight(serviceKey, result)

        data.motd.string shouldContain "Monde de survie passionnant !"
    }

    test("processMotd skips when already translated") {
        val original = "Welcome to our server!"
        val result = TranslationResult(original, "Bienvenue sur notre serveur !", "en", "fr", false)
        TranslationService.putCache(result)

        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.literal(original)

        ServerMotdTranslationManager.processMotd(data)
        val translated = data.motd
        ServerMotdTranslationManager.processMotd(data)
        data.motd shouldBe translated
    }

    test("processMotd preserves previous original when rawText matches") {
        val original = "Welcome to our server!"
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.literal(original)

        ServerMotdTranslationManager.processMotd(data)
        ServerMotdTranslationManager.getOriginalMotd(data)?.string shouldBe original

        data.motd = Component.literal(original)
        ServerMotdTranslationManager.processMotd(data)
        ServerMotdTranslationManager.getOriginalMotd(data)?.string shouldBe original
    }

    test("processMotd prevents duplicate in-flight async translations") {
        val text = "Unique async world text"
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.literal(text)

        ServerMotdTranslationManager.processMotd(data)
        ServerMotdTranslationManager.processMotd(data)

        val serviceKey = TranslationService.cacheKey(text, "fr")
        val result = TranslationResult(text, "Texte unique", "en", "fr", false)
        TranslationCache.completeInFlight(serviceKey, result)

        data.motd.string shouldContain "Texte unique"
    }

    test("processMotd handles async translation failure or same language") {
        val failText = "Failure server MOTD"
        val data = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        data.motd = Component.literal(failText)

        ServerMotdTranslationManager.processMotd(data)
        val failKey = TranslationService.cacheKey(failText, "fr")
        TranslationCache.completeInFlight(failKey, null)
        data.motd.string shouldBe failText

        val sameText = "French server MOTD"
        val sameData = ServerData("Test2", "127.0.0.2", ServerData.Type.OTHER)
        sameData.motd = Component.literal(sameText)

        ServerMotdTranslationManager.processMotd(sameData)
        val sameKey = TranslationService.cacheKey(sameText, "fr")
        val sameResult = TranslationResult(sameText, sameText, "fr", "fr", true)
        TranslationCache.completeInFlight(sameKey, sameResult)
        sameData.motd.string shouldBe sameText
    }

    test("processMotd falls back to serverData.motd when motdField is null") {
        val origField = ServerMotdTranslationManager.motdField
        try {
            ServerMotdTranslationManager.motdField = null
            val data = ServerData("TestFallback", "127.0.0.1", ServerData.Type.OTHER)
            data.motd = Component.literal("Fallback MOTD content")
            ServerMotdTranslationManager.processMotd(data)
            ServerMotdTranslationManager.getOriginalMotd(data)?.string shouldBe "Fallback MOTD content"
        } finally {
            ServerMotdTranslationManager.motdField = origField
        }
    }
})
