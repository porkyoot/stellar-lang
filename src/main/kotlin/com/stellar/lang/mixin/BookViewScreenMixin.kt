package com.stellar.lang.mixin

import com.stellar.lang.book.BookTranslationManager
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.gui.screens.inventory.PageButton
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

private const val BOOK_IMAGE_WIDTH = 192
private const val PREVIEW_SCALE_X = 1.20f
private const val PREVIEW_SCALE_Y = 1.16f
private const val MIN_HEADER_Y = 4
private const val HEADER_OFFSET_Y = 12
private const val MIN_SCREEN_HEIGHT_FOR_HEADER = 240
private const val HEADER_GRAY_COLOR = 0xAAAAAA
private const val HEADER_WHITE_COLOR = 0xFFFFFF
private const val BOTTOM_BUTTON_MARGIN = 24
private const val BUTTON_PADDING_Y = 6
private const val BUTTON_PAGE_FORWARD_X = 116
private const val BUTTON_PAGE_BACK_X = 43
private const val BUTTON_PAGE_Y = 157
private const val SCREEN_HALF_DIVISOR = 2.0f
private const val SCREEN_QUARTER_DIVISOR = 4.0f
private const val HALF_ORIGINAL_BOOK = 96
private const val MIN_OFFSET = 95f
private const val MAX_OFFSET = 130f
private const val OFFSET_WIDTH_MARGIN = 115f
private const val MIN_OFFSET_FALLBACK = 60f
private const val TOP_MARGIN_WITH_HEADER = 16

/**
 * Mixin into BookViewScreen to add a non-editable translated book on the side
 * when detected language is not the target language.
 */
@Suppress("UnusedPrivateMember", "TooManyFunctions", "LongParameterList", "MagicNumber")
@Mixin(BookViewScreen::class)
abstract class BookViewScreenMixin : Screen(Component.empty()) {
    @Shadow
    private lateinit var bookAccess: BookViewScreen.BookAccess

    @Shadow
    private var currentPage: Int = 0

    @Shadow
    private lateinit var forwardButton: PageButton

    @Shadow
    private lateinit var backButton: PageButton

    @Unique
    private var originalAccess: BookViewScreen.BookAccess? = null

    @Unique
    private var translatedAccess: BookViewScreen.BookAccess? = null

    @Unique
    private var isTranslating: Boolean = false

    @Unique
    private var doneButton: Button? = null

    @Unique
    private var translatedWidget: TranslatedBookWidget? = null

    @Shadow
    protected abstract fun backgroundLeft(): Int

    @Shadow
    protected abstract fun backgroundTop(): Int

    @Shadow
    protected abstract fun menuControlsTop(): Int

    @Unique
    private fun shouldShowDualBook(): Boolean {
        val config = TranslationService.getConfig()
        return config.enabled.value() && config.translateBooks.value() && translatedAccess != null
    }

    @Unique
    private fun getHorizontalOffset(): Float {
        val maxOffset = (this.width / SCREEN_HALF_DIVISOR - OFFSET_WIDTH_MARGIN).coerceAtLeast(MIN_OFFSET_FALLBACK)
        return (this.width / SCREEN_QUARTER_DIVISOR).coerceIn(MIN_OFFSET, MAX_OFFSET).coerceAtMost(maxOffset)
    }

    @Inject(method = ["backgroundLeft"], at = [At("RETURN")], cancellable = true)
    private fun stellarModifyBackgroundLeft(cir: CallbackInfoReturnable<Int>) {
        if (shouldShowDualBook()) {
            val hOffset = getHorizontalOffset()
            val origX = (this.width / SCREEN_HALF_DIVISOR - hOffset - HALF_ORIGINAL_BOOK).toInt()
            cir.returnValue = origX
        }
    }

    @Inject(method = ["backgroundTop"], at = [At("RETURN")], cancellable = true)
    private fun stellarModifyBackgroundTop(cir: CallbackInfoReturnable<Int>) {
        if (shouldShowDualBook() && this.height >= MIN_SCREEN_HEIGHT_FOR_HEADER) {
            cir.returnValue = TOP_MARGIN_WITH_HEADER
        }
    }

    @Inject(method = ["menuControlsTop"], at = [At("RETURN")], cancellable = true)
    private fun stellarModifyMenuControlsTop(cir: CallbackInfoReturnable<Int>) {
        if (shouldShowDualBook()) {
            val scaledHeight = (BOOK_IMAGE_WIDTH * PREVIEW_SCALE_Y).toInt()
            val computedY = backgroundTop() + scaledHeight + BUTTON_PADDING_Y
            val maxY = this.height - BOTTOM_BUTTON_MARGIN
            cir.returnValue = computedY.coerceAtMost(maxY)
        }
    }

    @Inject(method = ["createMenuControls"], at = [At("TAIL")])
    private fun stellarOnCreateMenuControls(ci: CallbackInfo) {
        for (child in children()) {
            if (child is Button && child.message.string == CommonComponents.GUI_DONE.string) {
                doneButton = child
                break
            }
        }
    }

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun stellarOnInit(ci: CallbackInfo) {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateBooks.value()) return

        if (originalAccess == null) {
            originalAccess = bookAccess
        }
        val orig = originalAccess ?: return

        setupTranslatedBookWidget()

        isTranslating = true
        BookTranslationManager.translateBookAsync(orig) { translated ->
            isTranslating = false
            if (translated != null) {
                translatedAccess = translated
                minecraft.execute {
                    updateLayout()
                }
            }
        }
    }

    @Unique
    private fun setupTranslatedBookWidget() {
        val hOffset = getHorizontalOffset()
        val rightCenterX = this.width / SCREEN_HALF_DIVISOR + hOffset
        val widgetWidth = (BOOK_IMAGE_WIDTH * PREVIEW_SCALE_X).toInt()
        val widgetHeight = (BOOK_IMAGE_WIDTH * PREVIEW_SCALE_Y).toInt()
        val rightLeft = (rightCenterX - widgetWidth / SCREEN_HALF_DIVISOR).toInt()
        val rightTop = backgroundTop()

        val widget = TranslatedBookWidget(
            x = rightLeft,
            y = rightTop,
            width = widgetWidth,
            height = widgetHeight,
            font = this.font,
            currentPageSupplier = { this.currentPage },
            translatedAccessSupplier = { this.translatedAccess },
            isTranslatingSupplier = { this.isTranslating },
        )
        widget.visible = shouldShowDualBook()
        this.translatedWidget = widget
        this.addRenderableWidget(widget)

        if (shouldShowDualBook()) {
            updateLayout()
        }
    }

    @Unique
    private fun updateLayout() {
        val show = shouldShowDualBook()
        val widget = translatedWidget ?: return
        widget.visible = show

        if (show) {
            val hOffset = getHorizontalOffset()
            val rightCenterX = this.width / SCREEN_HALF_DIVISOR + hOffset
            val widgetWidth = (BOOK_IMAGE_WIDTH * PREVIEW_SCALE_X).toInt()
            val rightLeft = (rightCenterX - widgetWidth / SCREEN_HALF_DIVISOR).toInt()
            val rightTop = backgroundTop()

            widget.x = rightLeft
            widget.y = rightTop

            if (::forwardButton.isInitialized) {
                forwardButton.setX(backgroundLeft() + BUTTON_PAGE_FORWARD_X)
                forwardButton.setY(backgroundTop() + BUTTON_PAGE_Y)
            }
            if (::backButton.isInitialized) {
                backButton.setX(backgroundLeft() + BUTTON_PAGE_BACK_X)
                backButton.setY(backgroundTop() + BUTTON_PAGE_Y)
            }
            doneButton?.setY(menuControlsTop())
        }
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
        if (!shouldShowDualBook()) return

        val config = TranslationService.getConfig()
        val hOffset = getHorizontalOffset()
        val origCenterX = this.width / SCREEN_HALF_DIVISOR - hOffset
        val rightCenterX = this.width / SCREEN_HALF_DIVISOR + hOffset
        val headerY = (backgroundTop() - HEADER_OFFSET_Y).coerceAtLeast(MIN_HEADER_Y)

        // Draw header above original book
        extractor.centeredText(
            this.font,
            Component.literal("Original").withStyle(ChatFormatting.GRAY),
            origCenterX.toInt(),
            headerY,
            HEADER_GRAY_COLOR,
        )

        // Draw header above translated book
        val langCode = TranslationService.getTargetLanguage().uppercase()
        val header = Component.literal("[T] ")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
            .append(Component.literal("Translated ($langCode)").withStyle(ChatFormatting.WHITE))
        extractor.centeredText(this.font, header, rightCenterX.toInt(), headerY, HEADER_WHITE_COLOR)
    }
}
