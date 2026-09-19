package com.stellar.lang.entity

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
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

    fun onEntityLoaded(entity: Entity) {
        val customName = entity.customName ?: return
        onEntityNameChanged(customName)
    }

    fun onEntityNameChanged(name: Component) {
        val plainText = getTranslatableText(name) ?: return
        val config = TranslationService.getConfig()
        val targetLang = config.targetLanguage.value()
        val textKey = "$targetLang::$plainText"

        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (!cachedResult.isSameLanguage && !textComponentCache.containsKey(textKey)) {
                textComponentCache[textKey] = createFormattedName(cachedResult.translatedText)
            }
            return
        }

        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                textComponentCache[textKey] = createFormattedName(result.translatedText)
            }
        }
    }

    @Suppress("UnusedParameter", "ReturnCount")
    fun translateEntityName(entity: Entity? = null, original: Component): Component {
        val plainText = getTranslatableText(original) ?: return original
        val config = TranslationService.getConfig()
        val targetLang = config.targetLanguage.value()
        val textKey = "$targetLang::$plainText"

        val cached = textComponentCache[textKey]
        if (cached != null) return cached

        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) return original
            val comp = createFormattedName(cachedResult.translatedText)
            textComponentCache[textKey] = comp
            return comp
        }

        onEntityNameChanged(original)
        return original
    }

    fun clearCache() {
        textComponentCache.clear()
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
        val badge = Component.literal("[T] ").withStyle(ChatFormatting.AQUA).withStyle(ChatFormatting.BOLD)
        return Component.empty().append(badge).append(Component.literal(translatedText))
    }
}
