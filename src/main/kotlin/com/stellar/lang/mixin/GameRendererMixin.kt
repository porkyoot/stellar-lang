package com.stellar.lang.mixin

import com.stellar.lang.target.TargetManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin into GameRenderer to capture targeted block and entity on crosshair pick.
 */
@Suppress("UnusedPrivateMember")
@Mixin(GameRenderer::class)
class GameRendererMixin {
    @Shadow
    @Final
    private lateinit var minecraft: Minecraft

    @Inject(method = ["pick(F)V"], at = [At("TAIL")])
    private fun stellarOnPick(partialTicks: Float, ci: CallbackInfo) {
        val hit = minecraft.hitResult
        val blockHit = hit as? BlockHitResult
        val entityHit = minecraft.crosshairPickEntity ?: (hit as? EntityHitResult)?.entity

        if (blockHit != null && blockHit.type == HitResult.Type.BLOCK) {
            TargetManager.updateTarget(
                blockPos = blockHit.blockPos,
                blockHit = blockHit,
                entity = entityHit,
            )
            return
        }

        if (entityHit != null) {
            TargetManager.updateTarget(
                blockPos = null,
                blockHit = null,
                entity = entityHit,
            )
            return
        }

        val cameraEntity = minecraft.cameraEntity
        if (canPickExtended(cameraEntity)) {
            pickExtendedTargets(cameraEntity!!, partialTicks)
        }
    }

    private fun canPickExtended(camera: Entity?): Boolean {
        if (camera == null) return false
        return minecraft.level != null && minecraft.player != null
    }

    private fun pickExtendedTargets(cameraEntity: Entity, partialTicks: Float) {
        val extendedBlockHit = cameraEntity.pick(TargetManager.EXTENDED_TARGET_RANGE, partialTicks, false)
        val blockPos = if (extendedBlockHit is BlockHitResult && extendedBlockHit.type == HitResult.Type.BLOCK) {
            extendedBlockHit.blockPos
        } else {
            null
        }

        val eyePos = cameraEntity.getEyePosition(partialTicks)
        val viewVec = cameraEntity.getViewVector(partialTicks)
        val reachVec = eyePos.add(viewVec.scale(TargetManager.EXTENDED_TARGET_RANGE))
        val box = cameraEntity.boundingBox.expandTowards(
            viewVec.scale(TargetManager.EXTENDED_TARGET_RANGE),
        ).inflate(1.0)

        val extendedEntityHit = ProjectileUtil.getEntityHitResult(
            cameraEntity,
            eyePos,
            reachVec,
            box,
            { !it.isSpectator && it.isPickable },
            TargetManager.EXTENDED_TARGET_RANGE * TargetManager.EXTENDED_TARGET_RANGE,
        )

        TargetManager.updateTarget(
            blockPos = blockPos,
            blockHit = extendedBlockHit as? BlockHitResult,
            entity = extendedEntityHit?.entity,
        )
    }
}
