package com.stellar.lang.target

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import sun.misc.Unsafe
import java.util.Optional

class TargetManagerSpec : FunSpec({
    beforeSpec {
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()
    }

    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null) as Unsafe

    fun createMockEntity(id: Int = 100): Entity {
        val accessorField = Entity::class.java.getDeclaredField("DATA_CUSTOM_NAME")
        accessorField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val accessor = accessorField.get(null) as EntityDataAccessor<Optional<Component>>

        val synchedData = unsafe.allocateInstance(SynchedEntityData::class.java) as SynchedEntityData
        val items = arrayOfNulls<SynchedEntityData.DataItem<*>>(accessor.id + 1)
        val dataItem = SynchedEntityData.DataItem(accessor, Optional.empty<Component>())
        items[accessor.id] = dataItem

        val itemsField = SynchedEntityData::class.java.getDeclaredField("itemsById")
        itemsField.isAccessible = true
        itemsField.set(synchedData, items)

        val entity = unsafe.allocateInstance(ArmorStand::class.java) as ArmorStand
        entity.setId(id)
        val entityDataField = Entity::class.java.getDeclaredField("entityData")
        entityDataField.isAccessible = true
        entityDataField.set(entity, synchedData)
        return entity
    }

    beforeEach {
        TargetManager.clearTarget()
    }

    afterEach {
        TargetManager.clearTarget()
    }

    test("TargetManager has extended target range constant") {
        TargetManager.EXTENDED_TARGET_RANGE shouldBe 8.0
    }

    test("updateTarget and clearTarget manage state properly") {
        val pos = BlockPos(1, 2, 3)
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, pos, false)
        val entity = createMockEntity(1)

        TargetManager.updateTarget(pos, hit, entity)
        TargetManager.targetedBlockPos shouldBe pos
        TargetManager.targetedBlockHit shouldBe hit
        TargetManager.targetedEntity shouldBe entity

        TargetManager.clearTarget()
        TargetManager.targetedBlockPos shouldBe null
        TargetManager.targetedBlockHit shouldBe null
        TargetManager.targetedEntity shouldBe null
        TargetManager.targetedBlockPosProvider shouldBe null
        TargetManager.targetedBlockHitProvider shouldBe null
        TargetManager.targetedEntityProvider shouldBe null
    }

    test("getTargetedBlock returns provider result first") {
        val providerPos = BlockPos(10, 20, 30)
        TargetManager.targetedBlockPos = BlockPos(1, 2, 3)
        TargetManager.targetedBlockPosProvider = { providerPos }

        TargetManager.getTargetedBlock() shouldBe providerPos
    }

    test("getTargetedBlock returns targetedBlockPos when provider is null") {
        val pos = BlockPos(5, 6, 7)
        TargetManager.targetedBlockPos = pos
        TargetManager.getTargetedBlock() shouldBe pos
    }

    test("getTargetedBlock falls back to getTargetedBlockHit") {
        val pos = BlockPos(8, 9, 10)
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, pos, false)
        TargetManager.targetedBlockHit = hit

        TargetManager.getTargetedBlock() shouldBe pos
    }

    test("getTargetedBlockHit returns provider result first") {
        val pos = BlockPos(1, 1, 1)
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, pos, false)
        TargetManager.targetedBlockHit = BlockHitResult(Vec3.ZERO, Direction.DOWN, BlockPos.ZERO, false)
        TargetManager.targetedBlockHitProvider = { hit }

        TargetManager.getTargetedBlockHit() shouldBe hit
    }

    test("getTargetedBlockHit falls back to mc.hitResult when block hit") {
        val pos = BlockPos(3, 3, 3)
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, pos, false)
        val mockMc = unsafe.allocateInstance(Minecraft::class.java) as Minecraft
        mockMc.hitResult = hit

        TargetManager.getTargetedBlockHit(mockMc) shouldBe hit
    }

    test("getTargetedBlockHit returns null when mc.hitResult is miss") {
        val miss = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO)
        val mockMc = unsafe.allocateInstance(Minecraft::class.java) as Minecraft
        mockMc.hitResult = miss

        TargetManager.getTargetedBlockHit(mockMc) shouldBe null
    }

    test("getTargetedBlockHit returns null when mc.hitResult is null and Minecraft instance is uninitialized") {
        TargetManager.getTargetedBlockHit(null) shouldBe null
    }

    test("getTargetedEntity returns provider result first") {
        val entity1 = createMockEntity(1)
        val entity2 = createMockEntity(2)
        TargetManager.targetedEntity = entity1
        TargetManager.targetedEntityProvider = { entity2 }

        TargetManager.getTargetedEntity() shouldBe entity2
    }

    test("getTargetedEntity returns targetedEntity when provider is null") {
        val entity = createMockEntity(1)
        TargetManager.targetedEntity = entity

        TargetManager.getTargetedEntity() shouldBe entity
    }

    test("getTargetedEntity falls back to mc.crosshairPickEntity") {
        val entity = createMockEntity(1)
        val mockMc = unsafe.allocateInstance(Minecraft::class.java) as Minecraft
        mockMc.crosshairPickEntity = entity

        TargetManager.getTargetedEntity(mockMc) shouldBe entity
    }

    test("getTargetedEntity falls back to mc.hitResult entity") {
        val entity = createMockEntity(1)
        val mockMc = unsafe.allocateInstance(Minecraft::class.java) as Minecraft
        mockMc.hitResult = EntityHitResult(entity)

        TargetManager.getTargetedEntity(mockMc) shouldBe entity
    }

    test("getTargetedEntity returns null when no entity is targeted") {
        val mockMc = unsafe.allocateInstance(Minecraft::class.java) as Minecraft
        mockMc.hitResult = null
        mockMc.crosshairPickEntity = null

        TargetManager.getTargetedEntity(mockMc) shouldBe null
    }

    test("isTargeted returns true when position matches targeted block") {
        val pos = BlockPos(12, 34, 56)
        TargetManager.targetedBlockPos = pos

        TargetManager.isTargeted(pos) shouldBe true
        TargetManager.isTargeted(BlockPos(0, 0, 0)) shouldBe false
    }

    test("getTargetedBlockHit handles miss hitResult and null client") {
        val mockMc = unsafe.allocateInstance(Minecraft::class.java) as Minecraft
        mockMc.hitResult = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO)
        TargetManager.getTargetedBlockHit(mockMc) shouldBe null

        TargetManager.getTargetedBlockHit(null) shouldBe null
        TargetManager.getTargetedEntity(null) shouldBe null
    }
})
