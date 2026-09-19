package com.stellar.lang.mixin

import com.stellar.lang.entity.EntityTranslationManager
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin into ClientLevel to trigger entity translation when entities are added to the client level.
 */
@Suppress("UnusedPrivateMember")
@Mixin(ClientLevel::class)
class ClientLevelMixin {
    @Inject(method = ["addEntity"], at = [At("TAIL")])
    private fun stellarOnAddEntity(entity: Entity, ci: CallbackInfo) {
        EntityTranslationManager.onEntityLoaded(entity)
    }
}
