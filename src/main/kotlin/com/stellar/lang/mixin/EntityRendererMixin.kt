package com.stellar.lang.mixin

import com.stellar.lang.entity.EntityTranslationManager
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Mixin into EntityRenderer to provide translated entity nametags.
 */
@Suppress("UnusedPrivateMember")
@Mixin(EntityRenderer::class)
class EntityRendererMixin<T : Entity, S : EntityRenderState> {
    @Inject(method = ["getNameTag"], at = [At("RETURN")], cancellable = true)
    private fun stellarOnGetNameTag(entity: T, cir: CallbackInfoReturnable<Component?>) {
        val original = cir.returnValue ?: return
        cir.returnValue = EntityTranslationManager.translateEntityName(entity, original)
    }
}
