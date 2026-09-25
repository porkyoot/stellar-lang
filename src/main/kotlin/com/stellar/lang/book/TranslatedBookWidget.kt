package com.stellar.lang.book

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.network.chat.Style

private const val BOOK_IMAGE_WIDTH = 192
private const val BOOK_IMAGE_HEIGHT = 192
private const val BOOK_TEXTURE_WIDTH = 256
private const val BOOK_TEXTURE_HEIGHT = 256
private const val PAGE_TEXT_X = 34
private const val PAGE_TEXT_Y = 30
private const val PAGE_NUM_RIGHT_X = 148
private const val PAGE_NUM_Y = 16
private const val WRAP_WIDTH = 126
private const val LINE_HEIGHT = 9
private const val MAX_VISIBLE_LINES = 16
private const val BLACK_COLOR = -16_777_216
private const val GRAY_COLOR = 0x555555
private const val SUBTLE_GRAY_COLOR = 0x777777
private const val INDICATOR_Y = 175
private const val PREVIEW_SCALE_X = 1.20f
private const val PREVIEW_SCALE_Y = 1.16f
private const val HALF_PAGE = 96

/**
 * Layout constants for the dual book display used by BookViewScreenMixin.
 */
internal object BookLayoutConstants {
    const val BOOK_IMAGE_WIDTH = 192
    const val PREVIEW_SCALE_X = 1.20f
    const val PREVIEW_SCALE_Y = 1.16f
    const val MIN_HEADER_Y = 4
    const val HEADER_OFFSET_Y = 12
    const val MIN_SCREEN_HEIGHT_FOR_HEADER = 240
    const val HEADER_GRAY_COLOR = -0x555556
    const val HEADER_WHITE_COLOR = -0x1
    const val BOTTOM_BUTTON_MARGIN = 24
    const val BUTTON_PADDING_Y = 6
    const val BUTTON_PAGE_FORWARD_X = 116
    const val BUTTON_PAGE_BACK_X = 43
    const val BUTTON_PAGE_Y = 157
    const val SCREEN_HALF_DIVISOR = 2.0f
    const val SCREEN_QUARTER_DIVISOR = 4.0f
    const val HALF_ORIGINAL_BOOK = 96
    const val MIN_OFFSET = 95f
    const val MAX_OFFSET = 130f
    const val OFFSET_WIDTH_MARGIN = 115f
    const val MIN_OFFSET_FALLBACK = 60f
    const val TOP_MARGIN_WITH_HEADER = 16
    const val BOOK_RETRY_INTERVAL_MS = 15_000L
}

/**
 * Non-editable translated book widget rendered on the side of BookViewScreen.
 * Supports wider pages, overflow display, and mouse wheel scrolling.
 */
@Suppress("LongParameterList")
class TranslatedBookWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val font: Font,
    private val currentPageSupplier: () -> Int,
    private val translatedAccessSupplier: () -> BookViewScreen.BookAccess?,
    private val isTranslatingSupplier: () -> Boolean,
) : AbstractWidget(x, y, width, height, Component.empty()) {
    var scrollOffset: Int = 0
    private var lastRenderedPage: Int = -1

    override fun extractWidgetRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        extractor.pose().pushMatrix()
        extractor.pose().translate(this.x.toFloat(), this.y.toFloat())
        extractor.pose().scale(PREVIEW_SCALE_X, PREVIEW_SCALE_Y)

        // Draw translated book background texture
        extractor.blit(
            RenderPipelines.GUI_TEXTURED,
            BookViewScreen.BOOK_LOCATION,
            0,
            0,
            0.0f,
            0.0f,
            BOOK_IMAGE_WIDTH,
            BOOK_IMAGE_HEIGHT,
            BOOK_TEXTURE_WIDTH,
            BOOK_TEXTURE_HEIGHT,
        )

        val access = translatedAccessSupplier()
        if (access == null) {
            if (isTranslatingSupplier()) {
                val loadingMsg = Component.literal("...").withStyle(ChatFormatting.GRAY)
                val msgWidth = font.width(loadingMsg)
                extractor.text(font, loadingMsg, HALF_PAGE - msgWidth / 2, HALF_PAGE, SUBTLE_GRAY_COLOR, false)
            }
            extractor.pose().popMatrix()
            return
        }

        renderPageContents(extractor, access)
        extractor.pose().popMatrix()
    }

    private fun renderPageContents(extractor: GuiGraphicsExtractor, access: BookViewScreen.BookAccess) {
        val currentPage = currentPageSupplier()
        val totalPages = access.pageCount

        if (currentPage != lastRenderedPage) {
            lastRenderedPage = currentPage
            scrollOffset = 0
        }

        // Draw page indicator
        val pageMsg = Component.translatable("book.pageIndicator", currentPage + 1, totalPages.coerceAtLeast(1))
        val pageMsgWidth = font.width(pageMsg)
        extractor.text(font, pageMsg, PAGE_NUM_RIGHT_X - pageMsgWidth, PAGE_NUM_Y, GRAY_COLOR, false)

        if (currentPage in 0 until totalPages) {
            renderTranslatedLines(extractor, access.getPage(currentPage))
        }
    }

    private fun renderTranslatedLines(extractor: GuiGraphicsExtractor, pageComp: Component) {
        val pageStyle = Style.EMPTY.withoutShadow().withColor(BLACK_COLOR)
        val styledComp = ComponentUtils.mergeStyles(pageComp, pageStyle)
        val lines = font.split(styledComp, WRAP_WIDTH)

        val maxScroll = (lines.size - MAX_VISIBLE_LINES).coerceAtLeast(0)
        val startLine = scrollOffset.coerceIn(0, maxScroll)
        val linesToRender = lines.drop(startLine).take(MAX_VISIBLE_LINES + 1)

        for (i in linesToRender.indices) {
            val lineCharSeq = linesToRender[i]
            val posY = PAGE_TEXT_Y + i * LINE_HEIGHT
            extractor.text(font, lineCharSeq, PAGE_TEXT_X, posY, BLACK_COLOR, false)
        }

        if (lines.size > MAX_VISIBLE_LINES) {
            val endLine = (startLine + MAX_VISIBLE_LINES).coerceAtMost(lines.size)
            val indicator = "↕ ${startLine + 1}-$endLine/${lines.size}"
            val indWidth = font.width(indicator)
            extractor.text(font, indicator, PAGE_NUM_RIGHT_X - indWidth, INDICATOR_Y, SUBTLE_GRAY_COLOR, false)
        }
    }

    @Suppress("ReturnCount")
    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        val access = translatedAccessSupplier() ?: return false
        val page = currentPageSupplier()
        if (page !in 0 until access.pageCount) return false

        val pageComp = access.getPage(page)
        val pageStyle = Style.EMPTY.withoutShadow().withColor(BLACK_COLOR)
        val styledComp = ComponentUtils.mergeStyles(pageComp, pageStyle)
        val lines = font.split(styledComp, WRAP_WIDTH)
        val maxScroll = (lines.size - MAX_VISIBLE_LINES).coerceAtLeast(0)

        if (maxScroll > 0) {
            if (verticalAmount > 0) {
                scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
                return true
            } else if (verticalAmount < 0) {
                scrollOffset = (scrollOffset + 1).coerceAtMost(maxScroll)
                return true
            }
        }
        return false
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        // Narration handled by main book view
    }
}
