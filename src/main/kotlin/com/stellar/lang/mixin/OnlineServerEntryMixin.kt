@file:Suppress(
    "UnusedPrivateMember",
    "LongParameterList",
    "MaxLineLength",
    "MaximumLineLength",
    "TooGenericExceptionCaught",
)

package com.stellar.lang.mixin

import com.stellar.lang.geoip.ServerFlagManager
import com.stellar.lang.motd.ServerMotdTranslationManager
import com.stellar.lang.service.TranslationService
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList
import net.minecraft.client.multiplayer.ServerData
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.lang.reflect.Method

private const val PING_ICON_WIDTH: Int = 10
private const val PING_ICON_OFFSET_X: Int = 15
private const val FLAG_OFFSET_Y: Int = 12

private val getContentRightMethod: Method? = runCatching {
    ServerSelectionList.OnlineServerEntry::class.java.getMethod("getContentRight").apply {
        isAccessible = true
    }
}.getOrNull()

private val getContentYMethod: Method? = runCatching {
    ServerSelectionList.OnlineServerEntry::class.java.getMethod("getContentY").apply {
        isAccessible = true
    }
}.getOrNull()

/**
 * Mixin to display GeoIP country flag badges under the ping indicator and translate MOTD.
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
        require = 0,
    )
    private fun stellarOnExtractContent(
        extractor: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        runCatching {
            lastMouseX = x
            lastMouseY = y
            if (::serverData.isInitialized) {
                ServerMotdTranslationManager.processMotd(serverData)
                ServerFlagManager.processServer(serverData)
            }
        }
    }

    @Inject(
        method = [
            "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V",
        ],
        at = [At("TAIL")],
        require = 0,
    )
    private fun stellarRenderTail(
        extractor: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        runCatching {
            renderFlagUnderPing(extractor)
        }
    }

    @Unique
    private fun resolveRight(): Int? {
        val reflected = getContentRightMethod?.invoke(this) as? Int
        if (reflected != null) return reflected
        val dynamicallyFound = this.javaClass.methods
            .firstOrNull { it.name == "getContentRight" && it.parameterCount == 0 }
            ?.invoke(this) as? Int
        if (dynamicallyFound != null) return dynamicallyFound
        return (this as Any as? LayoutElement)?.let { it.x + it.width - 2 }
    }

    @Unique
    private fun resolveTop(): Int? {
        val reflected = getContentYMethod?.invoke(this) as? Int
        if (reflected != null) return reflected
        val dynamicallyFound = this.javaClass.methods
            .firstOrNull { it.name == "getContentY" && it.parameterCount == 0 }
            ?.invoke(this) as? Int
        if (dynamicallyFound != null) return dynamicallyFound
        return (this as Any as? LayoutElement)?.let { it.y + 2 }
    }

    @Unique
    private fun getEntryBounds(): Pair<Int, Int>? {
        return runCatching {
            val right = resolveRight() ?: return null
            val top = resolveTop() ?: return null
            Pair(right, top)
        }.getOrNull()
    }

    @Unique
    private fun renderFlagUnderPing(extractor: GuiGraphicsExtractor) {
        runCatching {
            if (!isServerReady()) return
            val flagChar = ServerFlagManager.getFlagChar(serverData) ?: return
            val (right, top) = getEntryBounds() ?: return

            val font = Minecraft.getInstance().font
            val flagStr = flagChar.toString()
            val flagWidth = font.width(flagStr)
            val flagX = right - PING_ICON_OFFSET_X + (PING_ICON_WIDTH - flagWidth) / 2
            val flagY = top + FLAG_OFFSET_Y

            extractor.text(font, flagStr, flagX, flagY, -1)
            checkFlagTooltip(extractor, font, flagX, flagY, flagChar)
        }
    }

    @Unique
    private fun isServerReady(): Boolean {
        return ::serverData.isInitialized && ServerFlagManager.isEnabled()
    }

    @Unique
    private fun checkFlagTooltip(
        extractor: GuiGraphicsExtractor,
        font: Font,
        flagX: Int,
        flagY: Int,
        flagChar: Char,
    ) {
        runCatching {
            val showTooltip = runCatching {
                TranslationService.getConfig().serverFlagTooltip.value()
            }.getOrDefault(true)
            if (!showTooltip) return

            val flagWidth = font.width(flagChar.toString())
            val isMouseOver = lastMouseX in flagX..flagX + flagWidth &&
                lastMouseY in flagY..flagY + font.lineHeight
            if (!isMouseOver || !::serverData.isInitialized) return

            val tooltip = ServerFlagManager.getTooltip(serverData) ?: return
            extractor.setTooltipForNextFrame(tooltip, lastMouseX, lastMouseY)
        }
    }
}
