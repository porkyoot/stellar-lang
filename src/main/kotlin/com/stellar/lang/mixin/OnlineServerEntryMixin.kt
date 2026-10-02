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
import net.minecraft.client.Minecraft
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
private const val PING_ICON_WIDTH: Int = 10
private const val PING_ICON_OFFSET_X: Int = 15
private const val FLAG_OFFSET_Y: Int = 12
private const val PLACEMENT_UNDER_PING: String = "under_ping"
private const val PLACEMENT_AFTER_NAME: String = "after_name"

/**
 * Mixin to display GeoIP country flag badges and translate MOTD in the server selection list.
 */
@Mixin(ServerSelectionList.OnlineServerEntry::class)
abstract class OnlineServerEntryMixin {
    @Shadow
    @Final
    private lateinit var serverData: ServerData

    @Unique
    private var lastMouseX: Int = 0

    @Unique
    private var lastMouseY: Int = 0

    @Shadow
    abstract fun getContentRight(): Int

    @Shadow
    abstract fun getContentY(): Int

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

    @Inject(
        method = [
            "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V",
        ],
        at = [At("TAIL")],
    )
    private fun stellarRenderTail(
        extractor: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        val placement = runCatching {
            TranslationService.getConfig().serverFlagPlacement.value()
        }.getOrDefault(PLACEMENT_UNDER_PING)

        if (placement == PLACEMENT_UNDER_PING) {
            renderFlagUnderPing(extractor)
        }
    }

    @Unique
    private fun renderFlagUnderPing(extractor: GuiGraphicsExtractor) {
        val flagChar = runCatching {
            if (ServerFlagManager.isEnabled()) ServerFlagManager.getFlagChar(serverData) else null
        }.getOrNull() ?: return

        val font = Minecraft.getInstance().font
        val flagStr = flagChar.toString()
        val flagWidth = font.width(flagStr)
        val flagX = getContentRight() - PING_ICON_OFFSET_X + (PING_ICON_WIDTH - flagWidth) / 2
        val flagY = getContentY() + FLAG_OFFSET_Y

        extractor.text(font, flagStr, flagX, flagY, -1)
        checkFlagTooltip(extractor, font, flagX, flagY, flagChar)
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
        val placement = runCatching {
            TranslationService.getConfig().serverFlagPlacement.value()
        }.getOrDefault(PLACEMENT_UNDER_PING)

        if (placement == PLACEMENT_UNDER_PING) {
            original.call(extractor, font, text, x, y, color)
            return
        }

        val flagChar = runCatching {
            if (ServerFlagManager.isEnabled()) ServerFlagManager.getFlagChar(serverData) else null
        }.getOrNull()

        if (flagChar == null) {
            original.call(extractor, font, text, x, y, color)
            return
        }

        renderDecoratedServerName(extractor, font, text, x, y, color, flagChar, original, placement)
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
        placement: String,
    ) {
        if (placement == PLACEMENT_AFTER_NAME) {
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
