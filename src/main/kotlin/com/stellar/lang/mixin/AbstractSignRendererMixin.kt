package com.stellar.lang.mixin

import com.mojang.blaze3d.vertex.PoseStack
import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.sign.SignTooltipRenderer
import com.stellar.lang.sign.SignTranslationManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer
import net.minecraft.client.renderer.blockentity.state.HangingSignRenderState
import net.minecraft.client.renderer.blockentity.state.SignRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.level.block.entity.HangingSignBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.phys.Vec3
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin into AbstractSignRenderer to swap sign text with translated text and render in-game nametag.
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

        val frontOutcome = SignTranslationManager.getOutcome(sign, true)
        if (frontOutcome != null) {
            state.frontText = copyAppearance(sign.frontText, frontOutcome.signText)
        } else {
            SignTranslationManager.translateSignText(sign.frontText)
        }

        val backOutcome = SignTranslationManager.getOutcome(sign, false)
        if (backOutcome != null) {
            state.backText = copyAppearance(sign.backText, backOutcome.signText)
        } else {
            SignTranslationManager.translateSignText(sign.backText)
        }

        val player = runCatching { Minecraft.getInstance().player }.getOrNull()
        val isFacingFront = player?.let { sign.isFacingFrontText(it) } ?: true
        val isHanging = sign is HangingSignBlockEntity || state is HangingSignRenderState

        SignTooltipRenderer.storeRenderStateData(
            state,
            SignTooltipRenderer.SignRenderOutcomeData(
                frontOutcome = frontOutcome,
                backOutcome = backOutcome,
                isFacingFront = isFacingFront,
                isHanging = isHanging,
            ),
        )
    }

    private fun copyAppearance(original: SignText, translated: SignText): SignText {
        var result = original
        for (i in 0 until SignText.LINES) {
            result = result.setMessage(i, translated.getMessage(i, false))
        }
        return result
    }

    @Inject(
        method = [
            "submit(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;" +
                "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                "Lnet/minecraft/client/renderer/SubmitNodeCollector;" +
                "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        ],
        at = [At("TAIL")],
    )
    private fun stellarOnSubmit(
        state: S,
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        cameraRenderState: CameraRenderState,
        ci: CallbackInfo,
    ) {
        SignTooltipRenderer.renderSignIndicatorAndNametag(
            state,
            poseStack,
            submitNodeCollector,
            cameraRenderState,
        )
    }
}
