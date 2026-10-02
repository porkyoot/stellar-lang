@file:Suppress("LongParameterList")

package com.stellar.lang.geoip

import com.stellar.lang.service.TranslationService
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.multiplayer.ServerData

/**
 * Handles rendering the GeoIP country flag badge under the ping indicator in the server selection list.
 */
object ServerFlagRenderHelper {
    const val PING_ICON_WIDTH: Int = 10
    const val PING_ICON_OFFSET_X: Int = 15
    const val FLAG_OFFSET_Y: Int = 12
    private const val DEFAULT_FONT_HEIGHT: Int = 9

    fun renderFlagUnderPing(
        entry: Any,
        serverData: ServerData,
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        fontOverride: Font? = null,
    ) {
        if (!ServerFlagManager.isEnabled()) return
        val flagChar = ServerFlagManager.getFlagChar(serverData) ?: return
        val (right, top) = getEntryBounds(entry) ?: return

        val font = fontOverride ?: runCatching { Minecraft.getInstance().font }.getOrNull() ?: return
        val flagStr = flagChar.toString()
        val flagWidth = resolveDimension(runCatching { font.width(flagStr) }.getOrNull(), PING_ICON_WIDTH)
        val flagX = right - PING_ICON_OFFSET_X + (PING_ICON_WIDTH - flagWidth) / 2
        val flagY = top + FLAG_OFFSET_Y

        runCatching { extractor.text(font, flagStr, flagX, flagY, -1) }
        checkFlagTooltip(serverData, extractor, font, flagX, flagY, flagChar, mouseX, mouseY)
    }

    internal fun resolveDimension(computed: Int?, fallback: Int): Int =
        if (computed != null && computed > 0) computed else fallback

    private fun resolveRight(entry: Any): Int? {
        val reflected = runCatching {
            entry.javaClass.getMethod("getContentRight").apply { isAccessible = true }.invoke(entry) as? Int
        }.getOrNull()
        if (reflected != null) return reflected
        return (entry as? LayoutElement)?.let { it.x + it.width - 2 }
    }

    private fun resolveTop(entry: Any): Int? {
        val reflected = runCatching {
            entry.javaClass.getMethod("getContentY").apply { isAccessible = true }.invoke(entry) as? Int
        }.getOrNull()
        if (reflected != null) return reflected
        return (entry as? LayoutElement)?.let { it.y + 2 }
    }

    internal fun getEntryBounds(entry: Any): Pair<Int, Int>? {
        return runCatching {
            val right = resolveRight(entry) ?: return null
            val top = resolveTop(entry) ?: return null
            Pair(right, top)
        }.getOrNull()
    }

    internal fun checkFlagTooltip(
        serverData: ServerData,
        extractor: GuiGraphicsExtractor,
        font: Font,
        flagX: Int,
        flagY: Int,
        flagChar: Char,
        mouseX: Int,
        mouseY: Int,
    ) {
        val showTooltip = runCatching {
            TranslationService.getConfig().serverFlagTooltip.value()
        }.getOrDefault(true)
        if (!showTooltip) return

        val flagWidth = resolveDimension(
            runCatching { font.width(flagChar.toString()) }.getOrNull(),
            PING_ICON_WIDTH,
        )
        val lineHeight = resolveDimension(
            runCatching { font.lineHeight }.getOrNull(),
            DEFAULT_FONT_HEIGHT,
        )
        val isMouseOver = mouseX in flagX..flagX + flagWidth &&
            mouseY in flagY..flagY + lineHeight
        if (!isMouseOver) return

        val tooltip = ServerFlagManager.getTooltip(serverData) ?: return
        runCatching { extractor.setTooltipForNextFrame(tooltip, mouseX, mouseY) }
    }
}
