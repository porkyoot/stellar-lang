package com.stellar.lang.mixin

import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.gen.Invoker

/**
 * Accessor mixin for ChatComponent to update chat messages dynamically.
 */
@Mixin(ChatComponent::class)
interface ChatComponentAccessor {
    @Accessor("allMessages")
    fun stellarGetAllMessages(): MutableList<GuiMessage>

    @Invoker("refreshTrimmedMessages")
    fun stellarRefreshTrimmedMessages()
}
