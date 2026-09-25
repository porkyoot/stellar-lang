package com.stellar.lang.mixin

import com.stellar.core.input.KeyMappingRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Options
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin into [Options] to register Stellar mod key mappings into [Options.keyMappings].
 */
@Suppress("UnusedPrivateMember")
@Mixin(Options::class)
class OptionsMixin {
    @Mutable
    @Final
    @Shadow
    lateinit var keyMappings: Array<KeyMapping>

    @Inject(method = ["load"], at = [At("HEAD")])
    private fun stellarLangOnLoad(ci: CallbackInfo) {
        keyMappings = KeyMappingRegistry.process(keyMappings)
    }
}
