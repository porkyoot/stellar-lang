package com.stellar.lang.badge

import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Utility for generating visual flag and [...] badges across signs, chat, items, and entities.
 */
object TranslationBadgeHelper {
    const val INDICATOR_COLOR: Int = 0x55FFFF
    const val INDICATOR_FAILED_COLOR: Int = 0xFF5555
    const val INDICATOR_TRANSLATING_COLOR: Int = 0xAAAAAA

    fun isHidden(): Boolean =
        TranslationService.getConfig().hideIndicators.value()

    fun createBadge(failed: Boolean): MutableComponent =
        createBadge(lang = null, failed = failed, trailingSpace = true)

    fun createBadge(failed: Boolean, trailingSpace: Boolean): MutableComponent =
        createBadge(lang = null, failed = failed, trailingSpace = trailingSpace)

    fun createBadge(
        lang: String?,
        failed: Boolean = false,
        trailingSpace: Boolean = true,
    ): MutableComponent {
        if (failed || isHidden()) return Component.empty()
        return LanguageFlagHelper.createFlagBadge(lang, trailingSpace)
    }

    fun createTranslatingBadge(trailingSpace: Boolean = true): MutableComponent {
        if (isHidden()) return Component.empty()
        val text = if (trailingSpace) "[...] " else "[...]"
        return Component.literal(text).withStyle(ChatFormatting.GRAY)
    }
}
