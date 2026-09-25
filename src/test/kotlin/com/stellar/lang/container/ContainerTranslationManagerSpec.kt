package com.stellar.lang.container

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationCache
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import sun.misc.Unsafe

@Suppress("LargeClass")
class ContainerTranslationManagerSpec : FunSpec({
    val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null) as Unsafe
    }

    beforeSpec {
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()
    }

    beforeEach {
        ContainerTranslationManager.clearCache()
        TranslationService.clearCache()
        StellarLangInputHandler.clearProviders()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateContainers.setValue(true, false)
        config.targetLanguage.setValue("en", false)
    }

    test("isRenamed accurately differentiates vanilla translatable keys from custom names") {
        ContainerTranslationManager.isRenamed(Component.translatable("container.chest")) shouldBe false
        ContainerTranslationManager.isRenamed(Component.translatable("container.inventory")) shouldBe false
        ContainerTranslationManager.isRenamed(Component.translatable("container.barrel")) shouldBe false

        // Empty / blank components
        ContainerTranslationManager.isRenamed(Component.literal("")) shouldBe false
        ContainerTranslationManager.isRenamed(Component.literal("   ")) shouldBe false

        // Wrapped translatable component
        val wrappedTranslatable = Component.empty().append(Component.translatable("container.chest"))
        ContainerTranslationManager.isRenamed(wrappedTranslatable) shouldBe false

        // Player-renamed literal components
        ContainerTranslationManager.isRenamed(Component.literal("Coffre secret")) shouldBe true
        ContainerTranslationManager.isRenamed(Component.literal("Treasures")) shouldBe true

        // Wrapped literal component
        val wrappedLiteral = Component.empty().append(Component.literal("Coffre aux diamants"))
        ContainerTranslationManager.isRenamed(wrappedLiteral) shouldBe true
    }

    test("translateLabel returns original when feature is disabled or showing original") {
        val config = TranslationService.getConfig()
        val renamed = Component.literal("Coffre secret")

        config.enabled.setValue(false, false)
        ContainerTranslationManager.translateLabel(renamed) shouldBe renamed

        config.enabled.setValue(true, false)
        config.translateContainers.setValue(false, false)
        ContainerTranslationManager.translateLabel(renamed) shouldBe renamed

        config.translateContainers.setValue(true, false)
        StellarLangInputHandler.keyStateProvider = { true }
        ContainerTranslationManager.translateLabel(renamed) shouldBe renamed
        StellarLangInputHandler.keyStateProvider = null
    }

    test("translateLabel returns original for non-renamed or short/badged labels") {
        val defaultChest = Component.translatable("container.chest")
        ContainerTranslationManager.translateLabel(defaultChest) shouldBe defaultChest

        val defaultInventory = Component.translatable("container.inventory")
        ContainerTranslationManager.translateLabel(defaultInventory) shouldBe defaultInventory

        val shortName = Component.literal("X")
        ContainerTranslationManager.translateLabel(shortName) shouldBe shortName

        val badgedName = Component.literal("[T] Already Translated")
        ContainerTranslationManager.translateLabel(badgedName) shouldBe badgedName

        val translatingName = Component.literal("[...] In Progress")
        ContainerTranslationManager.translateLabel(translatingName) shouldBe translatingName
    }

    test("translateLabel returns original when label is in the target language") {
        val englishLabel = Component.literal("Diamond Storage")
        val targetLang = "en"

        // Seed TranslationService with same-language result
        TranslationService.putCache(
            TranslationResult(
                originalText = "Diamond Storage",
                translatedText = "Diamond Storage",
                detectedLanguage = targetLang,
                targetLanguage = targetLang,
                isSameLanguage = true,
            ),
        )

        val result = ContainerTranslationManager.translateLabel(englishLabel)
        result shouldBe englishLabel
        result.string shouldBe "Diamond Storage"
        result.string shouldNotBe "[T] Diamond Storage"
    }

    test("translateLabel translates renamed container when not in target language") {
        val frenchLabel = Component.literal("Coffre aux trésors").withStyle(ChatFormatting.GOLD)
        val targetLang = "en"

        TranslationService.putCache(
            TranslationResult(
                originalText = "Coffre aux trésors",
                translatedText = "Treasure Chest",
                detectedLanguage = "fr",
                targetLanguage = targetLang,
                isSameLanguage = false,
            ),
        )

        val result = ContainerTranslationManager.translateLabel(frenchLabel)
        result.string shouldContain "[T]"
        result.string shouldContain "Treasure Chest"
        result.siblings.last().style shouldBe frenchLabel.style
    }

    test("onTranslationSuccess handles sameLanguage and nonSameLanguage results") {
        val sameLangResult = TranslationResult(
            originalText = "Weapons",
            translatedText = "Weapons",
            detectedLanguage = "en",
            targetLanguage = "en",
            isSameLanguage = true,
        )
        ContainerTranslationManager.onTranslationSuccess(sameLangResult)
        val sameKey = "en::${"Weapons".hashCode()}"
        ContainerTranslationManager.sameLanguageContainers.contains(sameKey) shouldBe true
        ContainerTranslationManager.containerCache.containsKey(sameKey) shouldBe false

        val diffLangResult = TranslationResult(
            originalText = "Armes",
            translatedText = "Weapons",
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        ContainerTranslationManager.onTranslationSuccess(diffLangResult)
        val diffKey = "en::${"Armes".hashCode()}"
        ContainerTranslationManager.sameLanguageContainers.contains(diffKey) shouldBe false
        ContainerTranslationManager.containerCache.containsKey(diffKey) shouldBe true
        ContainerTranslationManager.containerCache[diffKey]?.string shouldContain "Weapons"
    }

    test("translateLabel returns in-flight translating name when translation is queued") {
        val text = "Magasin de potions"
        val label = Component.literal(text)
        val targetLang = "en"
        val key = TranslationService.cacheKey(text, targetLang)

        com.stellar.lang.service.TranslationCache.queueInFlight(key) {}

        val result = ContainerTranslationManager.translateLabel(label)
        result.string shouldContain "[...]"
        result.string shouldContain text

        com.stellar.lang.service.TranslationCache.completeInFlight(key, null)
    }

    test("translateLabel handles failed translation and retry cooldown") {
        val text = "Coffre maudit"
        val label = Component.literal(text)
        val targetLang = "en"
        val cacheKey = "$targetLang::${text.hashCode()}"

        val lastRetryField = ContainerTranslationManager::class.java.getDeclaredField("lastContainerRetryTimes")
        lastRetryField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = lastRetryField.get(ContainerTranslationManager) as MutableMap<String, Long>
        map[cacheKey] = System.currentTimeMillis()

        ContainerTranslationManager.failedContainers.add(cacheKey)

        val failedResult = ContainerTranslationManager.translateLabel(label)
        failedResult.string shouldContain "[T]"
        failedResult.string shouldContain text

        // Trigger retry cooldown expiry
        map[cacheKey] = System.currentTimeMillis() - 10_000L

        val retryingResult = ContainerTranslationManager.translateLabel(label)
        retryingResult.string shouldContain "[...]"
    }

    test("refreshContainer flushes cache and requests translation") {
        val text = "Coffre de bois"
        val label = Component.literal(text)
        val targetLang = "en"
        val cacheKey = "$targetLang::${text.hashCode()}"

        ContainerTranslationManager.containerCache[cacheKey] = Component.literal("Old")
        ContainerTranslationManager.failedContainers.add(cacheKey)
        ContainerTranslationManager.sameLanguageContainers.add(cacheKey)

        val refreshed = ContainerTranslationManager.refreshContainer(label)
        refreshed shouldBe true

        ContainerTranslationManager.containerCache.containsKey(cacheKey) shouldBe false
        ContainerTranslationManager.failedContainers.contains(cacheKey) shouldBe false
        ContainerTranslationManager.sameLanguageContainers.contains(cacheKey) shouldBe false

        // Invalid component
        ContainerTranslationManager.refreshContainer(Component.literal("")) shouldBe false
    }

    test("refreshScreen refreshes when title is renamed") {
        class TestScreen : AbstractContainerScreen<AbstractContainerMenu>(
            null as Any as AbstractContainerMenu,
            null as Any as net.minecraft.world.entity.player.Inventory,
            Component.empty(),
        )
        val screen = unsafe.allocateInstance(TestScreen::class.java) as AbstractContainerScreen<AbstractContainerMenu>
        val titleField = net.minecraft.client.gui.screens.Screen::class.java.getDeclaredField("title")
        titleField.isAccessible = true

        titleField.set(screen, Component.literal("Coffre spécial"))
        ContainerTranslationManager.refreshScreen(screen) shouldBe true

        titleField.set(screen, Component.translatable("container.chest"))
        ContainerTranslationManager.refreshScreen(screen) shouldBe false
    }

    test("refreshBlockEntity refreshes when custom name is present") {
        val blockEntity = unsafe.allocateInstance(
            net.minecraft.world.level.block.entity.ChestBlockEntity::class.java,
        ) as BaseContainerBlockEntity
        val nameField = BaseContainerBlockEntity::class.java.getDeclaredField("name")
        nameField.isAccessible = true

        nameField.set(blockEntity, Component.literal("Coffre secret"))
        ContainerTranslationManager.refreshBlockEntity(blockEntity) shouldBe true

        nameField.set(blockEntity, null)
        ContainerTranslationManager.refreshBlockEntity(blockEntity) shouldBe false

        nameField.set(blockEntity, Component.translatable("container.chest"))
        ContainerTranslationManager.refreshBlockEntity(blockEntity) shouldBe false
    }

    test("getValidCachedContainer handles valid, failed-and-cached, and recovered states") {
        val text = "Coffre valide"
        val targetLang = "en"
        val cacheKey = "$targetLang::${text.hashCode()}"
        val key = TranslationService.cacheKey(text, targetLang)

        // 1. Valid cached item not failed
        val validComp = Component.literal("[T] Valid Chest")
        ContainerTranslationManager.containerCache[cacheKey] = validComp
        ContainerTranslationManager.translateLabel(Component.literal(text)) shouldBe validComp

        // 2. Failed cached item while TranslationService still marks it failed
        TranslationCache.markFailed(key)
        ContainerTranslationManager.failedContainers.add(cacheKey)
        ContainerTranslationManager.translateLabel(Component.literal(text)) shouldBe validComp

        // 3. Failed cached item when TranslationService has recovered (no longer failed)
        TranslationCache.removeFailed(key)
        val recovered = ContainerTranslationManager.translateLabel(Component.literal(text))
        recovered.string shouldBe text
    }

    test("resolveContainerTranslation callbacks handle success, same-language, and failure") {
        val text = "Coffre async"
        val targetLang = "en"
        val key = TranslationService.cacheKey(text, targetLang)
        val cacheKey = "$targetLang::${text.hashCode()}"

        // 1. Non-same-language success callback
        ContainerTranslationManager.translateLabel(Component.literal(text))
        val successResult = TranslationResult(text, "Async Chest", "fr", targetLang, false)
        TranslationCache.completeInFlight(key, successResult)
        ContainerTranslationManager.containerCache[cacheKey]?.string shouldContain "Async Chest"

        // 2. Same-language success callback
        ContainerTranslationManager.clearCache()
        val textSame = "Same async"
        val keySame = TranslationService.cacheKey(textSame, targetLang)
        val cacheKeySame = "$targetLang::${textSame.hashCode()}"
        ContainerTranslationManager.translateLabel(Component.literal(textSame))
        val sameResult = TranslationResult(textSame, textSame, "en", targetLang, true)
        TranslationCache.completeInFlight(keySame, sameResult)
        ContainerTranslationManager.sameLanguageContainers.contains(cacheKeySame) shouldBe true

        // 3. Failure callback
        ContainerTranslationManager.clearCache()
        val textFail = "Fail async"
        val keyFail = TranslationService.cacheKey(textFail, targetLang)
        val cacheKeyFail = "$targetLang::${textFail.hashCode()}"
        ContainerTranslationManager.translateLabel(Component.literal(textFail))
        TranslationCache.markFailed(keyFail)
        TranslationCache.completeInFlight(keyFail, null)
        ContainerTranslationManager.failedContainers.contains(cacheKeyFail) shouldBe true
        ContainerTranslationManager.containerCache[cacheKeyFail]?.string shouldContain "[T]"
    }

    test("handleRetryOrFailed and refreshContainer callbacks handle all branches") {
        val text = "Coffre retry cb"
        val targetLang = "en"
        val key = TranslationService.cacheKey(text, targetLang)
        val cacheKey = "$targetLang::${text.hashCode()}"

        val lastRetryField = ContainerTranslationManager::class.java.getDeclaredField("lastContainerRetryTimes")
        lastRetryField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = lastRetryField.get(ContainerTranslationManager) as MutableMap<String, Long>

        // Retry success
        map[cacheKey] = System.currentTimeMillis() - 10_000L
        ContainerTranslationManager.failedContainers.add(cacheKey)
        ContainerTranslationManager.translateLabel(Component.literal(text))
        val successResult = TranslationResult(text, "Retried Chest", "fr", targetLang, false)
        TranslationCache.completeInFlight(key, successResult)
        ContainerTranslationManager.containerCache[cacheKey]?.string shouldContain "Retried Chest"

        // Retry same language
        ContainerTranslationManager.clearCache()
        map[cacheKey] = System.currentTimeMillis() - 10_000L
        ContainerTranslationManager.failedContainers.add(cacheKey)
        ContainerTranslationManager.translateLabel(Component.literal(text))
        val sameResult = TranslationResult(text, text, "en", targetLang, true)
        TranslationCache.completeInFlight(key, sameResult)
        ContainerTranslationManager.sameLanguageContainers.contains(cacheKey) shouldBe true

        // Retry fail
        ContainerTranslationManager.clearCache()
        map[cacheKey] = System.currentTimeMillis() - 10_000L
        ContainerTranslationManager.failedContainers.add(cacheKey)
        ContainerTranslationManager.translateLabel(Component.literal(text))
        TranslationCache.markFailed(key)
        TranslationCache.completeInFlight(key, null)
        ContainerTranslationManager.failedContainers.contains(cacheKey) shouldBe true

        // refreshContainer same language
        ContainerTranslationManager.clearCache()
        ContainerTranslationManager.refreshContainer(text)
        TranslationCache.completeInFlight(key, sameResult)
        ContainerTranslationManager.sameLanguageContainers.contains(cacheKey) shouldBe true

        // refreshContainer failure
        ContainerTranslationManager.clearCache()
        ContainerTranslationManager.refreshContainer(text)
        TranslationCache.completeInFlight(key, null)
        ContainerTranslationManager.failedContainers.contains(cacheKey) shouldBe true
    }
})
