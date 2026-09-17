package com.stellar.lang.sign

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignText

class SignTranslationManagerSpec : FunSpec({
    beforeEach {
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
        shortResult[0] shouldBe "[T] Shop Here"

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

    test("extractTargetSentence handles contextual area queries with pipe delimiters") {
        val method = SignTranslationManager::class.java.getDeclaredMethod(
            "extractTargetSentence",
            String::class.java,
            Boolean::class.java,
        )
        method.isAccessible = true

        val withContext = method.invoke(
            SignTranslationManager,
            "Welcome | Market Place | Bakery Fresh Bread",
            true,
        ) as String
        withContext shouldBe "Bakery Fresh Bread"

        val withoutContext = method.invoke(
            SignTranslationManager,
            "Only This Sign Text",
            false,
        ) as String
        withoutContext shouldBe "Only This Sign Text"

        val noPipeWithPrefix = method.invoke(
            SignTranslationManager,
            "No Pipe Here",
            true,
        ) as String
        noPipeWithPrefix shouldBe "No Pipe Here"
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
        applied.getMessage(0, false).string shouldBe "[T] Welcome"
        applied.getMessage(1, false).string shouldBe "travelers to"
        applied.getMessage(2, false).string shouldBe "town"
    }

    test("sign cache retrieval returns cached SignText") {
        val cacheField = SignTranslationManager::class.java.getDeclaredField("signCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(SignTranslationManager) as java.util.concurrent.ConcurrentHashMap<String, SignText>

        val dummySignText = SignText().setMessage(0, Component.literal("[T] Cached Sign"))
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
        secondResult!!.getMessage(0, false).string shouldBe "[T] Welcome"

        // Also test backText
        val fakeBack = TranslationResult("Sortie", "Exit", "fr", "en", false)
        TranslationService.putCache(fakeBack)
        SignTranslationManager.getOrRequestTranslatedSignText(sign, false)
        val backResult = SignTranslationManager.getOrRequestTranslatedSignText(sign, false)
        backResult shouldNotBe null
        backResult!!.getMessage(0, false).string shouldBe "[T] Exit"
    }

    test("dispatchSignTranslation uses nearbyContextProvider and joins context") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val sign = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity

        val posField = net.minecraft.world.level.block.entity.BlockEntity::class.java.getDeclaredField("worldPosition")
        posField.isAccessible = true
        posField.set(sign, BlockPos(100, 64, 100))

        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        var frontSignText = SignText()
        frontSignText = frontSignText.setMessage(0, Component.literal("Chateau Royal"))
        frontTextField.set(sign, frontSignText)

        // Set nearbyContextProvider
        SignTranslationManager.nearbyContextProvider = { "Grand Place" }

        val fakeFullResult = TranslationResult(
            originalText = "Grand Place | Chateau Royal",
            translatedText = "Main Square | Royal Castle",
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        TranslationService.putCache(fakeFullResult)

        SignTranslationManager.getOrRequestTranslatedSignText(sign, true)
        val result = requireNotNull(SignTranslationManager.getOrRequestTranslatedSignText(sign, true))
        result.getMessage(0, false).string shouldBe "[T] Royal"
        result.getMessage(1, false).string shouldBe "Castle"

        SignTranslationManager.nearbyContextProvider = null
    }

    test("gatherNearbySignContextWithGetter collects nearby signs in area") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val centerPos = BlockPos(0, 64, 0)
        val neighborPos1 = BlockPos(1, 64, 0)
        val neighborPos2 = BlockPos(-1, 64, 0)

        val neighborSign1 = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity
        val frontTextField = net.minecraft.world.level.block.entity.SignBlockEntity::class.java
            .getDeclaredField("frontText")
        frontTextField.isAccessible = true
        frontTextField.set(neighborSign1, SignText().setMessage(0, Component.literal("Taverne")))

        val neighborSign2 = unsafe.allocateInstance(net.minecraft.world.level.block.entity.SignBlockEntity::class.java)
            as net.minecraft.world.level.block.entity.SignBlockEntity
        frontTextField.set(neighborSign2, SignText().setMessage(0, Component.literal("Forge")))

        val context = SignTranslationManager.gatherNearbySignContextWithGetter({ pos ->
            when (pos) {
                neighborPos1 -> neighborSign1
                neighborPos2 -> neighborSign2
                else -> null
            }
        }, centerPos)

        context shouldContain "Taverne"
        context shouldContain "Forge"
    }
})
