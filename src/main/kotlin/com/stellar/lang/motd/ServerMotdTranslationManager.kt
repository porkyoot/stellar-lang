package com.stellar.lang.motd

import com.stellar.lang.badge.TranslationBadgeHelper
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component
import java.util.Collections
import java.util.WeakHashMap

/**
 * Manages translation of server MOTDs (Message of the Day) on the multiplayer screen.
 */
object ServerMotdTranslationManager {
    private const val MIN_MOTD_LENGTH = 2

    private val originalMotds = Collections.synchronizedMap(WeakHashMap<ServerData, Component>())
    private val translatedMotds = Collections.synchronizedMap(WeakHashMap<ServerData, Component>())
    private val pendingTranslations = Collections.synchronizedSet(mutableSetOf<String>())

    fun processMotd(serverData: ServerData?) {
        if (serverData == null) return
        val currentMotd = serverData.motd
        if (shouldSkipMotd(serverData, currentMotd)) return

        val original = resolveOriginalMotd(serverData, currentMotd)
        val originalText = original.string.trim()

        if (applyCachedTranslation(serverData, original, originalText)) {
            return
        }

        requestAsyncTranslation(serverData, original, originalText)
    }

    private fun shouldSkipMotd(serverData: ServerData, currentMotd: Component): Boolean {
        val config = TranslationService.getConfig()
        if (!config.enabled.value()) return true

        val rawText = currentMotd.string.trim()
        if (rawText.isBlank() || rawText.length < MIN_MOTD_LENGTH) return true

        val cachedTranslated = translatedMotds[serverData]
        return cachedTranslated != null && currentMotd === cachedTranslated
    }

    private fun resolveOriginalMotd(serverData: ServerData, currentMotd: Component): Component {
        val rawText = currentMotd.string.trim()
        val previousOriginal = originalMotds[serverData]
        if (previousOriginal == null || previousOriginal.string.trim() != rawText) {
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
            val translatedComp = buildMotdComponent(cached.translatedText, original)
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
                val translatedComp = buildMotdComponent(result.translatedText, original)
                translatedMotds[serverData] = translatedComp
                serverData.motd = translatedComp
            }
        }
    }

    private fun buildMotdComponent(translatedText: String, original: Component): Component {
        val isHidden = TranslationBadgeHelper.isHidden()
        val textComp = Component.literal(translatedText).setStyle(original.style)
        if (isHidden) {
            return textComp
        }
        val badge = Component.literal("[T] ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
        return Component.empty().append(badge).append(textComp)
    }

    fun getOriginalMotd(serverData: ServerData): Component? = originalMotds[serverData]

    fun clearCache() {
        originalMotds.clear()
        translatedMotds.clear()
        pendingTranslations.clear()
    }
}
