package com.stellar.lang.container

import com.stellar.lang.badge.TranslationBadgeHelper
import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of container and inventory labels when renamed and not in the target language.
 */
@Suppress("TooManyFunctions")
object ContainerTranslationManager {
    private const val MIN_TRANSLATABLE_LENGTH = 2
    private const val ACCESS_RETRY_COOLDOWN_MS = 5_000L

    internal val containerCache = ConcurrentHashMap<String, Component>()
    internal val failedContainers = ConcurrentHashMap.newKeySet<String>()
    internal val sameLanguageContainers = ConcurrentHashMap.newKeySet<String>()
    private val lastContainerRetryTimes = ConcurrentHashMap<String, Long>()

    init {
        TranslationService.addSuccessListener { result ->
            onTranslationSuccess(result)
        }
    }

    internal fun onTranslationSuccess(result: TranslationResult) {
        val targetLang = result.targetLanguage
        val cacheKey = "$targetLang::${result.originalText.hashCode()}"
        if (result.isSameLanguage) {
            sameLanguageContainers.add(cacheKey)
            containerCache.remove(cacheKey)
            failedContainers.remove(cacheKey)
            return
        }
        failedContainers.remove(cacheKey)
        sameLanguageContainers.remove(cacheKey)
        containerCache[cacheKey] = createFormattedName(result.translatedText)
    }

    fun clearCache() {
        containerCache.clear()
        failedContainers.clear()
        sameLanguageContainers.clear()
        lastContainerRetryTimes.clear()
    }

    /**
     * Determines whether a component represents a player-renamed container label rather than a default translation key.
     */
    fun isRenamed(component: Component): Boolean {
        if (component.string.isBlank()) return false
        val contents = component.contents
        if (contents is TranslatableContents) return false
        if (contents is PlainTextContents && contents.text().isEmpty()) {
            val nonBlank = component.siblings.filter { it.string.isNotBlank() }
            if (nonBlank.isNotEmpty() && nonBlank.all { !isRenamed(it) }) {
                return false
            }
        }
        return true
    }

    fun refreshContainer(original: Component): Boolean {
        val plainText = getTranslatableText(original) ?: return false
        return refreshContainer(plainText)
    }

    fun refreshContainer(plainText: String): Boolean {
        val targetLang = TranslationService.getTargetLanguage()
        val cacheKey = "$targetLang::${plainText.hashCode()}"
        containerCache.remove(cacheKey)
        failedContainers.remove(cacheKey)
        sameLanguageContainers.remove(cacheKey)
        lastContainerRetryTimes.remove(cacheKey)
        TranslationService.evict(plainText, targetLang)
        TranslationService.translateAsync(plainText, forceRetry = true) { result ->
            if (result != null && !result.isSameLanguage) {
                failedContainers.remove(cacheKey)
                sameLanguageContainers.remove(cacheKey)
                containerCache[cacheKey] = createFormattedName(result.translatedText)
            } else if (result != null && result.isSameLanguage) {
                sameLanguageContainers.add(cacheKey)
            } else if (result == null) {
                failedContainers.add(cacheKey)
                containerCache[cacheKey] = createFailedName(plainText)
            }
        }
        return true
    }

    fun refreshScreen(screen: AbstractContainerScreen<*>): Boolean {
        val title = screen.title
        if (isRenamed(title)) {
            return refreshContainer(title)
        }
        return false
    }

    fun refreshBlockEntity(blockEntity: BaseContainerBlockEntity): Boolean {
        val customName = blockEntity.customName ?: return false
        if (isRenamed(customName)) {
            return refreshContainer(customName)
        }
        return false
    }

    private fun getValidCachedContainer(cacheKey: String, plainText: String, targetLang: String): Component? {
        val cached = containerCache[cacheKey] ?: return null
        if (!failedContainers.contains(cacheKey)) return cached
        if (TranslationService.isFailed(plainText, targetLang)) return cached
        failedContainers.remove(cacheKey)
        containerCache.remove(cacheKey)
        return null
    }

    /**
     * Translates a container or inventory label if and only if it is renamed and not in the target language.
     */
    @Suppress("ReturnCount")
    fun translateLabel(original: Component): Component {
        if (!isRenamed(original)) return original
        val plainText = getTranslatableText(original) ?: return original
        val targetLang = TranslationService.getTargetLanguage()
        val cacheKey = "$targetLang::${plainText.hashCode()}"

        if (sameLanguageContainers.contains(cacheKey)) return original

        val quickLang = TranslationService.detectLanguageQuick(plainText)
        if (quickLang != null && quickLang.equals(targetLang, ignoreCase = true)) {
            sameLanguageContainers.add(cacheKey)
            return original
        }

        if (TranslationService.isInFlight(plainText, targetLang)) {
            return createTranslatingName(plainText, original.style)
        }

        val cached = getValidCachedContainer(cacheKey, plainText, targetLang)
        if (cached != null) return cached

        return resolveContainerTranslation(plainText, targetLang, cacheKey, original)
    }

    private fun getTranslatableText(original: Component): String? {
        val config = TranslationService.getConfig()
        val disabled = !config.enabled.value() ||
            !config.translateContainers.value() ||
            StellarLangInputHandler.isShowingOriginal()
        if (disabled) return null

        val text = original.string.trim()
        val isBadgePrefix = text.startsWith("[T]") || text.startsWith("[...]")
        return if (text.length < MIN_TRANSLATABLE_LENGTH || isBadgePrefix) null else text
    }

    @Suppress("ReturnCount", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    private fun resolveContainerTranslation(
        plainText: String,
        targetLang: String,
        cacheKey: String,
        original: Component,
    ): Component {
        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) {
                sameLanguageContainers.add(cacheKey)
                return original
            }
            val comp = createFormattedName(cachedResult.translatedText, original.style)
            failedContainers.remove(cacheKey)
            containerCache[cacheKey] = comp
            return comp
        }

        val isFailed = failedContainers.contains(cacheKey) || TranslationService.isFailed(plainText, targetLang)
        if (isFailed && !TranslationService.isInFlight(plainText, targetLang)) {
            return handleRetryOrFailed(plainText, cacheKey, original)
        }

        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                failedContainers.remove(cacheKey)
                containerCache[cacheKey] = createFormattedName(result.translatedText, original.style)
            } else if (result != null && result.isSameLanguage) {
                sameLanguageContainers.add(cacheKey)
            } else if (result == null && TranslationService.isFailed(plainText, targetLang)) {
                failedContainers.add(cacheKey)
                containerCache[cacheKey] = createFailedName(plainText, original.style)
            }
        }
        return original
    }

    private fun handleRetryOrFailed(
        plainText: String,
        cacheKey: String,
        original: Component,
    ): Component {
        val now = System.currentTimeMillis()
        val lastRetry = lastContainerRetryTimes[cacheKey] ?: 0L
        if (now - lastRetry >= ACCESS_RETRY_COOLDOWN_MS) {
            lastContainerRetryTimes[cacheKey] = now
            TranslationService.translateAsync(plainText, forceRetry = true) { result ->
                if (result != null && !result.isSameLanguage) {
                    failedContainers.remove(cacheKey)
                    containerCache[cacheKey] = createFormattedName(result.translatedText, original.style)
                } else if (result != null && result.isSameLanguage) {
                    sameLanguageContainers.add(cacheKey)
                } else if (result == null) {
                    failedContainers.add(cacheKey)
                    containerCache[cacheKey] = createFailedName(plainText, original.style)
                }
            }
            return createTranslatingName(plainText, original.style)
        }
        return createFailedName(plainText, original.style)
    }

    private fun createFormattedName(translatedText: String, style: Style = Style.EMPTY): MutableComponent {
        val badge = TranslationBadgeHelper.createBadge(failed = false, trailingSpace = true)
        return Component.empty().append(badge).append(Component.literal(translatedText).withStyle(style))
    }

    private fun createTranslatingName(originalText: String, style: Style = Style.EMPTY): MutableComponent {
        val badge = TranslationBadgeHelper.createTranslatingBadge(trailingSpace = true)
        return Component.empty().append(badge).append(Component.literal(originalText).withStyle(style))
    }

    private fun createFailedName(originalText: String, style: Style = Style.EMPTY): MutableComponent {
        val badge = TranslationBadgeHelper.createBadge(failed = true, trailingSpace = true)
        return Component.empty().append(badge).append(Component.literal(originalText).withStyle(style))
    }
}
