package com.stellar.lang.mixin

import com.stellar.lang.chat.ChatTranslationManager
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Style
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Intercepts chat message click events to toggle translation without sending commands to the server.
 */
@Suppress("UnusedPrivateMember")
@Mixin(ChatScreen::class)
abstract class ChatScreenMixin {
    @Inject(
        method = ["handleComponentClicked(Lnet/minecraft/network/chat/Style;Z)Z"],
        at = [At("HEAD")],
        cancellable = true,
    )
    private fun stellarOnHandleComponentClicked(
        style: Style?,
        isShift: Boolean,
        cir: CallbackInfoReturnable<Boolean>,
    ) {
        val event = style?.clickEvent
        if (event is ClickEvent.RunCommand && ChatTranslationManager.handleCommandClick(event.command())) {
            cir.returnValue = true
        }
    }
}
