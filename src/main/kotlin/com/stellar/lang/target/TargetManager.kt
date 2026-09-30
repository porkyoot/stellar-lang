package com.stellar.lang.target

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * Tracks the targeted block and entity resolved by GameRendererMixin or Minecraft hit results.
 */
object TargetManager {
    const val EXTENDED_TARGET_RANGE: Double = 8.0

    @Volatile
    var targetedBlockPos: BlockPos? = null

    @Volatile
    var targetedBlockHit: BlockHitResult? = null

    @Volatile
    var targetedEntity: Entity? = null

    @Volatile
    var targetedBlockPosProvider: (() -> BlockPos?)? = null

    @Volatile
    var targetedBlockHitProvider: (() -> BlockHitResult?)? = null

    @Volatile
    var targetedEntityProvider: (() -> Entity?)? = null

    fun getTargetedBlockHit(mc: Minecraft? = null): BlockHitResult? {
        targetedBlockHitProvider?.invoke()?.let { return it }
        targetedBlockHit?.let { return it }
        val hit = mc?.hitResult ?: runCatching { Minecraft.getInstance().hitResult }.getOrNull()
        return (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
    }

    fun getTargetedBlock(mc: Minecraft? = null): BlockPos? {
        targetedBlockPosProvider?.invoke()?.let { return it }
        targetedBlockPos?.let { return it }
        return getTargetedBlockHit(mc)?.blockPos
    }

    fun getTargetedEntity(mc: Minecraft? = null): Entity? {
        targetedEntityProvider?.invoke()?.let { return it }
        targetedEntity?.let { return it }
        val client = mc ?: runCatching { Minecraft.getInstance() }.getOrNull()
        return client?.crosshairPickEntity ?: (client?.hitResult as? EntityHitResult)?.entity
    }

    fun isTargeted(pos: BlockPos, mc: Minecraft? = null): Boolean {
        return getTargetedBlock(mc) == pos
    }

    fun updateTarget(blockPos: BlockPos?, blockHit: BlockHitResult?, entity: Entity?) {
        targetedBlockPos = blockPos
        targetedBlockHit = blockHit
        targetedEntity = entity
    }

    fun clearTarget() {
        targetedBlockPos = null
        targetedBlockHit = null
        targetedEntity = null
        targetedBlockPosProvider = null
        targetedBlockHitProvider = null
        targetedEntityProvider = null
    }
}
