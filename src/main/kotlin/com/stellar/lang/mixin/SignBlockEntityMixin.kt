package com.stellar.lang.mixin

import com.stellar.lang.sign.SignTranslationManager
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.storage.ValueInput
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Mixin into SignBlockEntity to trigger translation on load and on text change.
 */
@Suppress("UnusedPrivateMember")
@Mixin(SignBlockEntity::class)
class SignBlockEntityMixin {
    @Inject(method = ["loadAdditional"], at = [At("TAIL")])
    private fun stellarOnLoadAdditional(input: ValueInput, ci: CallbackInfo) {
        val self = this as Any as? SignBlockEntity ?: return
        SignTranslationManager.onSignLoaded(self)
    }

    @Inject(method = ["setText"], at = [At("TAIL")])
    private fun stellarOnSetText(signText: SignText, isFront: Boolean, cir: CallbackInfoReturnable<Boolean>) {
        SignTranslationManager.onSignTextChanged(signText)
    }
}
