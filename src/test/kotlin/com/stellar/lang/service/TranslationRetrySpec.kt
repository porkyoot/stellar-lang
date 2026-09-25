package com.stellar.lang.service

import com.stellar.lang.chat.ChatTranslationManager
import com.stellar.lang.entity.EntityTranslationManager
import com.stellar.lang.item.ItemTranslationManager
import com.stellar.lang.sign.SignTranslationManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignText
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("LargeClass")
class TranslationRetrySpec : FunSpec({
    beforeEach {
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.targetLanguage.setValue("en", false)
        TranslationService.languageProvider = null
        TranslationService.clearCache()
        SignTranslationManager.clearCache()
        ItemTranslationManager.clearCache()
        EntityTranslationManager.clearCache()
        ChatTranslationManager.clearCache()
    }

    test("TranslationCache getFailedKeys and removeFailed work correctly") {
        TranslationCache.markFailed("en::test")
        TranslationCache.getFailedKeys().contains("en::test") shouldBe true
        TranslationCache.removeFailed("en::test")
        TranslationCache.getFailedKeys().contains("en::test") shouldBe false
    }

    test("TranslationService addSuccessListener, removeSuccessListener and notifySuccess work") {
        val received = AtomicBoolean(false)
        val listener: (TranslationResult) -> Unit = { result ->
            if (result.originalText == "bonjour") {
                received.set(true)
            }
        }
        TranslationService.addSuccessListener(listener)

        val result = TranslationResult(
            originalText = "bonjour",
            translatedText = "hello",
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        TranslationService.notifySuccess(result)
        received.get() shouldBe true

        received.set(false)
        TranslationService.removeSuccessListener(listener)
        TranslationService.notifySuccess(result)
        received.get() shouldBe false
    }

    test("TranslationService detectLanguageQuick returns cached or null for blank") {
        TranslationService.detectLanguageQuick("") shouldBe null
        TranslationService.detectLanguageQuick("   ") shouldBe null

        val targetLang = TranslationService.getTargetLanguage()
        val result = TranslationResult(
            originalText = "salut",
            translatedText = "hi",
            detectedLanguage = "fr",
            targetLanguage = targetLang,
            isSameLanguage = false,
        )
        TranslationService.putCache(result)
        TranslationService.detectLanguageQuick("salut") shouldBe "fr"
    }

    test("TranslationService retryFailedTranslations skips when circuit breaker open or downloading") {
        TranslationCache.tripCircuitBreaker(60_000L)
        TranslationService.retryFailedTranslations()
        TranslationCache.resetCircuitBreaker()
    }

    test("TranslationService retryFailedTranslations retries expired entries") {
        val targetLang = TranslationService.getTargetLanguage()
        val key = TranslationService.cacheKey("hola", targetLang)
        TranslationService.failedRequests[key] = TranslationService.FailedRequest(
            text = "hola",
            targetLang = targetLang,
            failedAt = System.currentTimeMillis() - 20_000L,
        )

        // Pre-seed cache so retry succeeds
        val result = TranslationResult(
            originalText = "hola",
            translatedText = "hello",
            detectedLanguage = "es",
            targetLanguage = targetLang,
            isSameLanguage = false,
        )
        TranslationService.putCache(result)

        TranslationService.retryFailedTranslations()
        TranslationService.failedRequests.containsKey(key) shouldBe false
    }

    test("ChatTranslationManager onTranslationSuccess upgrades failed chat message") {
        var refreshed = false
        ChatTranslationManager.refreshScheduler = { refreshed = true }

        val originalComp = Component.literal("<Player> Bonjour le monde")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 9999L,
            originalComponent = originalComp,
            plainText = "Bonjour le monde",
            messageText = "Bonjour le monde",
        )
        tracked.translatedComponent = ChatTranslationManager.createFailedComponent(9999L, "Bonjour le monde")
        ChatTranslationManager.javaClass.getDeclaredField("trackedMessages").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = get(ChatTranslationManager)
                as java.util.concurrent.ConcurrentHashMap<Long, ChatTranslationManager.TrackedChatMessage>
            map[9999L] = tracked
        }

        val successRes = TranslationResult(
            originalText = "Bonjour le monde",
            translatedText = "Hello world",
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        ChatTranslationManager.onTranslationSuccess(successRes)

        refreshed shouldBe true
        tracked.translatedComponent!!.string.contains("Hello world") shouldBe true
        tracked.translatedComponent!!.string.contains("[T]") shouldBe true

        // Same language result is ignored
        val sameRes = TranslationResult(
            originalText = "Bonjour le monde",
            translatedText = "Bonjour le monde",
            detectedLanguage = "en",
            targetLanguage = "en",
            isSameLanguage = true,
        )
        refreshed = false
        ChatTranslationManager.onTranslationSuccess(sameRes)
        refreshed shouldBe false
    }

    test("SignTranslationManager onTranslationSuccess updates outcome and clears failed sign key") {
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = "$targetLang::Guten Tag"
        SignTranslationManager.failedSignKeys.add(textKey)

        val result = TranslationResult(
            originalText = "Guten Tag",
            translatedText = "Good day",
            detectedLanguage = "de",
            targetLanguage = targetLang,
            isSameLanguage = false,
        )
        SignTranslationManager.onTranslationSuccess(result)
        SignTranslationManager.failedSignKeys.contains(textKey) shouldBe false
        SignTranslationManager.textOutcomeCache.containsKey(textKey) shouldBe true

        // When same language, it should be ignored
        val sameResult = TranslationResult(
            originalText = "Guten Tag",
            translatedText = "Guten Tag",
            detectedLanguage = "en",
            targetLanguage = targetLang,
            isSameLanguage = true,
        )
        SignTranslationManager.onTranslationSuccess(sameResult)
    }

    test("ItemTranslationManager onTranslationSuccess updates itemCache") {
        val targetLang = TranslationService.getTargetLanguage()
        val text = "Epee magique"
        val cacheKey = "$targetLang::${text.hashCode()}"
        ItemTranslationManager.failedItems.add(cacheKey)

        val result = TranslationResult(
            originalText = text,
            translatedText = "Magic sword",
            detectedLanguage = "fr",
            targetLanguage = targetLang,
            isSameLanguage = false,
        )
        ItemTranslationManager.onTranslationSuccess(result)
        ItemTranslationManager.failedItems.contains(cacheKey) shouldBe false
        ItemTranslationManager.translateItemName(null, Component.literal(text)).string shouldBe text

        // Same language is ignored
        val sameResult = TranslationResult(
            originalText = text,
            translatedText = text,
            detectedLanguage = "en",
            targetLanguage = targetLang,
            isSameLanguage = true,
        )
        ItemTranslationManager.onTranslationSuccess(sameResult)
    }

    test("EntityTranslationManager onTranslationSuccess updates textComponentCache") {
        val targetLang = TranslationService.getTargetLanguage()
        val text = "Vache rouge"
        val textKey = "$targetLang::$text"
        EntityTranslationManager.failedEntities.add(textKey)

        val result = TranslationResult(
            originalText = text,
            translatedText = "Red cow",
            detectedLanguage = "fr",
            targetLanguage = targetLang,
            isSameLanguage = false,
        )
        EntityTranslationManager.onTranslationSuccess(result)
        EntityTranslationManager.failedEntities.contains(textKey) shouldBe false
        EntityTranslationManager.textComponentCache.containsKey(textKey) shouldBe true

        // Same language is ignored
        val sameResult = TranslationResult(
            originalText = text,
            translatedText = text,
            detectedLanguage = "en",
            targetLanguage = targetLang,
            isSameLanguage = true,
        )
        EntityTranslationManager.onTranslationSuccess(sameResult)
    }

    test("SignTranslationManager getOrRequestTranslatedSignText re-requests if failure cooldown elapsed") {
        val signText = SignText().setMessage(0, Component.literal("Test sentence"))
        SignTranslationManager.isFailed(signText) shouldBe false

        // Mark failed
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = "$targetLang::Test sentence"
        SignTranslationManager.failedSignKeys.add(textKey)
        TranslationCache.markFailed(textKey)
        SignTranslationManager.isFailed(signText) shouldBe true

        // Once cooldown passes, isFailed becomes false and clears key
        TranslationCache.removeFailed(textKey)
        SignTranslationManager.isFailed(signText) shouldBe false
        SignTranslationManager.failedSignKeys.contains(textKey) shouldBe false
    }

    test("TranslationService notifySuccess handles listener exceptions safely") {
        val throwingListener: (TranslationResult) -> Unit = {
            error("Listener deliberate error")
        }
        TranslationService.addSuccessListener(throwingListener)
        val result = TranslationResult(
            originalText = "hello",
            translatedText = "bonjour",
            detectedLanguage = "en",
            targetLanguage = "fr",
            isSameLanguage = false,
        )
        // Should not throw
        TranslationService.notifySuccess(result)
        TranslationService.removeSuccessListener(throwingListener)
    }

    test("TranslationService retryFailedTranslations skips unexpired or mismatched targetLang") {
        val targetLang = TranslationService.getTargetLanguage()
        val otherKey = "other::test"
        val unexpiredKey = TranslationService.cacheKey("recent", targetLang)

        TranslationService.failedRequests[otherKey] = TranslationService.FailedRequest(
            text = "test",
            targetLang = "de",
            failedAt = System.currentTimeMillis() - 60_000L,
        )
        TranslationService.failedRequests[unexpiredKey] = TranslationService.FailedRequest(
            text = "recent",
            targetLang = targetLang,
            failedAt = System.currentTimeMillis(),
        )

        TranslationService.retryFailedTranslations()
        TranslationService.failedRequests.containsKey(otherKey) shouldBe true
        TranslationService.failedRequests.containsKey(unexpiredKey) shouldBe true
    }

    test("OnnxModelManager onDownloadCompleted clears caches and triggers retry") {
        val method = com.stellar.lang.plugin.onnx.OnnxModelManager::class.java
            .getDeclaredMethod("onDownloadCompleted")
        method.isAccessible = true
        method.invoke(com.stellar.lang.plugin.onnx.OnnxModelManager)
    }

    test("ItemTranslationManager getValidCachedItem removes expired failures") {
        val targetLang = TranslationService.getTargetLanguage()
        val text = "Epee ancienne"
        val cacheKey = "$targetLang::${text.hashCode()}"
        val failedComp = Component.literal(text)
        val cacheField = ItemTranslationManager::class.java.getDeclaredField("itemCache")
            .apply { isAccessible = true }
        (cacheField.get(ItemTranslationManager) as? MutableMap<String, Component>)?.set(cacheKey, failedComp)
        ItemTranslationManager.failedItems.add(cacheKey)
        val serviceKey = TranslationService.cacheKey(text, targetLang)
        TranslationCache.markFailed(serviceKey)

        val method = ItemTranslationManager::class.java.getDeclaredMethod(
            "getValidCachedItem",
            String::class.java,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        // Still failed within cooldown
        val cached = method.invoke(ItemTranslationManager, cacheKey, text, targetLang)
        cached shouldBe failedComp
        ItemTranslationManager.failedItems.contains(cacheKey) shouldBe true

        // Expired failure clears key
        TranslationCache.removeFailed(serviceKey)
        val expired = method.invoke(ItemTranslationManager, cacheKey, text, targetLang)
        expired shouldBe null
        ItemTranslationManager.failedItems.contains(cacheKey) shouldBe false
    }

    test("EntityTranslationManager getValidCachedEntity removes expired failures") {
        val targetLang = TranslationService.getTargetLanguage()
        val text = "Zombie ancien"
        val textKey = "$targetLang::$text"
        val failedComp = Component.literal(text)
        EntityTranslationManager.textComponentCache[textKey] = failedComp
        EntityTranslationManager.failedEntities.add(textKey)
        TranslationCache.markFailed(textKey)

        val method = EntityTranslationManager::class.java.getDeclaredMethod(
            "getValidCachedEntity",
            String::class.java,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        // Still failed within cooldown
        val cached = method.invoke(EntityTranslationManager, textKey, text, targetLang)
        cached shouldBe failedComp
        EntityTranslationManager.failedEntities.contains(textKey) shouldBe true

        // Expired failure clears key
        TranslationCache.removeFailed(textKey)
        val expired = method.invoke(EntityTranslationManager, textKey, text, targetLang)
        expired shouldBe null
        EntityTranslationManager.failedEntities.contains(textKey) shouldBe false
    }

    test("SignTranslationManager isTranslating and translateSignText forceRetry work") {
        val signText = SignText().setMessage(0, Component.literal("Panneau traduit"))
        val targetLang = TranslationService.getTargetLanguage()
        val key = TranslationCache.cacheKey("Panneau traduit", targetLang)

        SignTranslationManager.isTranslating(signText) shouldBe false
        SignTranslationManager.isTranslating(SignText()) shouldBe false

        TranslationCache.queueInFlight(key) {}
        SignTranslationManager.isTranslating(signText) shouldBe true
        TranslationService.isInFlight("Panneau traduit") shouldBe true

        TranslationCache.completeInFlight(key, null)
        SignTranslationManager.isTranslating(signText) shouldBe false

        // Test translateSignText with forceRetry = true
        SignTranslationManager.translateSignText(signText, forceRetry = true)
        TranslationCache.completeInFlight(key, null)
    }

    test("TranslationBadgeHelper and ChatTranslationManager default arguments") {
        val badge = com.stellar.lang.badge.TranslationBadgeHelper.createTranslatingBadge()
        badge.string shouldBe "[...] "

        val chatComp = ChatTranslationManager.createTranslatingComponent(123L, "Default chat")
        chatComp.string shouldBe "[...] Default chat"

        val prefixedComp = ChatTranslationManager.createTranslatingComponent(
            124L,
            "Prefixed chat",
            Component.literal("[PREFIX] "),
        )
        prefixedComp.string shouldBe "[...] [PREFIX] Prefixed chat"
    }

    test("Item, entity, and sign translation managers throttle retries within cooldown") {
        val targetLang = TranslationService.getTargetLanguage()

        val itemText = "Epee cooldown"
        val itemKey = "$targetLang::${itemText.hashCode()}"
        val itemComp = Component.literal(itemText)
        val resolveItemMethod = ItemTranslationManager::class.java.getDeclaredMethod(
            "resolveItemTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        ).apply { isAccessible = true }

        ItemTranslationManager.failedItems.add(itemKey)
        val inFlightItem = resolveItemMethod.invoke(
            ItemTranslationManager,
            itemText,
            targetLang,
            itemKey,
            itemComp,
        ) as Component
        inFlightItem.string shouldBe "[...] $itemText"

        TranslationCache.completeInFlight(TranslationCache.cacheKey(itemText, targetLang), null)
        ItemTranslationManager.failedItems.add(itemKey)
        val throttledItem = resolveItemMethod.invoke(
            ItemTranslationManager,
            itemText,
            targetLang,
            itemKey,
            itemComp,
        ) as Component
        throttledItem.string shouldBe "[T] $itemText"

        val entityText = "Zombie cooldown"
        val entityKey = "$targetLang::$entityText"
        val entityComp = Component.literal(entityText)
        val resolveEntityMethod = EntityTranslationManager::class.java.getDeclaredMethod(
            "resolveEntityTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        ).apply { isAccessible = true }

        EntityTranslationManager.failedEntities.add(entityKey)
        val inFlightEntity = resolveEntityMethod.invoke(
            EntityTranslationManager,
            entityText,
            targetLang,
            entityKey,
            entityComp,
        ) as Component
        inFlightEntity.string shouldBe "[...] $entityText"

        TranslationCache.completeInFlight(entityKey, null)
        EntityTranslationManager.failedEntities.add(entityKey)
        val throttledEntity = resolveEntityMethod.invoke(
            EntityTranslationManager,
            entityText,
            targetLang,
            entityKey,
            entityComp,
        ) as Component
        throttledEntity.string shouldBe "[T] $entityText"

        val signText = SignText().setMessage(0, Component.literal("Panneau cooldown"))
        val signSentence = "Panneau cooldown"
        val signKey = "$targetLang::$signSentence"
        TranslationCache.markFailed(signKey)
        SignTranslationManager.failedSignKeys.add(signKey)

        SignTranslationManager.translateSignText(signText, forceRetry = false)
        SignTranslationManager.translateSignText(signText, forceRetry = true)
        SignTranslationManager.translateSignText(signText, forceRetry = true)
        TranslationCache.completeInFlight(signKey, null)
    }

    test("ItemTranslationManager and EntityTranslationManager retry callbacks handle success and failure") {
        val targetLang = TranslationService.getTargetLanguage()
        val itemText = "Epee magique retry"
        val itemKey = "$targetLang::${itemText.hashCode()}"
        val itemComp = Component.literal(itemText)
        val resolveItemMethod = ItemTranslationManager::class.java.getDeclaredMethod(
            "resolveItemTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        ).apply { isAccessible = true }

        // 1. Success on retry with callback completion
        ItemTranslationManager.failedItems.add(itemKey)
        val res = resolveItemMethod.invoke(ItemTranslationManager, itemText, targetLang, itemKey, itemComp)
            as Component
        res.string shouldBe "[...] $itemText"
        val retryResult = TranslationResult(itemText, "Magic sword retry", "fr", targetLang, false)
        TranslationCache.completeInFlight(TranslationCache.cacheKey(itemText, targetLang), retryResult)

        // 2. Failure on retry with callback completion
        TranslationCache.clear()
        TranslationCache.markFailed(TranslationCache.cacheKey(itemText, targetLang))
        ItemTranslationManager.failedItems.add(itemKey)
        resolveItemMethod.invoke(ItemTranslationManager, itemText, targetLang, itemKey, itemComp)
        TranslationCache.completeInFlight(TranslationCache.cacheKey(itemText, targetLang), null)

        // 3. Entity retry success and failure
        val entityText = "Monstre mystique retry"
        val entityKey = "$targetLang::$entityText"
        val entityComp = Component.literal(entityText)
        val resolveEntityMethod = EntityTranslationManager::class.java.getDeclaredMethod(
            "resolveEntityTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        ).apply { isAccessible = true }

        EntityTranslationManager.failedEntities.add(entityKey)
        val entRes = resolveEntityMethod.invoke(
            EntityTranslationManager,
            entityText,
            targetLang,
            entityKey,
            entityComp,
        ) as Component
        entRes.string shouldBe "[...] $entityText"
        val entityResult = TranslationResult(entityText, "Mystic monster retry", "fr", targetLang, false)
        TranslationCache.completeInFlight(entityKey, entityResult)

        TranslationCache.clear()
        TranslationCache.markFailed(entityKey)
        EntityTranslationManager.failedEntities.add(entityKey)
        resolveEntityMethod.invoke(EntityTranslationManager, entityText, targetLang, entityKey, entityComp)
        TranslationCache.completeInFlight(entityKey, null)
    }
})
