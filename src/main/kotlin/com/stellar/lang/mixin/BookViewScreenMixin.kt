package com.stellar.lang.mixin

import com.stellar.lang.book.BookLayoutConstants.BOOK_IMAGE_WIDTH
import com.stellar.lang.book.BookLayoutConstants.BOOK_RETRY_INTERVAL_MS
import com.stellar.lang.book.BookLayoutConstants.BOTTOM_BUTTON_MARGIN
import com.stellar.lang.book.BookLayoutConstants.BUTTON_PADDING_Y
import com.stellar.lang.book.BookLayoutConstants.BUTTON_PAGE_BACK_X
import com.stellar.lang.book.BookLayoutConstants.BUTTON_PAGE_FORWARD_X
import com.stellar.lang.book.BookLayoutConstants.BUTTON_PAGE_Y
import com.stellar.lang.book.BookLayoutConstants.HALF_ORIGINAL_BOOK
import com.stellar.lang.book.BookLayoutConstants.HEADER_GRAY_COLOR
import com.stellar.lang.book.BookLayoutConstants.HEADER_OFFSET_Y
import com.stellar.lang.book.BookLayoutConstants.HEADER_WHITE_COLOR
import com.stellar.lang.book.BookLayoutConstants.MAX_OFFSET
import com.stellar.lang.book.BookLayoutConstants.MIN_HEADER_Y
import com.stellar.lang.book.BookLayoutConstants.MIN_OFFSET
import com.stellar.lang.book.BookLayoutConstants.MIN_OFFSET_FALLBACK
import com.stellar.lang.book.BookLayoutConstants.MIN_SCREEN_HEIGHT_FOR_HEADER
import com.stellar.lang.book.BookLayoutConstants.OFFSET_WIDTH_MARGIN
import com.stellar.lang.book.BookLayoutConstants.PREVIEW_SCALE_X
import com.stellar.lang.book.BookLayoutConstants.PREVIEW_SCALE_Y
import com.stellar.lang.book.BookLayoutConstants.SCREEN_HALF_DIVISOR
import com.stellar.lang.book.BookLayoutConstants.SCREEN_QUARTER_DIVISOR
import com.stellar.lang.book.BookLayoutConstants.TOP_MARGIN_WITH_HEADER
import com.stellar.lang.book.BookTranslationManager
import com.stellar.lang.book.RefreshableBookScreen
import com.stellar.lang.book.TranslatedBookWidget
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

/**
 * Mixin into BookViewScreen to add a non-editable translated book on the side
 * when detected language is not the target language.
 */
@Suppress("UnusedPrivateMember", "TooManyFunctions", "LongParameterList", "MagicNumber", "LargeClass")
@Mixin(BookViewScreen::class)
abstract class BookViewScreenMixin : Screen(Component.empty()), RefreshableBookScreen {
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
    private var showDualBookState: Boolean = false

    @Unique
    private var isFailed: Boolean = false

    @Unique
    private var lastRetryTime: Long = 0L

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
        return config.enabled.value() && config.translateBooks.value() && showDualBookState
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

    @Unique
    private fun shouldActivateDualBook(orig: BookViewScreen.BookAccess): Boolean {
        val pages = orig.pages().map { it.string.trim() }
        if (pages.isEmpty() || pages.all { it.isBlank() }) return false
        val sample = pages.firstOrNull { it.isNotBlank() } ?: return false
        val quickLang = TranslationService.detectLanguageQuick(sample)
        val targetLang = TranslationService.getTargetLanguage()
        return quickLang == null || !quickLang.equals(targetLang, ignoreCase = true)
    }

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun stellarOnInit(ci: CallbackInfo) {
        this.translatedWidget = null
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateBooks.value()) return

        val current = bookAccess
        if (current == BookViewScreen.EMPTY_ACCESS || current.pageCount == 0) return

        val orig = originalAccess ?: current.also { originalAccess = it }
        if (!shouldActivateDualBook(orig)) {
            showDualBookState = false
            return
        }

        showDualBookState = true
        isTranslating = true
        isFailed = false
        setupTranslatedBookWidget()
        requestBookTranslation(orig, forceRetry = false)
    }

    @Inject(method = ["setBookAccess"], at = [At("TAIL")])
    private fun stellarOnSetBookAccess(access: BookViewScreen.BookAccess, ci: CallbackInfo) {
        val config = TranslationService.getConfig()
        val isValid = config.enabled.value() && config.translateBooks.value() &&
            access != BookViewScreen.EMPTY_ACCESS && access.pageCount > 0 &&
            (originalAccess !== access || translatedWidget == null)
        if (!isValid) return

        originalAccess = access
        if (!shouldActivateDualBook(access)) {
            showDualBookState = false
            updateLayout()
        } else {
            showDualBookState = true
            isTranslating = true
            isFailed = false
            setupTranslatedBookWidget()
            requestBookTranslation(access, forceRetry = false)
        }
    }

    @Unique
    private fun requestBookTranslation(orig: BookViewScreen.BookAccess, forceRetry: Boolean) {
        if (translatedWidget == null) {
            setupTranslatedBookWidget()
        }
        isTranslating = true
        BookTranslationManager.translateBookDetailedAsync(orig, forceRetry) { result ->
            val mc = this.minecraft
            mc.execute {
                isTranslating = false
                if (result.isSameLanguage) {
                    showDualBookState = false
                    translatedAccess = null
                    isFailed = false
                } else if (result.isFailed) {
                    showDualBookState = true
                    isFailed = true
                    translatedAccess = orig
                } else {
                    showDualBookState = true
                    isFailed = false
                    translatedAccess = result.access
                }
                if (translatedWidget == null) {
                    setupTranslatedBookWidget()
                }
                updateLayout()
            }
        }
    }

    @Unique
    private fun setupTranslatedBookWidget() {
        if (this.translatedWidget != null) {
            updateLayout()
            return
        }
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
        if (show && translatedWidget == null) {
            setupTranslatedBookWidget()
        }
        val widget = translatedWidget ?: return
        widget.visible = show

        if (show) {
            val hOffset = getHorizontalOffset()
            val rightCenterX = this.width / SCREEN_HALF_DIVISOR + hOffset
            val widgetWidth = (BOOK_IMAGE_WIDTH * PREVIEW_SCALE_X).toInt()
            val rightLeft = (rightCenterX - widgetWidth / SCREEN_HALF_DIVISOR).toInt()
            widget.x = rightLeft
            widget.y = backgroundTop()
            updateControlButtons()
        }
    }

    @Unique
    private fun updateControlButtons() {
        if (::forwardButton.isInitialized) {
            forwardButton.setX(backgroundLeft() + BUTTON_PAGE_FORWARD_X)
            forwardButton.setY(backgroundTop() + BUTTON_PAGE_Y)
        }
        if (::backButton.isInitialized) {
            backButton.setX(backgroundLeft() + BUTTON_PAGE_BACK_X)
            backButton.setY(backgroundTop() + BUTTON_PAGE_Y)
        }
        val menuY = menuControlsTop()
        for (child in children()) {
            if (child is Button && child !is PageButton) {
                child.setY(menuY)
            }
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

        if (isFailed && !isTranslating) {
            val now = System.currentTimeMillis()
            if (now - lastRetryTime >= BOOK_RETRY_INTERVAL_MS) {
                lastRetryTime = now
                val orig = originalAccess ?: bookAccess
                requestBookTranslation(orig, forceRetry = true)
            }
        }

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
        val badge = if (isTranslating) {
            com.stellar.lang.badge.TranslationBadgeHelper.createTranslatingBadge(trailingSpace = true)
        } else {
            com.stellar.lang.badge.TranslationBadgeHelper.createBadge(failed = isFailed, trailingSpace = true)
        }
        val header = Component.empty().append(badge)
            .append(Component.literal("Translated ($langCode)").withStyle(ChatFormatting.WHITE))
        extractor.centeredText(this.font, header, rightCenterX.toInt(), headerY, HEADER_WHITE_COLOR)
    }

    override fun stellarGetBookAccess(): BookViewScreen.BookAccess = bookAccess

    override fun stellarRefreshBook(): Boolean {
        val access = originalAccess ?: bookAccess
        if (access == BookViewScreen.EMPTY_ACCESS || access.pageCount == 0) return false
        showDualBookState = true
        if (translatedWidget == null) {
            setupTranslatedBookWidget()
        }
        requestBookTranslation(access, forceRetry = true)
        return true
    }
}
