package com.stellar.lang.entity

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.Entity
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of entity nametags and display names with text-based caching and lifecycle triggers.
 */
object EntityTranslationManager {
    private const val MIN_TRANSLATABLE_LENGTH = 2

    // Cache is strictly for TEXT: "$targetLang::$plainText" -> Component
    internal val textComponentCache = ConcurrentHashMap<String, Component>()
    internal val failedEntities = ConcurrentHashMap.newKeySet<String>()

    init {
        TranslationService.addSuccessListener { result ->
            onTranslationSuccess(result)
        }
    }

    internal fun onTranslationSuccess(result: TranslationResult) {
        if (result.isSameLanguage) return
        val targetLang = result.targetLanguage
        val textKey = "$targetLang::${result.originalText}"
        failedEntities.remove(textKey)
        textComponentCache[textKey] = createFormattedName(result.translatedText)
    }

    fun onEntityLoaded(entity: Entity) {
        val customName = entity.customName ?: return
        onEntityNameChanged(customName)
    }

    fun onEntityNameChanged(name: Component) {
        val plainText = getTranslatableText(name) ?: return
        val config = TranslationService.getConfig()
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = "$targetLang::$plainText"

        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (!cachedResult.isSameLanguage && !textComponentCache.containsKey(textKey)) {
                failedEntities.remove(textKey)
                textComponentCache[textKey] = createFormattedName(cachedResult.translatedText)
            }
            return
        }

        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                failedEntities.remove(textKey)
                textComponentCache[textKey] = createFormattedName(result.translatedText)
            } else if (result == null && TranslationService.isFailed(plainText, targetLang)) {
                failedEntities.add(textKey)
                textComponentCache[textKey] = createFailedName(plainText)
            }
        }
    }

    private fun getValidCachedEntity(textKey: String, plainText: String, targetLang: String): Component? {
        val cached = textComponentCache[textKey] ?: return null
        if (!failedEntities.contains(textKey)) return cached
        if (TranslationService.isFailed(plainText, targetLang)) return cached
        failedEntities.remove(textKey)
        textComponentCache.remove(textKey)
        return null
    }

    @Suppress("UnusedParameter", "ReturnCount")
    fun translateEntityName(entity: Entity? = null, original: Component): Component {
        val plainText = getTranslatableText(original) ?: return original
        val config = TranslationService.getConfig()
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = "$targetLang::$plainText"

        val cached = getValidCachedEntity(textKey, plainText, targetLang)
        if (cached != null) return cached

        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) return original
            val comp = createFormattedName(cachedResult.translatedText)
            failedEntities.remove(textKey)
            textComponentCache[textKey] = comp
            return comp
        }

        if (TranslationService.isFailed(plainText, targetLang)) {
            val failedComp = createFailedName(plainText)
            failedEntities.add(textKey)
            textComponentCache[textKey] = failedComp
            return failedComp
        }

        onEntityNameChanged(original)
        return original
    }

    fun clearCache() {
        textComponentCache.clear()
        failedEntities.clear()
    }

    private fun getTranslatableText(original: Component): String? {
        val config = TranslationService.getConfig()
        val disabled = !config.enabled.value() ||
            !config.translateEntities.value() ||
            StellarLangInputHandler.isShowingOriginal()
        if (disabled) return null

        val text = original.string.trim()
        return if (text.length < MIN_TRANSLATABLE_LENGTH || text.startsWith("[T]")) null else text
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
