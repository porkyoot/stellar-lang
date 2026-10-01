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
@Suppress("UnusedPrivateMember", "LongParameterList", "MagicNumber", "LongMethod")
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
        if (this.width >= 380) 110f else (this.width / 4.0f).coerceAtLeast(65f)

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

        // 1. Draw the second wider sign
        renderSecondWiderSign(extractor, secondSignX, secondSignY)

        // 2. Draw labels under signs to avoid colliding with hanging sign UI or title
        val isSameLang = SignEditPreviewManager.isSameLanguage.get()
        val badge = if (SignEditPreviewManager.isTranslating.get()) {
            com.stellar.lang.badge.TranslationBadgeHelper.createTranslatingBadge(trailingSpace = true)
        } else {
            com.stellar.lang.badge.TranslationBadgeHelper.createBadge(
                lang = SignEditPreviewManager.detectedLanguage,
                failed = SignEditPreviewManager.hasFailed.get(),
                trailingSpace = true,
            )
        }
        val origHeader = Component.empty().append(badge)
            .append(Component.translatable("stellar_lang.ui.original").withStyle(ChatFormatting.GRAY))

        val translatedHeader = if (isSameLang) {
            Component.empty()
                .append(Component.literal("[=] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("stellar_lang.ui.same_language").withStyle(ChatFormatting.GRAY))
        } else {
            Component.translatable("stellar_lang.ui.translated").withStyle(ChatFormatting.WHITE)
        }

        val labelY = (origY + 38).toInt()
        extractor.centeredText(
            this.font,
            origHeader,
            origX.toInt(),
            labelY,
            0xFFAAAAAA.toInt(),
        )

        extractor.centeredText(
            this.font,
            translatedHeader,
            secondSignX.toInt(),
            labelY,
            0xFFFFFFFF.toInt(),
        )
    }

    private fun renderSecondWiderSign(extractor: GuiGraphicsExtractor, secondSignX: Float, secondSignY: Float) {
        val previewScaleX = 1.45f
        val previewScaleY = 1.0f

        extractor.pose().pushMatrix()
        extractor.pose().translate(secondSignX, secondSignY)

        // Draw sign background wider
        extractor.pose().pushMatrix()
        extractor.pose().scale(previewScaleX, previewScaleY)
        this.extractSignBackground(extractor)
        extractor.pose().popMatrix()

        // Draw text with the exact same scale as the original sign
        extractor.pose().pushMatrix()
        val textScale = this.getSignTextScale()
        extractor.pose().scale(textScale.x(), textScale.y())

        val fullOriginal = messages.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val translated = SignEditPreviewManager.updateRealtimeTranslation(fullOriginal)
        renderPreviewContent(extractor, fullOriginal, translated)
        extractor.pose().popMatrix()

        extractor.pose().popMatrix()
    }

    private fun renderPreviewContent(extractor: GuiGraphicsExtractor, fullOriginal: String, translated: String) {
        val darkColor = if (this.text.hasGlowingText()) {
            this.text.color.textColor
        } else {
            AbstractSignRenderer.getDarkColor(this.text)
        }
        val textColor = if (darkColor == 0) 0x000000 else darkColor
        val isSameLang = SignEditPreviewManager.isSameLanguage.get()
        val displayText = when {
            isSameLang -> ""
            translated.isNotEmpty() -> translated
            SignEditPreviewManager.hasFailed.get() -> fullOriginal
            else -> ""
        }

        if (isSameLang) {
            val sameLangMsg = "[Same Language]"
            val subMsg = "Already translated"
            val msgWidth = this.font.width(sameLangMsg)
            val subWidth = this.font.width(subMsg)
            extractor.text(this.font, sameLangMsg, -msgWidth / 2, -this.font.lineHeight, 0x888888, false)
            extractor.text(this.font, subMsg, -subWidth / 2, 2, 0xAAAAAA, false)
        } else if (displayText.isNotEmpty()) {
            renderPreviewLines(extractor, displayText, textColor)
        } else if (fullOriginal.isNotEmpty() && SignEditPreviewManager.isTranslating.get()) {
            val translatingMsg = "..."
            val msgWidth = this.font.width(translatingMsg)
            extractor.text(this.font, translatingMsg, -msgWidth / 2, 0, 0x888888, false)
        }
    }

    private fun renderPreviewLines(extractor: GuiGraphicsExtractor, text: String, textColor: Int) {
        val lines = SignTooltipRenderer.wrapTooltipLines(text, SignEditPreviewManager.PREVIEW_LINE_CHARS)
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
    }

    @Inject(method = ["removed"], at = [At("TAIL")])
    private fun stellarOnRemoved(ci: CallbackInfo) {
        SignEditPreviewManager.clear()
    }
}
