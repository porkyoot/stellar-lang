package com.stellar.lang.mixin

import com.stellar.lang.input.StellarLangInputHandler
import net.minecraft.client.KeyboardHandler
import net.minecraft.client.input.KeyEvent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin intercepting raw keyboard events to trigger translation retry/refresh.
 */
@Suppress("UnusedPrivateMember", "UnusedParameter")
@Mixin(KeyboardHandler::class)
class KeyboardHandlerMixin {
    @Inject(method = ["keyPress"], at = [At("HEAD")], cancellable = true)
    private fun stellarLangOnKeyPress(window: Long, action: Int, keyEvent: KeyEvent, ci: CallbackInfo) {
        if (StellarLangInputHandler.onKey(keyEvent, action)) {
            ci.cancel()
        }
    }
}
