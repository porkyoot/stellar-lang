package com.stellar.lang.mixin

import com.stellar.lang.entity.EntityTranslationManager
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin into Entity to trigger entity translation when custom name is set or updated.
 */
@Suppress("UnusedPrivateMember")
@Mixin(Entity::class)
abstract class EntityMixin {
    @Inject(method = ["setCustomName"], at = [At("TAIL")])
    private fun stellarOnSetCustomName(name: Component?, ci: CallbackInfo) {
        if (name != null) {
            EntityTranslationManager.onEntityNameChanged(name)
        }
    }

    @Inject(method = ["onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V"], at = [At("TAIL")])
    private fun stellarOnSyncedDataUpdated(accessor: EntityDataAccessor<*>, ci: CallbackInfo) {
        val self = this as Any as? Entity ?: return
        val name = self.customName
        if (name != null) {
            EntityTranslationManager.onEntityNameChanged(name)
        }
    }
}
