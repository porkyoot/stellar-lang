package com.stellar.lang.mixin

import com.stellar.lang.book.BookTranslationManager
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

private const val BOOK_IMAGE_WIDTH = 192
private const val BOOK_IMAGE_HEIGHT = 192
private const val BUTTON_WIDTH = 24
private const val BUTTON_HEIGHT = 20
private const val BUTTON_OFFSET_X = 26
private const val BUTTON_OFFSET_Y = 8

/**
 * Injects a floating clickable [T] widget into BookViewScreen to toggle translation.
 */
@Suppress("UnusedPrivateMember")
@Mixin(BookViewScreen::class)
abstract class BookViewScreenMixin : Screen(Component.empty()) {
    @Shadow
    private lateinit var bookAccess: BookViewScreen.BookAccess

    @Unique
    private var originalAccess: BookViewScreen.BookAccess? = null

    @Unique
    private var translatedAccess: BookViewScreen.BookAccess? = null

    @Unique
    private var isTranslatedView: Boolean = false

    @Shadow
    abstract fun setBookAccess(bookAccess: BookViewScreen.BookAccess)

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun stellarOnInit(ci: CallbackInfo) {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateBooks.value()) {
            return
        }

        if (originalAccess == null) {
            originalAccess = bookAccess
        }

        val bookLeft = (this.width - BOOK_IMAGE_WIDTH) / 2
        val bookTop = (this.height - BOOK_IMAGE_HEIGHT) / 2
        val buttonX = bookLeft + BOOK_IMAGE_WIDTH - BUTTON_OFFSET_X
        val buttonY = bookTop + BUTTON_OFFSET_Y

        val toggleButton = Button.builder(createButtonLabel()) { button ->
            stellarOnToggleClick(button)
        }
            .bounds(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)
            .tooltip(Tooltip.create(Component.literal("Toggle Translation [T] (LibreTranslate)")))
            .build()

        this.addRenderableWidget(toggleButton)

        // Pre-fetch translated book access
        val orig = originalAccess ?: return
        BookTranslationManager.translateBookAsync(orig) { translated ->
            if (translated != null) {
                translatedAccess = translated
            }
        }
    }

    @Unique
    private fun createButtonLabel(): Component {
        val color = if (isTranslatedView) ChatFormatting.GREEN else ChatFormatting.AQUA
        return Component.literal("[T]").withStyle(color).withStyle(ChatFormatting.BOLD)
    }

    @Unique
    private fun stellarOnToggleClick(button: Button) {
        val orig = originalAccess ?: return
        if (isTranslatedView) {
            isTranslatedView = false
            setBookAccess(orig)
            button.message = createButtonLabel()
        } else {
            val trans = translatedAccess
            if (trans != null) {
                isTranslatedView = true
                setBookAccess(trans)
                button.message = createButtonLabel()
            } else {
                fetchAndApplyTranslation(orig, button)
            }
        }
    }

    @Unique
    private fun fetchAndApplyTranslation(orig: BookViewScreen.BookAccess, button: Button) {
        BookTranslationManager.translateBookAsync(orig) { result ->
            if (result != null) {
                translatedAccess = result
                isTranslatedView = true
                minecraft.execute {
                    setBookAccess(result)
                    button.message = createButtonLabel()
                }
            }
        }
    }
}
