package com.stellar.lang.mixin

import com.stellar.lang.map.MapBannerTranslationManager
import net.minecraft.client.renderer.MapRenderer
import net.minecraft.client.renderer.state.MapRenderState
import net.minecraft.world.level.saveddata.maps.MapDecoration
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Mixin into MapRenderer to translate named map decorations (e.g. banners).
 */
@Suppress("UnusedPrivateMember")
@Mixin(MapRenderer::class)
class MapRendererMixin {
    @Inject(method = ["extractDecorationRenderState"], at = [At("RETURN")])
    private fun stellarOnExtractDecorationRenderState(
        decoration: MapDecoration,
        cir: CallbackInfoReturnable<MapRenderState.MapDecorationRenderState>,
    ) {
        val state = cir.returnValue ?: return
        val name = state.name ?: return
        state.name = MapBannerTranslationManager.translateBannerName(name)
    }
}
