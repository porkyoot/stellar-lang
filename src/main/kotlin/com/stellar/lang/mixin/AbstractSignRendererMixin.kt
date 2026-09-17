package com.stellar.lang.mixin

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.sign.SignTranslationManager
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer
import net.minecraft.client.renderer.blockentity.state.SignRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.Vec3
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin into AbstractSignRenderer to swap sign text with translated text unless original key is held.
 */
@Suppress("UnusedPrivateMember", "UnusedParameter", "LongParameterList")
@Mixin(AbstractSignRenderer::class)
class AbstractSignRendererMixin<S : SignRenderState> {
    @Inject(
        method = [
            "extractRenderState(Lnet/minecraft/world/level/block/entity/SignBlockEntity;" +
                "Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;F" +
                "Lnet/minecraft/world/phys/Vec3;" +
                "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer\$CrumblingOverlay;)V",
        ],
        at = [At("TAIL")],
    )
    private fun stellarOnExtractRenderState(
        sign: SignBlockEntity,
        state: S,
        tickProgress: Float,
        cameraPos: Vec3,
        overlay: ModelFeatureRenderer.CrumblingOverlay?,
        ci: CallbackInfo,
    ) {
        if (StellarLangInputHandler.isShowingOriginal()) {
            return
        }

        val translatedFront = SignTranslationManager.getOrRequestTranslatedSignText(sign, true)
        if (translatedFront != null) {
            state.frontText = translatedFront
        }
        val translatedBack = SignTranslationManager.getOrRequestTranslatedSignText(sign, false)
        if (translatedBack != null) {
            state.backText = translatedBack
        }
    }
}
