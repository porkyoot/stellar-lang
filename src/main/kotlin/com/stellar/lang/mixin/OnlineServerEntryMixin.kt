@file:Suppress(
    "UnusedPrivateMember",
    "LongParameterList",
    "MaxLineLength",
    "MaximumLineLength",
    "TooGenericExceptionCaught",
)

package com.stellar.lang.mixin

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.stellar.lang.geoip.ServerFlagManager
import com.stellar.lang.motd.ServerMotdTranslationManager
import com.stellar.lang.service.TranslationService
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList
import net.minecraft.client.multiplayer.ServerData
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

private const val FLAG_SPACING: Int = 4

/**
 * Mixin to display GeoIP country flag badges and translate MOTD in the server selection list.
 */
@Mixin(ServerSelectionList.OnlineServerEntry::class)
class OnlineServerEntryMixin {
    @Shadow
    @Final
    private lateinit var serverData: ServerData

    @Unique
    private var lastMouseX: Int = 0

    @Unique
    private var lastMouseY: Int = 0

    @Suppress("LongParameterList")
    @Inject(
        method = [
            "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V",
        ],
        at = [At("HEAD")],
    )
    private fun stellarOnExtractContent(
        extractor: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        lastMouseX = x
        lastMouseY = y
        runCatching {
            ServerMotdTranslationManager.processMotd(serverData)
            ServerFlagManager.processServer(serverData)
        }
    }

    @WrapOperation(
        method = [
            "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V",
        ],
        at = [
            At(
                value = "INVOKE",
                target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(" +
                    "Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V",
            ),
        ],
    )
    private fun stellarWrapServerName(
        extractor: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Int,
        y: Int,
        color: Int,
        original: Operation<Void>,
    ) {
        val flagChar = runCatching {
            if (ServerFlagManager.isEnabled()) ServerFlagManager.getFlagChar(serverData) else null
        }.getOrNull()

        if (flagChar == null) {
            original.call(extractor, font, text, x, y, color)
            return
        }

        renderDecoratedServerName(extractor, font, text, x, y, color, flagChar, original)
    }

    @Unique
    private fun renderDecoratedServerName(
        extractor: GuiGraphicsExtractor,
        font: Font,
        text: String,
        x: Int,
        y: Int,
        color: Int,
        flagChar: Char,
        original: Operation<Void>,
    ) {
        val placement = runCatching {
            TranslationService.getConfig().serverFlagPlacement.value()
        }.getOrDefault("before_name")

        if (placement == "after_name") {
            original.call(extractor, font, text, x, y, color)
            val flagX = x + font.width(text) + FLAG_SPACING
            extractor.text(font, flagChar.toString(), flagX, y, color)
            checkFlagTooltip(extractor, font, flagX, y, flagChar)
        } else {
            val decorated = "$flagChar $text"
            original.call(extractor, font, decorated, x, y, color)
            checkFlagTooltip(extractor, font, x, y, flagChar)
        }
    }

    @Unique
    private fun checkFlagTooltip(
        extractor: GuiGraphicsExtractor,
        font: Font,
        flagX: Int,
        flagY: Int,
        flagChar: Char,
    ) {
        val showTooltip = runCatching {
            TranslationService.getConfig().serverFlagTooltip.value()
        }.getOrDefault(true)
        if (!showTooltip) return

        val flagWidth = font.width(flagChar.toString())
        val isMouseOver = lastMouseX in flagX..flagX + flagWidth && lastMouseY in flagY..flagY + font.lineHeight
        if (isMouseOver) {
            val tooltip = ServerFlagManager.getTooltip(serverData)
            if (tooltip != null) {
                extractor.setTooltipForNextFrame(tooltip, lastMouseX, lastMouseY)
            }
        }
    }
}
