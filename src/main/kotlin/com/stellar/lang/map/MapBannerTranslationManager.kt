package com.stellar.lang.map

import com.stellar.lang.badge.TranslationBadgeHelper
import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.maps.MapBanner
import net.minecraft.world.level.saveddata.maps.MapDecoration
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of banner and marker names on maps with text-based caching and lifecycle triggers.
 */
@Suppress("TooManyFunctions")
object MapBannerTranslationManager {
    private const val MIN_TRANSLATABLE_LENGTH = 2
    private const val ACCESS_RETRY_COOLDOWN_MS = 5_000L
    private const val BADGE_PREFIX_LENGTH = 4

    // Cache is strictly for TEXT: "$targetLang::$plainText" -> Component
    internal val textComponentCache = ConcurrentHashMap<String, Component>()
    internal val failedBanners = ConcurrentHashMap.newKeySet<String>()
    private val lastBannerRetryTimes = ConcurrentHashMap<String, Long>()

    @Volatile
    var levelProvider: (() -> Level?)? = null

    init {
        TranslationService.addSuccessListener { result ->
            onTranslationSuccess(result)
        }
    }

    internal fun onTranslationSuccess(result: TranslationResult) {
        val targetLang = result.targetLanguage
        val textKey = buildTextKey(targetLang, result.originalText)
        if (result.isSameLanguage) {
            failedBanners.remove(textKey)
            textComponentCache.remove(textKey)
            return
        }
        failedBanners.remove(textKey)
        textComponentCache[textKey] = createFormattedName(result.translatedText)
    }

    private fun buildTextKey(targetLang: String, plainText: String): String = "$targetLang::$plainText"

    private fun getLevel(): Level? =
        levelProvider?.invoke() ?: runCatching { Minecraft.getInstance().level }.getOrNull()

    fun refreshBanner(plainText: String): Boolean {
        val clean = plainText.trim()
        if (clean.length < MIN_TRANSLATABLE_LENGTH) return false
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = buildTextKey(targetLang, clean)
        textComponentCache.remove(textKey)
        failedBanners.remove(textKey)
        lastBannerRetryTimes.remove(textKey)
        TranslationService.evict(clean, targetLang)
        TranslationService.translateAsync(clean, forceRetry = true) { result ->
            if (result != null && !result.isSameLanguage) {
                failedBanners.remove(textKey)
                textComponentCache[textKey] = createFormattedName(result.translatedText)
            } else if (result != null && result.isSameLanguage) {
                failedBanners.remove(textKey)
                textComponentCache.remove(textKey)
            } else if (result == null) {
                failedBanners.add(textKey)
                textComponentCache[textKey] = createFailedName(clean)
            }
        }
        return true
    }

    fun refreshBanner(original: Component): Boolean {
        val text = original.string.trim()
        val clean = if (text.startsWith("[T] ") || text.startsWith("[...] ")) {
            text.substring(BADGE_PREFIX_LENGTH).trim()
        } else {
            text
        }
        return refreshBanner(clean)
    }

    fun refreshMap(item: ItemStack): Boolean {
        if (item.item !is MapItem) return false
        val level = getLevel() ?: return false
        val savedData = MapItem.getSavedData(item, level) ?: return false
        val bannersRefreshed = refreshBanners(savedData.banners)
        val decorationsRefreshed = refreshDecorations(savedData.decorations)
        return bannersRefreshed || decorationsRefreshed
    }

    private fun refreshBanners(banners: Iterable<MapBanner>): Boolean {
        var refreshedAny = false
        for (banner in banners) {
            val bannerName = banner.name().orElse(null)
            if (bannerName != null && refreshBanner(bannerName)) {
                refreshedAny = true
            }
        }
        return refreshedAny
    }

    private fun refreshDecorations(decorations: Iterable<MapDecoration>): Boolean {
        var refreshedAny = false
        for (decoration in decorations) {
            val decoName = decoration.name().orElse(null)
            if (decoName != null && refreshBanner(decoName)) {
                refreshedAny = true
            }
        }
        return refreshedAny
    }

    private fun getValidCachedBanner(textKey: String, plainText: String, targetLang: String): Component? {
        val cached = textComponentCache[textKey] ?: return null
        if (!failedBanners.contains(textKey)) return cached
        if (TranslationService.isFailed(plainText, targetLang)) return cached
        failedBanners.remove(textKey)
        textComponentCache.remove(textKey)
        return null
    }

    @Suppress("ReturnCount")
    fun translateBannerName(original: Component): Component {
        val plainText = getTranslatableText(original) ?: return original
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = buildTextKey(targetLang, plainText)

        if (TranslationService.isInFlight(plainText, targetLang)) {
            return createTranslatingName(plainText)
        }

        val cached = getValidCachedBanner(textKey, plainText, targetLang)
        if (cached != null) return cached

        return resolveBannerTranslation(plainText, targetLang, textKey, original)
    }

    @Suppress("ReturnCount", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    private fun resolveBannerTranslation(
        plainText: String,
        targetLang: String,
        textKey: String,
        original: Component,
    ): Component {
        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) return original
            val comp = createFormattedName(cachedResult.translatedText)
            failedBanners.remove(textKey)
            textComponentCache[textKey] = comp
            return comp
        }

        val isFailed = failedBanners.contains(textKey) || TranslationService.isFailed(plainText, targetLang)
        if (isFailed && !TranslationService.isInFlight(plainText, targetLang)) {
            return handleRetryOrFailed(plainText, textKey)
        }

        requestTranslation(plainText, targetLang, textKey)
        return original
    }

    private fun handleRetryOrFailed(plainText: String, textKey: String): Component {
        val now = System.currentTimeMillis()
        val lastRetry = lastBannerRetryTimes[textKey] ?: 0L
        if (now - lastRetry >= ACCESS_RETRY_COOLDOWN_MS) {
            lastBannerRetryTimes[textKey] = now
            TranslationService.translateAsync(plainText, forceRetry = true) { result ->
                if (result != null && !result.isSameLanguage) {
                    failedBanners.remove(textKey)
                    textComponentCache[textKey] = createFormattedName(result.translatedText)
                } else if (result != null && result.isSameLanguage) {
                    failedBanners.remove(textKey)
                    textComponentCache.remove(textKey)
                } else if (result == null) {
                    failedBanners.add(textKey)
                    textComponentCache[textKey] = createFailedName(plainText)
                }
            }
            return createTranslatingName(plainText)
        }
        return createFailedName(plainText)
    }

    private fun requestTranslation(plainText: String, targetLang: String, textKey: String) {
        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                failedBanners.remove(textKey)
                textComponentCache[textKey] = createFormattedName(result.translatedText)
            } else if (result != null && result.isSameLanguage) {
                failedBanners.remove(textKey)
                textComponentCache.remove(textKey)
            } else if (result == null && TranslationService.isFailed(plainText, targetLang)) {
                failedBanners.add(textKey)
                textComponentCache[textKey] = createFailedName(plainText)
            }
        }
    }

    fun refreshAll(): Int {
        val count = textComponentCache.size
        clearCache()
        return count
    }

    fun clearCache() {
        textComponentCache.clear()
        failedBanners.clear()
        lastBannerRetryTimes.clear()
        levelProvider = null
    }

    private fun getTranslatableText(original: Component): String? {
        val config = TranslationService.getConfig()
        val disabled = !config.enabled.value() ||
            !config.translateMapBanners.value() ||
            StellarLangInputHandler.isShowingOriginal()
        if (disabled) return null

        val text = com.stellar.lang.format.FormattingTagHelper.componentToFormattedText(original).trim()
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text)
        val isBadgePrefix = clean.startsWith("[T]") || clean.startsWith("[...]")
        return if (clean.length < MIN_TRANSLATABLE_LENGTH || isBadgePrefix) null else text
    }

    private fun createFormattedName(translatedText: String): MutableComponent {
        val badge = TranslationBadgeHelper.createBadge(failed = false, trailingSpace = true)
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(translatedText)
        return Component.empty().append(badge).append(textComp)
    }

    private fun createTranslatingName(originalText: String): MutableComponent {
        val badge = TranslationBadgeHelper.createTranslatingBadge(trailingSpace = true)
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(originalText)
        return Component.empty().append(badge).append(textComp)
    }

    private fun createFailedName(originalText: String): MutableComponent {
        val badge = TranslationBadgeHelper.createBadge(failed = true, trailingSpace = true)
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(originalText)
        return Component.empty().append(badge).append(textComp)
    }
}
