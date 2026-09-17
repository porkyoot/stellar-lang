package com.stellar.lang.mixin

import com.stellar.lang.chat.ChatTranslationManager
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyVariable

/**
 * Mixin into ChatComponent to process incoming chat messages for translation.
 */
@Suppress("UnusedPrivateMember", "UnusedParameter")
@Mixin(ChatComponent::class)
class ChatComponentMixin {
    @ModifyVariable(
        method = [
            "addMessage(Lnet/minecraft/network/chat/Component;" +
                "Lnet/minecraft/network/chat/MessageSignature;" +
                "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;" +
                "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        ],
        at = [At("HEAD")],
        argsOnly = true,
        ordinal = 0,
    )
    private fun stellarOnAddMessage(message: Component): Component {
        return ChatTranslationManager.processIncomingMessage(message)
    }
}
