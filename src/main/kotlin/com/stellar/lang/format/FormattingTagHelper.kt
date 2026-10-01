package com.stellar.lang.format

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import java.util.Locale
import java.util.Optional
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Utility for preserving Minecraft formatting across translations by encoding formatting
 * codes and component styles into protected untranslatable XML tags (<ut>...</ut>).
 */
object FormattingTagHelper {
    const val TAG_OPEN = "<ut>"
    const val TAG_CLOSE = "</ut>"

    private const val RGB_MASK = 0xFFFFFF
    private const val HEX_RADIX = 16
    private const val HEX_TOKEN_LENGTH = 14
    private const val CODE_TOKEN_LENGTH = 2
    private const val HEX_DIGITS_COUNT = 6
    private const val HEX_START_OFFSET = 3
    private const val HEX_STEP = 2

    private val LEGACY_COLOR_CODES: List<Pair<Int, String>> = listOf(
        ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN,
        ChatFormatting.DARK_AQUA, ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE,
        ChatFormatting.GOLD, ChatFormatting.GRAY, ChatFormatting.DARK_GRAY,
        ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.AQUA,
        ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW,
        ChatFormatting.WHITE,
    ).mapNotNull { formatting ->
        TextColor.fromLegacyFormat(formatting)?.let { it.value to formatting.toString() }
    }

    // Matches formatting code sequences: standard §[0-9a-fk-or] and RGB hex §x(§[0-9a-fA-F]){6}
    val FORMATTING_CODE_PATTERN: Pattern = Pattern.compile(
        """((?:§[0-9a-fk-orA-FK-OR]|§x(?:§[0-9a-fA-F]){6})+)""",
    )

    // Matches <ut>...</ut> tags with optional whitespace
    val UNTRANSLATABLE_TAG_PATTERN: Pattern = Pattern.compile(
        """<\s*ut\s*>([\s\S]*?)<\s*/\s*ut\s*>""",
        Pattern.CASE_INSENSITIVE,
    )

    private const val GROUP_HEX = 1
    private const val GROUP_CODE = 2
    private const val GROUP_TEXT = 3

    // Matches token streams of (1) hex code, (2) standard formatting code, or (3) text chunk
    private val TOKEN_PATTERN: Pattern = Pattern.compile(
        """(§x(?:§[0-9a-fA-F]){6})|(§[0-9a-fk-orA-FK-OR])|([^§]+|§)""",
    )

    /**
     * Converts a Minecraft Component into a formatted string with § formatting codes.
     */
    fun componentToFormattedText(component: Component): String {
        val builder = StringBuilder()
        var lastStyle: Style? = null

        component.visit({ style, partText ->
            if (partText.isNotEmpty()) {
                val styleChanged = style != lastStyle
                if (styleChanged) {
                    builder.append(resolveStyleCode(style, lastStyle))
                    lastStyle = style
                }
                builder.append(partText)
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)

        return builder.toString()
    }

    private fun resolveStyleCode(style: Style, lastStyle: Style?): String {
        if (style.isEmpty) {
            return if (lastStyle != null && !lastStyle.isEmpty) "§r" else ""
        }
        return styleToFormattingCodes(style)
    }

    /**
     * Converts a Style to its § formatting codes representation.
     */
    fun styleToFormattingCodes(style: Style): String {
        if (style.isEmpty) return "§r"
        val builder = StringBuilder()
        appendColorCode(builder, style.color)
        appendStyleFlags(builder, style)
        return builder.toString()
    }

    private fun appendColorCode(builder: StringBuilder, color: TextColor?) {
        if (color == null) return
        val legacy = LEGACY_COLOR_CODES.firstOrNull { it.first == color.value }?.second
        if (legacy != null) {
            builder.append(legacy)
            return
        }
        val hex = String.format(Locale.ROOT, "%06x", color.value and RGB_MASK)
        builder.append("§x")
        for (char in hex) {
            builder.append('§').append(char)
        }
    }

    private fun appendStyleFlags(builder: StringBuilder, style: Style) {
        if (style.isBold) builder.append("§l")
        if (style.isItalic) builder.append("§o")
        if (style.isUnderlined) builder.append("§n")
        if (style.isStrikethrough) builder.append("§m")
        if (style.isObfuscated) builder.append("§k")
    }

    /**
     * Wraps § formatting code sequences in untranslatable XML tags (<ut>...</ut>).
     */
    fun encodeToUntranslatableTags(text: String): String {
        if (text.contains(TAG_OPEN) || !text.contains('§')) return text
        val matcher = FORMATTING_CODE_PATTERN.matcher(text)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val code = matcher.group(1)
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("$TAG_OPEN$code$TAG_CLOSE"))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * Decodes <ut>...</ut> tags back into § formatting codes.
     */
    fun decodeFromUntranslatableTags(text: String): String {
        var str = text
        if (str.contains(TAG_OPEN, ignoreCase = true) || str.contains("<ut", ignoreCase = true)) {
            val matcher = UNTRANSLATABLE_TAG_PATTERN.matcher(str)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val inner = matcher.group(1).trim()
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(inner))
            }
            matcher.appendTail(buffer)
            str = buffer.toString()
        }
        if (str.contains('&')) {
            str = str.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
        return str
    }

    /**
     * Strips both <ut> tags and § formatting codes to produce clean text for language detection.
     */
    fun stripFormattingAndTags(text: String): String {
        var str = decodeFromUntranslatableTags(text)
        if (str.contains('§')) {
            str = FORMATTING_CODE_PATTERN.matcher(str).replaceAll("")
        }
        return str
    }

    /**
     * Parses a string with § formatting codes into a styled MutableComponent.
     */
    fun formattedTextToComponent(text: String, baseStyle: Style = Style.EMPTY): MutableComponent {
        if (!text.contains('§')) {
            return Component.literal(text).setStyle(baseStyle)
        }

        val root = Component.empty().setStyle(baseStyle)
        var currentStyle = baseStyle
        val matcher = TOKEN_PATTERN.matcher(text)

        while (matcher.find()) {
            val hexMatch = matcher.group(GROUP_HEX)
            val codeMatch = matcher.group(GROUP_CODE)
            val textMatch = matcher.group(GROUP_TEXT)

            if (hexMatch != null) {
                currentStyle = parseHexStyle(hexMatch, currentStyle)
            } else if (codeMatch != null) {
                currentStyle = parseCodeStyle(codeMatch, currentStyle, baseStyle)
            } else if (textMatch != null && textMatch.isNotEmpty()) {
                root.append(Component.literal(textMatch).setStyle(currentStyle))
            }
        }

        return root
    }

    private fun parseHexStyle(hexToken: String, current: Style): Style {
        if (hexToken.length != HEX_TOKEN_LENGTH) return current
        val hexChars = CharArray(HEX_DIGITS_COUNT)
        var writeIdx = 0
        var readIdx = HEX_START_OFFSET
        while (readIdx < HEX_TOKEN_LENGTH) {
            hexChars[writeIdx++] = hexToken[readIdx]
            readIdx += HEX_STEP
        }
        val rgb = String(hexChars).toIntOrNull(HEX_RADIX) ?: return current
        return current.withColor(TextColor.fromRgb(rgb))
    }

    private fun parseCodeStyle(codeToken: String, current: Style, base: Style): Style {
        if (codeToken.length != CODE_TOKEN_LENGTH) return current
        val formatting = ChatFormatting.getByCode(codeToken[1]) ?: return current
        return applyFormattingToStyle(current, formatting, base)
    }

    private fun applyFormattingToStyle(current: Style, formatting: ChatFormatting, base: Style): Style {
        return when {
            formatting == ChatFormatting.RESET -> base
            formatting.ordinal <= ChatFormatting.WHITE.ordinal -> current.withColor(formatting)
            formatting == ChatFormatting.BOLD -> current.withBold(true)
            formatting == ChatFormatting.ITALIC -> current.withItalic(true)
            formatting == ChatFormatting.UNDERLINE -> current.withUnderlined(true)
            formatting == ChatFormatting.STRIKETHROUGH -> current.withStrikethrough(true)
            formatting == ChatFormatting.OBFUSCATED -> current.withObfuscated(true)
            else -> current
        }
    }
}
