package com.stellar.lang.mixin

import com.stellar.lang.item.ItemTranslationManager
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Mixin into ItemStack to translate item hover and display names.
 */
@Suppress("UnusedPrivateMember")
@Mixin(ItemStack::class)
class ItemStackMixin {
    @Inject(method = ["getHoverName"], at = [At("RETURN")], cancellable = true)
    private fun stellarOnGetHoverName(cir: CallbackInfoReturnable<Component>) {
        val original = cir.returnValue ?: return
        cir.returnValue = ItemTranslationManager.translateItemName(this as Any as ItemStack, original)
    }
}
