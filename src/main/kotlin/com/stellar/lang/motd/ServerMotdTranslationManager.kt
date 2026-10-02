package com.stellar.lang.motd

import com.stellar.lang.badge.TranslationBadgeHelper
import com.stellar.lang.service.TranslationService
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component
import java.util.Collections
import java.util.WeakHashMap

/**
 * Manages translation of server MOTDs (Message of the Day) on the multiplayer screen.
 */
object ServerMotdTranslationManager {
    private const val MIN_MOTD_LENGTH = 2

    internal var motdField: java.lang.reflect.Field? = runCatching {
        ServerData::class.java.getDeclaredField("motd").apply { isAccessible = true }
    }.getOrNull()

    private val originalMotds = Collections.synchronizedMap(WeakHashMap<ServerData, Component>())
    private val translatedMotds = Collections.synchronizedMap(WeakHashMap<ServerData, Component>())
    private val pendingTranslations = Collections.synchronizedSet(mutableSetOf<String>())

    fun processMotd(serverData: ServerData?) {
        if (serverData == null) return
        val currentMotd = getMotdSafely(serverData) ?: return
        if (shouldSkipMotd(serverData, currentMotd)) return

        val original = resolveOriginalMotd(serverData, currentMotd)
        val originalText = original.string.trim()

        if (applyCachedTranslation(serverData, original, originalText)) {
            return
        }

        requestAsyncTranslation(serverData, original, originalText)
    }

    private fun getMotdSafely(serverData: ServerData): Component? {
        return runCatching {
            motdField?.get(serverData) as? Component
        }.getOrNull() ?: runCatching {
            serverData.motd
        }.getOrNull()
    }

    private fun shouldSkipMotd(serverData: ServerData, currentMotd: Component): Boolean {
        val config = TranslationService.getConfig()
        if (!config.enabled.value()) return true

        val rawText = com.stellar.lang.format.FormattingTagHelper.componentToFormattedText(currentMotd).trim()
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(rawText)
        if (clean.isBlank() || clean.length < MIN_MOTD_LENGTH) return true

        val cachedTranslated = translatedMotds[serverData]
        return cachedTranslated != null && currentMotd === cachedTranslated
    }

    private fun resolveOriginalMotd(serverData: ServerData, currentMotd: Component): Component {
        val rawText = com.stellar.lang.format.FormattingTagHelper.componentToFormattedText(currentMotd).trim()
        val previousOriginal = originalMotds[serverData]
        val prevRaw = previousOriginal?.let {
            com.stellar.lang.format.FormattingTagHelper.componentToFormattedText(it).trim()
        }
        if (previousOriginal == null || prevRaw != rawText) {
            originalMotds[serverData] = currentMotd
        }
        return originalMotds[serverData] ?: currentMotd
    }

    private fun applyCachedTranslation(
        serverData: ServerData,
        original: Component,
        originalText: String,
    ): Boolean {
        val targetLang = TranslationService.getTargetLanguage()
        val cached = TranslationService.getCached(originalText, targetLang) ?: return false

        if (!cached.isSameLanguage) {
            val translatedComp = buildMotdComponent(cached.translatedText, original, cached.detectedLanguage)
            translatedMotds[serverData] = translatedComp
            serverData.motd = translatedComp
        }
        return true
    }

    private fun requestAsyncTranslation(
        serverData: ServerData,
        original: Component,
        originalText: String,
    ) {
        if (!pendingTranslations.add(originalText)) return

        TranslationService.translateAsync(originalText) { result ->
            pendingTranslations.remove(originalText)
            if (result != null && !result.isSameLanguage) {
                val translatedComp = buildMotdComponent(result.translatedText, original, result.detectedLanguage)
                translatedMotds[serverData] = translatedComp
                serverData.motd = translatedComp
            }
        }
    }

    private fun buildMotdComponent(
        translatedText: String,
        original: Component,
        lang: String?,
    ): Component {
        val isHidden = TranslationBadgeHelper.isHidden()
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(
            translatedText,
            original.style,
        )
        if (isHidden) {
            return textComp
        }
        val badge = TranslationBadgeHelper.createBadge(lang = lang, failed = false, trailingSpace = true)
        return Component.empty().append(badge).append(textComp)
    }

    fun getOriginalMotd(serverData: ServerData): Component? = originalMotds[serverData]

    fun clearCache() {
        originalMotds.clear()
        translatedMotds.clear()
        pendingTranslations.clear()
    }
}
