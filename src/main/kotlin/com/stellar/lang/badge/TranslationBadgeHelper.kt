package com.stellar.lang.badge

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * Utility for generating visual [T] badges across signs, chat, items, and entities.
 */
object TranslationBadgeHelper {
    const val INDICATOR_COLOR: Int = 0x55FFFF
    const val INDICATOR_FAILED_COLOR: Int = 0xFF5555

    fun createBadge(failed: Boolean): MutableComponent = createBadge(failed, true)

    fun createBadge(failed: Boolean, trailingSpace: Boolean): MutableComponent {
        val text = if (trailingSpace) "[T] " else "[T]"
        return if (failed) {
            Component.literal(text).withStyle(ChatFormatting.RED, ChatFormatting.STRIKETHROUGH, ChatFormatting.BOLD)
        } else {
            Component.literal(text).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
        }
    }
}
