package com.stellar.lang.badge

import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Utility for generating visual [T] and [...] badges across signs, chat, items, and entities.
 */
object TranslationBadgeHelper {
    const val INDICATOR_COLOR: Int = 0x55FFFF
    const val INDICATOR_FAILED_COLOR: Int = 0xFF5555
    const val INDICATOR_TRANSLATING_COLOR: Int = 0xAAAAAA

    fun isHidden(): Boolean = runCatching {
        TranslationService.getConfig().hideIndicators.value()
    }.getOrDefault(false)

    fun createBadge(failed: Boolean): MutableComponent = createBadge(failed, true)

    fun createBadge(failed: Boolean, trailingSpace: Boolean): MutableComponent {
        if (isHidden()) return Component.empty()
        val text = if (trailingSpace) "[T] " else "[T]"
        return if (failed) {
            Component.literal(text).withStyle(ChatFormatting.RED, ChatFormatting.STRIKETHROUGH, ChatFormatting.BOLD)
        } else {
            Component.literal(text).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
        }
    }

    fun createTranslatingBadge(trailingSpace: Boolean = true): MutableComponent {
        if (isHidden()) return Component.empty()
        val text = if (trailingSpace) "[...] " else "[...]"
        return Component.literal(text).withStyle(ChatFormatting.GRAY)
    }
}
