package com.stellar.lang.format

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

class FormattingTagHelperSpec : FunSpec({

    test("componentToFormattedText with plain text returns unformatted string without reset") {
        val comp = Component.literal("Hello World")
        FormattingTagHelper.componentToFormattedText(comp) shouldBe "Hello World"
    }

    test("componentToFormattedText with named colors and styles formats correctly") {
        val comp = Component.literal("Green ")
            .withStyle(ChatFormatting.GREEN)
            .append(
                Component.literal("Bold Red")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
            )
            .append(
                Component.literal(" Normal"),
            )
        val formatted = FormattingTagHelper.componentToFormattedText(comp)
        formatted shouldBe "§aGreen §c§lBold Red§a Normal"
    }

    test("componentToFormattedText with RGB hex color formats correctly") {
        val rgbStyle = Style.EMPTY.withColor(TextColor.fromRgb(0x123456))
        val comp = Component.literal("Custom Color").setStyle(rgbStyle)
        val formatted = FormattingTagHelper.componentToFormattedText(comp)
        formatted shouldBe "§x§1§2§3§4§5§6Custom Color"
    }

    test("encodeToUntranslatableTags wraps formatting codes in ut tags") {
        val input = "§aHello §c§lWorld§r!"
        val encoded = FormattingTagHelper.encodeToUntranslatableTags(input)
        encoded shouldBe "<ut>§a</ut>Hello <ut>§c§l</ut>World<ut>§r</ut>!"
    }

    test("encodeToUntranslatableTags handles text without formatting and already tagged text") {
        FormattingTagHelper.encodeToUntranslatableTags("Plain Text") shouldBe "Plain Text"
        val alreadyTagged = "<ut>§a</ut>Hello"
        FormattingTagHelper.encodeToUntranslatableTags(alreadyTagged) shouldBe alreadyTagged
    }

    test("decodeFromUntranslatableTags restores formatting codes from tags") {
        val input = "<ut>§a</ut>Bonjour <ut>§c§l</ut>le monde<ut>§r</ut>!"
        val decoded = FormattingTagHelper.decodeFromUntranslatableTags(input)
        decoded shouldBe "§aBonjour §c§lle monde§r!"
    }

    test("decodeFromUntranslatableTags handles tag whitespace variations") {
        val input = "<ut> §a </ut>Bonjour <UT>§c</UT>monde"
        val decoded = FormattingTagHelper.decodeFromUntranslatableTags(input)
        decoded shouldBe "§aBonjour §cmonde"
    }

    test("decodeFromUntranslatableTags unescapes xml entities") {
        val input = "<ut>§a</ut>Porkyoot &lt;3 &amp; &quot;friends&quot;"
        val decoded = FormattingTagHelper.decodeFromUntranslatableTags(input)
        decoded shouldBe "§aPorkyoot <3 & \"friends\""
    }

    test("stripFormattingAndTags removes tags and formatting codes") {
        val input = "<ut>§a</ut>Bonjour §c§lle monde<ut>§r</ut>!"
        val stripped = FormattingTagHelper.stripFormattingAndTags(input)
        stripped shouldBe "Bonjour le monde!"
    }

    test("formattedTextToComponent parses standard colors and styles into component tree") {
        val input = "§aGreen §c§lBold Red§r Normal"
        val comp = FormattingTagHelper.formattedTextToComponent(input)
        comp.string shouldBe "Green Bold Red Normal"
        comp.siblings.size shouldBe 3
        comp.siblings[0].style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.GREEN)
        comp.siblings[1].style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.RED)
        comp.siblings[1].style.isBold shouldBe true
        comp.siblings[2].style.color shouldBe null
        comp.siblings[2].style.isBold shouldBe false
    }

    test("formattedTextToComponent parses hex RGB colors") {
        val input = "§x§1§2§3§4§5§6Hex Color"
        val comp = FormattingTagHelper.formattedTextToComponent(input)
        comp.string shouldBe "Hex Color"
        comp.siblings.size shouldBe 1
        comp.siblings[0].style.color shouldBe TextColor.fromRgb(0x123456)
    }

    test("formattedTextToComponent handles italic, underline, strikethrough, obfuscated") {
        val input = "§oItalic §nUnderline §mStrike §kObf"
        val comp = FormattingTagHelper.formattedTextToComponent(input)
        comp.string shouldBe "Italic Underline Strike Obf"
        comp.siblings[0].style.isItalic shouldBe true
        comp.siblings[1].style.isUnderlined shouldBe true
        comp.siblings[2].style.isStrikethrough shouldBe true
        comp.siblings[3].style.isObfuscated shouldBe true
    }

    test("roundtrip component conversion preserves styling") {
        val orig = Component.literal("Start ")
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal("Middle").withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC))
        val formatted = FormattingTagHelper.componentToFormattedText(orig)
        val encoded = FormattingTagHelper.encodeToUntranslatableTags(formatted)
        val decoded = FormattingTagHelper.decodeFromUntranslatableTags(encoded)
        val reconstructed = FormattingTagHelper.formattedTextToComponent(decoded)

        reconstructed.string shouldBe orig.string
        reconstructed.siblings.size shouldBe 2
        reconstructed.siblings[0].style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.YELLOW)
        reconstructed.siblings[1].style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.AQUA)
        reconstructed.siblings[1].style.isItalic shouldBe true
    }
})
