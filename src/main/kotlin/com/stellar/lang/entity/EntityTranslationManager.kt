package com.stellar.lang.entity

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.Entity
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of entity nametags and display names.
 */
object EntityTranslationManager {
    private const val MIN_TRANSLATABLE_LENGTH = 2
    private val entityCache = ConcurrentHashMap<String, Component>()

    @Suppress("UnusedParameter")
    fun translateEntityName(entity: Entity? = null, original: Component): Component {
        val plainText = getTranslatableText(original) ?: return original
        val config = TranslationService.getConfig()
        val targetLang = config.targetLanguage.value()
        val cacheKey = "$targetLang::${plainText.hashCode()}"

        val cached = entityCache[cacheKey]
        if (cached != null) return cached

        return resolveEntityTranslation(plainText, targetLang, cacheKey, original)
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

    private fun resolveEntityTranslation(
        plainText: String,
        targetLang: String,
        cacheKey: String,
        original: Component,
    ): Component {
        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) return original
            val comp = createFormattedName(cachedResult.translatedText)
            entityCache[cacheKey] = comp
            return comp
        }

        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                entityCache[cacheKey] = createFormattedName(result.translatedText)
            }
        }
        return original
    }

    private fun createFormattedName(translatedText: String): MutableComponent {
        val badge = Component.literal("[T] ").withStyle(ChatFormatting.AQUA).withStyle(ChatFormatting.BOLD)
        return Component.empty().append(badge).append(Component.literal(translatedText))
    }
}
