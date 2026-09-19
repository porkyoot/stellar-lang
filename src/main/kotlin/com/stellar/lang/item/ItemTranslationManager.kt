package com.stellar.lang.item

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationService
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of item names and hover tooltips.
 */
object ItemTranslationManager {
    private const val MIN_TRANSLATABLE_LENGTH = 2
    private val itemCache = ConcurrentHashMap<String, Component>()

    fun clearCache() {
        itemCache.clear()
    }

    fun translateItemName(stack: ItemStack? = null, original: Component): Component {
        if (StellarLangInputHandler.isShowingOriginal()) return original
        if (stack == null || stack.customName == null) return original
        val plainText = getTranslatableText(original) ?: return original
        val config = TranslationService.getConfig()
        val targetLang = TranslationService.getTargetLanguage()
        val cacheKey = "$targetLang::${plainText.hashCode()}"

        val cached = itemCache[cacheKey]
        if (cached != null) return cached

        return resolveItemTranslation(plainText, targetLang, cacheKey, original)
    }

    private fun getTranslatableText(original: Component): String? {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateItems.value()) return null

        val text = original.string.trim()
        return if (text.length < MIN_TRANSLATABLE_LENGTH || text.startsWith("[T]")) null else text
    }

    @Suppress("ReturnCount")
    private fun resolveItemTranslation(
        plainText: String,
        targetLang: String,
        cacheKey: String,
        original: Component,
    ): Component {
        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) return original
            val comp = createFormattedName(cachedResult.translatedText)
            itemCache[cacheKey] = comp
            return comp
        }

        if (TranslationService.isFailed(plainText, targetLang)) {
            val failedComp = createFailedName(plainText)
            itemCache[cacheKey] = failedComp
            return failedComp
        }

        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                itemCache[cacheKey] = createFormattedName(result.translatedText)
            } else if (result == null && TranslationService.isFailed(plainText, targetLang)) {
                itemCache[cacheKey] = createFailedName(plainText)
            }
        }
        return original
    }

    private fun createFormattedName(translatedText: String): MutableComponent {
        val badge = com.stellar.lang.badge.TranslationBadgeHelper.createBadge(failed = false, trailingSpace = true)
        return Component.empty().append(badge).append(Component.literal(translatedText))
    }

    private fun createFailedName(originalText: String): MutableComponent {
        val badge = com.stellar.lang.badge.TranslationBadgeHelper.createBadge(failed = true, trailingSpace = true)
        return Component.empty().append(badge).append(Component.literal(originalText))
    }
}
