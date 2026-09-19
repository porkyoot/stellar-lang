package com.stellar.lang.mixin

import com.stellar.lang.service.TranslationService
import com.stellar.lang.sign.SignEditPreviewManager
import com.stellar.lang.sign.SignTooltipRenderer
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import org.joml.Vector3fc
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.ModifyVariable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin into AbstractSignEditScreen to add a second bigger sign previewing real-time translation.
 */
@Suppress("UnusedPrivateMember", "LongParameterList", "MagicNumber")
@Mixin(AbstractSignEditScreen::class)
abstract class AbstractSignEditScreenMixin : Screen(Component.empty()) {
    @Shadow
    protected lateinit var sign: SignBlockEntity

    @Shadow
    private lateinit var text: SignText

    @Shadow
    private lateinit var messages: Array<String>

    @Shadow
    protected abstract fun extractSignBackground(extractor: GuiGraphicsExtractor)

    @Shadow
    protected abstract fun getSignTextScale(): Vector3fc

    @Shadow
    protected abstract fun getSignYOffset(): Float

    private fun getHorizontalOffset(): Float =
        if (this.width >= 380) 105f else (this.width / 4.0f).coerceAtLeast(65f)

    @ModifyVariable(method = ["extractSign"], at = [At("STORE")], ordinal = 0)
    private fun stellarModifySignX(originalX: Float): Float {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateSigns.value()) return originalX
        return originalX - getHorizontalOffset()
    }

    @Inject(
        method = ["extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"],
        at = [At("TAIL")],
    )
    private fun stellarOnExtractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
        ci: CallbackInfo,
    ) {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateSigns.value()) return

        val hOffset = getHorizontalOffset()
        val origX = this.width / 2.0f - hOffset
        val origY = this.getSignYOffset()
        val secondSignX = this.width / 2.0f + hOffset
        val secondSignY = this.getSignYOffset()

        // Draw headers above signs
        extractor.centeredText(
            this.font,
            Component.literal("Original").withStyle(ChatFormatting.GRAY),
            origX.toInt(),
            (origY - 50).toInt(),
            0xAAAAAA,
        )

        val langCode = TranslationService.getTargetLanguage().uppercase()
        val header = Component.literal("[T] ")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
            .append(Component.literal("Translated ($langCode)").withStyle(ChatFormatting.WHITE))
        extractor.centeredText(this.font, header, secondSignX.toInt(), (secondSignY - 50).toInt(), 0xFFFFFF)

        // Draw the second bigger sign
        renderSecondBiggerSign(extractor, secondSignX, secondSignY)
    }

    private fun renderSecondBiggerSign(extractor: GuiGraphicsExtractor, secondSignX: Float, secondSignY: Float) {
        val previewScale = 1.35f

        extractor.pose().pushMatrix()
        extractor.pose().translate(secondSignX, secondSignY)
        extractor.pose().scale(previewScale, previewScale)

        // Draw sign background
        extractor.pose().pushMatrix()
        this.extractSignBackground(extractor)
        extractor.pose().popMatrix()

        // Draw translated text
        val textScale = this.getSignTextScale()
        extractor.pose().scale(textScale.x(), textScale.y())

        val fullOriginal = messages.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val translated = SignEditPreviewManager.updateRealtimeTranslation(fullOriginal)

        val darkColor = if (this.text.hasGlowingText()) {
            this.text.color.textColor
        } else {
            AbstractSignRenderer.getDarkColor(this.text)
        }
        val textColor = if (darkColor == 0) 0x000000 else darkColor

        if (translated.isNotEmpty()) {
            val lines = SignTooltipRenderer.wrapTooltipLines(translated, SignEditPreviewManager.PREVIEW_LINE_CHARS)
            val lineHeight = this.sign.textLineHeight
            val totalHeight = lines.size * lineHeight
            val startY = -totalHeight / 2
            for (i in lines.indices) {
                val lineStr = lines[i]
                val lineWidth = this.font.width(lineStr)
                val posX = -lineWidth / 2
                val posY = startY + i * lineHeight
                extractor.text(this.font, lineStr, posX, posY, textColor, false)
            }
        } else if (fullOriginal.isNotEmpty() && SignEditPreviewManager.isTranslating.get()) {
            val translatingMsg = "..."
            val msgWidth = this.font.width(translatingMsg)
            extractor.text(this.font, translatingMsg, -msgWidth / 2, 0, 0x888888, false)
        }

        extractor.pose().popMatrix()
    }

    @Inject(method = ["removed"], at = [At("TAIL")])
    private fun stellarOnRemoved(ci: CallbackInfo) {
        SignEditPreviewManager.clear()
    }
}
