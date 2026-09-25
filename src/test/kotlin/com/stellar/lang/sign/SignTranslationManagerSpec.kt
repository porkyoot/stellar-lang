package com.stellar.lang.sign

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignText

class SignTranslationManagerSpec : FunSpec({
    beforeEach {
        SignTranslationManager.clearCache()
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = null
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateSigns.setValue(true, false)
        config.targetLanguage.setValue("en", false)
    }

    test("wrapToSignLines wraps long sentences within 15 chars and 4 lines max") {
        val method = SignTranslationManager::class.java.getDeclaredMethod("wrapToSignLines", String::class.java)
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val shortResult = method.invoke(SignTranslationManager, "Shop Here") as List<String>
        shortResult.size shouldBe 1
        shortResult[0] shouldBe "Shop Here"

        @Suppress("UNCHECKED_CAST")
        val longResult = method.invoke(
            SignTranslationManager,
            "Welcome to the magnificent castle and palace of our kingdom",
        ) as List<String>
        longResult.size shouldBe 4
        longResult.forEach { line ->
            (line.length <= 15) shouldBe true
        }
    }

    test("extractSentence and applyTranslatedLines format SignText accurately") {
        var signText = SignText()
        signText = signText.setMessage(0, Component.literal("Line 1"))
        signText = signText.setMessage(1, Component.literal("Line 2"))
        signText = signText.setMessage(2, Component.literal(""))
        signText = signText.setMessage(3, Component.literal("Line 4"))

        val extractMethod = SignTranslationManager::class.java
            .getDeclaredMethod("extractSentence", SignText::class.java)
        extractMethod.isAccessible = true
        val extracted = extractMethod.invoke(SignTranslationManager, signText) as String
        extracted shouldBe "Line 1 Line 2 Line 4"

        val applyMethod = SignTranslationManager::class.java.getDeclaredMethod(
            "applyTranslatedLines",
            SignText::class.java,
            String::class.java,
        )
        applyMethod.isAccessible = true
        val applied = applyMethod.invoke(SignTranslationManager, signText, "Welcome travelers to town") as SignText
        applied.getMessage(0, false).string shouldBe "Welcome"
        applied.getMessage(1, false).string shouldBe "travelers to"
        applied.getMessage(2, false).string shouldBe "town"
    }

    test("sign cache retrieval returns cached SignText") {
        val cacheField = SignTranslationManager::class.java.getDeclaredField("signCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(SignTranslationManager) as java.util.concurrent.ConcurrentHashMap<String, SignText>

        val dummySignText = SignText().setMessage(0, Component.literal("Cached Sign"))
        cache["test_key"] = dummySignText
        cache["test_key"] shouldBe dummySignText
    }

    test("getOrRequestTranslatedSignText handles disabled, uncached, and cached states") {
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val sign = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity

        val posField = net.minecraft.world.level.block.entity.BlockEntity::class.java.getDeclaredField("worldPosition")
        posField.isAccessible = true
        posField.set(sign, BlockPos(10, 20, 30))

        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        var frontSignText = SignText()
        frontSignText = frontSignText.setMessage(0, Component.literal("Bienvenue"))
        frontTextField.set(sign, frontSignText)

        val backTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("backText")
        backTextField.isAccessible = true
        var backSignText = SignText()
        backSignText = backSignText.setMessage(0, Component.literal("Sortie"))
        backTextField.set(sign, backSignText)

        val config = TranslationService.getConfig()
        config.enabled.setValue(false, false)
        SignTranslationManager.getOrRequestTranslatedSignText(sign, true) shouldBe null

        config.enabled.setValue(true, false)
        config.translateSigns.setValue(false, false)
        SignTranslationManager.getOrRequestTranslatedSignText(sign, true) shouldBe null

        config.translateSigns.setValue(true, false)

        // Seed TranslationService cache for "Bienvenue"
        val fakeResult = TranslationResult("Bienvenue", "Welcome", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        // First call triggers dispatch
        SignTranslationManager.getOrRequestTranslatedSignText(sign, true)
        // May be null initially while dispatch completes
        val secondResult = SignTranslationManager.getOrRequestTranslatedSignText(sign, true)
        secondResult shouldNotBe null
        secondResult!!.getMessage(0, false).string shouldBe "Welcome"

        // Also test backText
        val fakeBack = TranslationResult("Sortie", "Exit", "fr", "en", false)
        TranslationService.putCache(fakeBack)
        SignTranslationManager.getOrRequestTranslatedSignText(sign, false)
        val backResult = SignTranslationManager.getOrRequestTranslatedSignText(sign, false)
        backResult shouldNotBe null
        backResult!!.getMessage(0, false).string shouldBe "Exit"
    }

    test("signs translate independently without contamination from nearby signs") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val sign1 = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity
        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        frontTextField.set(sign1, SignText().setMessage(0, Component.literal("Chateau Royal")))

        val sign2 = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity
        frontTextField.set(sign2, SignText().setMessage(0, Component.literal("Boulangerie")))

        val backTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("backText")
        backTextField.isAccessible = true
        backTextField.set(sign1, SignText())
        backTextField.set(sign2, SignText())

        val fakeChateau = TranslationResult("Chateau Royal", "Royal Castle", "fr", "en", false)
        val fakeBoulangerie = TranslationResult("Boulangerie", "Bakery", "fr", "en", false)
        TranslationService.putCache(fakeChateau)
        TranslationService.putCache(fakeBoulangerie)

        SignTranslationManager.onSignLoaded(sign1)
        SignTranslationManager.onSignLoaded(sign2)

        val trans1 = SignTranslationManager.getOrRequestTranslatedSignText(sign1, true)
        val trans2 = SignTranslationManager.getOrRequestTranslatedSignText(sign2, true)

        trans1 shouldNotBe null
        trans2 shouldNotBe null
        trans1!!.getMessage(0, false).string shouldBe "Royal Castle"
        trans2!!.getMessage(0, false).string shouldBe "Bakery"
    }

    test("isFailed returns true when translation fails") {
        var signText = SignText()
        signText = signText.setMessage(0, Component.literal("Inconnu"))

        SignTranslationManager.isFailed(signText) shouldBe false

        val config = TranslationService.getConfig()
        config.apiHost.setValue("http://127.0.0.1:1", false)

        SignTranslationManager.translateSignText(signText)

        var attempts = 0
        while (attempts++ < 30 && !SignTranslationManager.isFailed(signText)) {
            Thread.sleep(50)
        }

        SignTranslationManager.isFailed(signText) shouldBe true

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val sign = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity
        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        frontTextField.set(sign, signText)
        val backTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("backText")
        backTextField.isAccessible = true
        backTextField.set(sign, SignText())

        SignTranslationManager.isFailed(sign, isFront = true) shouldBe true
        SignTranslationManager.isFailed(sign, isFront = false) shouldBe false
    }

    test("isTranslating, getExcessText and getFullTranslation handle in-flight and empty signs") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val sign = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity

        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        var frontSignText = SignText()
        frontSignText = frontSignText.setMessage(0, Component.literal("En route"))
        frontTextField.set(sign, frontSignText)

        val backTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("backText")
        backTextField.isAccessible = true
        backTextField.set(sign, SignText())

        val targetLang = TranslationService.getTargetLanguage()
        val key = com.stellar.lang.service.TranslationCache.cacheKey("En route", targetLang)

        SignTranslationManager.isTranslating(sign, isFront = true) shouldBe false
        SignTranslationManager.isTranslating(sign, isFront = false) shouldBe false
        SignTranslationManager.isTranslating(SignText()) shouldBe false

        com.stellar.lang.service.TranslationCache.queueInFlight(key) {}
        SignTranslationManager.isTranslating(sign, isFront = true) shouldBe true
        SignTranslationManager.isTranslating(sign, isFront = false) shouldBe false
        SignTranslationManager.isTranslating(frontSignText) shouldBe true

        com.stellar.lang.service.TranslationCache.completeInFlight(key, null)
        SignTranslationManager.isTranslating(sign, isFront = true) shouldBe false

        SignTranslationManager.getExcessText(sign, isFront = true) shouldBe null
        SignTranslationManager.getExcessText(sign, isFront = false) shouldBe null
        SignTranslationManager.getFullTranslation(sign, isFront = true) shouldBe null
        SignTranslationManager.getFullTranslation(sign, isFront = false) shouldBe null
    }
})
