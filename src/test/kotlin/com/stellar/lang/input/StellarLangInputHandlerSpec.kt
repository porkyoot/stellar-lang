package com.stellar.lang.input

import com.stellar.core.input.Key
import com.stellar.lang.book.RefreshableBookScreen
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.SharedConstants
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponents
import net.minecraft.core.component.PatchedDataComponentMap
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityEquipment
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.WritableBookItem
import net.minecraft.world.item.WrittenBookItem
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import sun.misc.Unsafe
import java.util.Optional

@Suppress("LargeClass")
class StellarLangInputHandlerSpec : FunSpec({
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null) as Unsafe

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    beforeEach {
        StellarLangInputHandler.clearProviders()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.retryTargetKey.setValue(Key.KEY_PERIOD, false)
        config.showOriginalKey.setValue(Key.KEY_COMMA, false)
    }

    afterEach {
        StellarLangInputHandler.clearProviders()
    }

    fun createMockItem(customName: Component?, isBook: Boolean = false, isWritten: Boolean = false): ItemStack {
        val stack = unsafe.allocateInstance(ItemStack::class.java) as ItemStack
        val compField = ItemStack::class.java.getDeclaredField("components")
        compField.isAccessible = true
        val itemField = ItemStack::class.java.getDeclaredField("item")
        itemField.isAccessible = true
        val countField = ItemStack::class.java.getDeclaredField("count")
        countField.isAccessible = true

        val dummyItem = when {
            isWritten -> unsafe.allocateInstance(WrittenBookItem::class.java) as WrittenBookItem
            isBook -> unsafe.allocateInstance(WritableBookItem::class.java) as WritableBookItem
            else -> unsafe.allocateInstance(Item::class.java) as Item
        }
        itemField.set(stack, Holder.direct(dummyItem))
        countField.set(stack, 1)

        val map = PatchedDataComponentMap(DataComponentMap.EMPTY)
        if (customName != null) {
            map.set(DataComponents.CUSTOM_NAME, customName)
        }
        if (isBook) {
            map.set(
                DataComponents.WRITABLE_BOOK_CONTENT,
                net.minecraft.world.item.component.WritableBookContent.EMPTY,
            )
        }
        compField.set(stack, map)
        return stack
    }

    fun createMockEntity(name: Component?): Entity {
        val accessorField = Entity::class.java.getDeclaredField("DATA_CUSTOM_NAME")
        accessorField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val accessor = accessorField.get(null) as EntityDataAccessor<Optional<Component>>

        val synchedData = unsafe.allocateInstance(SynchedEntityData::class.java) as SynchedEntityData
        val items = arrayOfNulls<SynchedEntityData.DataItem<*>>(accessor.id + 1)
        val dataItem = SynchedEntityData.DataItem(accessor, Optional.ofNullable(name))
        items[accessor.id] = dataItem

        val itemsField = SynchedEntityData::class.java.getDeclaredField("itemsById")
        itemsField.isAccessible = true
        itemsField.set(synchedData, items)

        val entity = unsafe.allocateInstance(ArmorStand::class.java) as ArmorStand
        entity.setId(100)
        val entityDataField = Entity::class.java.getDeclaredField("entityData")
        entityDataField.isAccessible = true
        entityDataField.set(entity, synchedData)
        return entity
    }

    fun createMockSign(sentence: String): SignBlockEntity {
        val sign = unsafe.allocateInstance(SignBlockEntity::class.java) as SignBlockEntity
        val frontTextField = SignBlockEntity::class.java.getDeclaredField("frontText")
        frontTextField.isAccessible = true
        val backTextField = SignBlockEntity::class.java.getDeclaredField("backText")
        backTextField.isAccessible = true

        var signText = SignText()
        if (sentence.isNotBlank()) {
            signText = signText.setMessage(0, Component.literal(sentence))
        }
        frontTextField.set(sign, signText)
        backTextField.set(sign, SignText())
        return sign
    }

    fun createMockLectern(book: ItemStack?): LecternBlockEntity {
        val lectern = unsafe.allocateInstance(LecternBlockEntity::class.java) as LecternBlockEntity
        val bookField = LecternBlockEntity::class.java.getDeclaredField("book")
        bookField.isAccessible = true
        bookField.set(lectern, book ?: ItemStack.EMPTY)
        return lectern
    }

    fun createMockPlayer(main: ItemStack?, off: ItemStack?): LocalPlayer {
        val player = unsafe.allocateInstance(LocalPlayer::class.java) as LocalPlayer
        val equipment = EntityEquipment()
        if (main != null) equipment.set(EquipmentSlot.MAINHAND, main)
        if (off != null) equipment.set(EquipmentSlot.OFFHAND, off)
        val eqField = LivingEntity::class.java.getDeclaredField("equipment")
        eqField.isAccessible = true
        eqField.set(player, equipment)
        return player
    }

    test("isShowingOriginal uses keyStateProvider when provided") {
        StellarLangInputHandler.keyStateProvider = { key -> key == 44 }
        val config = TranslationService.getConfig()
        config.showOriginalKey.setValue(44, false)
        StellarLangInputHandler.isShowingOriginal() shouldBe true

        config.showOriginalKey.setValue(45, false)
        StellarLangInputHandler.isShowingOriginal() shouldBe false
    }

    test("isShowingOriginal falls back safely when windowProvider is null or returns null") {
        StellarLangInputHandler.keyStateProvider = null
        StellarLangInputHandler.windowProvider = { null }
        StellarLangInputHandler.isShowingOriginal() shouldBe false

        StellarLangInputHandler.windowProvider = null
        StellarLangInputHandler.isShowingOriginal() shouldBe false
        StellarLangInputHandler.checkKeyDown(null, 44) shouldBe false

        val dummyWindow = unsafe.allocateInstance(com.mojang.blaze3d.platform.Window::class.java)
            as com.mojang.blaze3d.platform.Window
        StellarLangInputHandler.checkKeyDown(dummyWindow, 44) shouldBe false

        StellarLangInputHandler.windowProvider = { dummyWindow }
        StellarLangInputHandler.isShowingOriginal() shouldBe false
    }

    test("onKey validates action, enabled flag, and key binding") {
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.retryTargetKey.setValue(Key.KEY_PERIOD, false)

        // Action not GLFW_PRESS
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, 0) shouldBe false
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, 2) shouldBe false

        // Config disabled
        config.enabled.setValue(false, false)
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, StellarLangInputHandler.GLFW_PRESS) shouldBe false

        // Different key
        config.enabled.setValue(true, false)
        StellarLangInputHandler.onKey(Key.KEY_COMMA, StellarLangInputHandler.GLFW_PRESS) shouldBe false

        // Correct key and action but no target
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, StellarLangInputHandler.GLFW_PRESS) shouldBe false

        // Correct key and action with target
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.Sign(createMockSign("Auberge"))
        }
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, StellarLangInputHandler.GLFW_PRESS) shouldBe true
    }

    test("onKey with KeyEvent validates action, enabled flag, key binding, and target") {
        val config = TranslationService.getConfig()
        val eventPeriod = net.minecraft.client.input.KeyEvent(Key.KEY_PERIOD, 0, 0)
        val eventComma = net.minecraft.client.input.KeyEvent(Key.KEY_COMMA, 0, 0)

        // Non-press action
        StellarLangInputHandler.onKey(eventPeriod, 0) shouldBe false
        StellarLangInputHandler.onKey(eventPeriod, 2) shouldBe false

        // Disabled config
        config.enabled.setValue(false, false)
        StellarLangInputHandler.onKey(eventPeriod, StellarLangInputHandler.GLFW_PRESS) shouldBe false

        // Different key
        config.enabled.setValue(true, false)
        StellarLangInputHandler.onKey(eventComma, StellarLangInputHandler.GLFW_PRESS) shouldBe false

        // Correct key and action but no target
        StellarLangInputHandler.onKey(eventPeriod, StellarLangInputHandler.GLFW_PRESS) shouldBe false

        // Correct key and action with target
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.Sign(createMockSign("Auberge"))
        }
        StellarLangInputHandler.onKey(eventPeriod, StellarLangInputHandler.GLFW_PRESS) shouldBe true
    }

    test("isShowingOriginal checks KeyMapping isDown state") {
        StellarLangInputHandler.keyStateProvider = null
        StellarLangInputHandler.windowProvider = null

        StellarLangInputHandler.showOriginalKeyMapping.setDown(true)
        StellarLangInputHandler.isShowingOriginal() shouldBe true

        StellarLangInputHandler.showOriginalKeyMapping.setDown(false)
        StellarLangInputHandler.isShowingOriginal() shouldBe false
    }

    test("key mapping properties are registered and accessible") {
        StellarLangInputHandler.retryTargetKeyMapping.name shouldBe "key.stellar.lang.retry_target"
        StellarLangInputHandler.showOriginalKeyMapping.name shouldBe "key.stellar.lang.show_original"
    }

    test("isShowingOriginal handles rebound showOriginalKeyMapping") {
        StellarLangInputHandler.keyStateProvider = { key -> key == Key.KEY_H }
        val reboundKey = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.h")
        StellarLangInputHandler.showOriginalKeyMapping.setKey(reboundKey)

        StellarLangInputHandler.isShowingOriginal() shouldBe true

        StellarLangInputHandler.showOriginalKeyMapping.setKey(StellarLangInputHandler.showOriginalKeyMapping.defaultKey)
    }

    test("onKey handles GLFW actions, config enabled, and key matching branches") {
        val config = TranslationService.getConfig()
        val sign = createMockSign("Auberge")
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.Sign(sign) }

        val release = StellarLangInputHandler.GLFW_RELEASE
        val press = StellarLangInputHandler.GLFW_PRESS
        val eventPeriod = KeyEvent(Key.KEY_PERIOD, 0, 0)
        val eventX = KeyEvent(Key.KEY_X, 0, 0)
        val eventR = KeyEvent(Key.KEY_R, 0, 0)

        // Action not PRESS
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, release) shouldBe false
        StellarLangInputHandler.onKey(eventPeriod, release) shouldBe false

        // Config disabled
        config.enabled.setValue(false, false)
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, press) shouldBe false
        StellarLangInputHandler.onKey(eventPeriod, press) shouldBe false
        config.enabled.setValue(true, false)

        // Non-matching key
        StellarLangInputHandler.onKey(Key.KEY_X, press) shouldBe false
        StellarLangInputHandler.onKey(eventX, press) shouldBe false

        // Matches mapping (PERIOD) while config is changed to R
        config.retryTargetKey.setValue(Key.KEY_R, false)
        StellarLangInputHandler.onKey(Key.KEY_PERIOD, press) shouldBe true
        StellarLangInputHandler.onKey(eventPeriod, press) shouldBe true

        // Matches config (R) while mapping is default (PERIOD)
        StellarLangInputHandler.onKey(Key.KEY_R, press) shouldBe true
        StellarLangInputHandler.onKey(eventR, press) shouldBe true

        // Reset
        config.retryTargetKey.setValue(Key.KEY_PERIOD, false)
    }

    test("checkKeyDown handles null window and window fallback safely") {
        StellarLangInputHandler.checkKeyDown(null, Key.KEY_COMMA) shouldBe false

        val dummyWindow = unsafe.allocateInstance(com.mojang.blaze3d.platform.Window::class.java)
            as com.mojang.blaze3d.platform.Window
        StellarLangInputHandler.checkKeyDown(dummyWindow, Key.KEY_COMMA) shouldBe false

        StellarLangInputHandler.windowProvider = { dummyWindow }
        StellarLangInputHandler.keyStateProvider = null
        StellarLangInputHandler.showOriginalKeyMapping.setDown(false)
        StellarLangInputHandler.isShowingOriginal() shouldBe false

        // Test branch when configKey is not equal to defaultKey
        val config = TranslationService.getConfig()
        config.showOriginalKey.setValue(Key.KEY_X, false)
        StellarLangInputHandler.keyStateProvider = { key -> key == Key.KEY_X }
        StellarLangInputHandler.isShowingOriginal() shouldBe true
        config.showOriginalKey.setValue(Key.KEY_COMMA, false)
    }

    test("resolveBlockTarget handles missing or non-block hit result and block entity variants") {
        StellarLangInputHandler.blockHitProvider = { null }
        StellarLangInputHandler.minecraftProvider = { null }
        StellarLangInputHandler.resolveTarget() shouldBe null

        val missHit = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO)
        StellarLangInputHandler.blockHitProvider = { missHit }
        StellarLangInputHandler.resolveTarget() shouldBe null

        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false)
        StellarLangInputHandler.blockHitProvider = { hit }

        val lectern = createMockLectern(null)
        StellarLangInputHandler.blockEntityProvider = { lectern }
        val targetLectern = StellarLangInputHandler.resolveTarget()
        (targetLectern is StellarLangInputHandler.RefreshTarget.Lectern) shouldBe true

        val chestEntity = unsafe.allocateInstance(
            net.minecraft.world.level.block.entity.ChestBlockEntity::class.java,
        ) as net.minecraft.world.level.block.entity.ChestBlockEntity
        StellarLangInputHandler.blockEntityProvider = { chestEntity }
        val targetChest = StellarLangInputHandler.resolveTarget()
        (targetChest is StellarLangInputHandler.RefreshTarget.ContainerBlockTarget) shouldBe true

        val bellEntity = unsafe.allocateInstance(
            net.minecraft.world.level.block.entity.BellBlockEntity::class.java,
        ) as net.minecraft.world.level.block.entity.BellBlockEntity
        StellarLangInputHandler.blockEntityProvider = { bellEntity }
        StellarLangInputHandler.resolveTarget() shouldBe null

        StellarLangInputHandler.blockEntityProvider = { null }
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveEntityTarget handles crosshairPickEntity and hitResult entity fallbacks") {
        val entity1 = createMockEntity(Component.literal("Cow"))
        val mockMc = unsafe.allocateInstance(net.minecraft.client.Minecraft::class.java)
            as net.minecraft.client.Minecraft
        mockMc.crosshairPickEntity = entity1
        StellarLangInputHandler.minecraftProvider = { mockMc }
        StellarLangInputHandler.crosshairEntityProvider = null

        val target1 = StellarLangInputHandler.resolveTarget()
        (target1 is StellarLangInputHandler.RefreshTarget.EntityTarget) shouldBe true
        (target1 as StellarLangInputHandler.RefreshTarget.EntityTarget).entity shouldBe entity1

        mockMc.crosshairPickEntity = null
        val entity2 = createMockEntity(Component.literal("Pig"))
        mockMc.hitResult = EntityHitResult(entity2)
        val target2 = StellarLangInputHandler.resolveTarget()
        (target2 is StellarLangInputHandler.RefreshTarget.EntityTarget) shouldBe true
        (target2 as StellarLangInputHandler.RefreshTarget.EntityTarget).entity shouldBe entity2
    }

    test("getPlayerHeldItem handles empty mainhand and non-empty offhand") {
        val offItem = createMockItem(Component.literal("Shield"))
        val player = createMockPlayer(main = ItemStack.EMPTY, off = offItem)
        StellarLangInputHandler.playerProvider = { player }

        val target = StellarLangInputHandler.resolveTarget()
        (target is StellarLangInputHandler.RefreshTarget.HeldItem) shouldBe true
        (target as StellarLangInputHandler.RefreshTarget.HeldItem).itemStack shouldBe offItem

        val emptyPlayer = createMockPlayer(main = ItemStack.EMPTY, off = ItemStack.EMPTY)
        StellarLangInputHandler.playerProvider = { emptyPlayer }
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveBlockTarget falls back to minecraft level block entity") {
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false)
        StellarLangInputHandler.blockHitProvider = { hit }
        StellarLangInputHandler.blockEntityProvider = null

        val mockLevel = unsafe.allocateInstance(net.minecraft.client.multiplayer.ClientLevel::class.java)
            as net.minecraft.client.multiplayer.ClientLevel
        val mockMc = unsafe.allocateInstance(net.minecraft.client.Minecraft::class.java)
            as net.minecraft.client.Minecraft
        val levelField = net.minecraft.client.Minecraft::class.java.getDeclaredField("level")
        levelField.isAccessible = true
        levelField.set(mockMc, mockLevel)

        StellarLangInputHandler.minecraftProvider = { mockMc }
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveBlockTarget falls back to minecraft hitResult when blockHitProvider is null") {
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false)
        val mockMc = unsafe.allocateInstance(net.minecraft.client.Minecraft::class.java)
            as net.minecraft.client.Minecraft
        mockMc.hitResult = hit
        StellarLangInputHandler.minecraftProvider = { mockMc }
        StellarLangInputHandler.blockHitProvider = null
        val sign = createMockSign("Auberge")
        StellarLangInputHandler.blockEntityProvider = { sign }
        val target = StellarLangInputHandler.resolveTarget()
        (target is StellarLangInputHandler.RefreshTarget.Sign) shouldBe true
    }

    test("resolveHeldItemTarget handles empty itemStack from provider") {
        StellarLangInputHandler.heldItemProvider = { ItemStack.EMPTY }
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveTarget handles completely unprovided minecraft instance safely") {
        StellarLangInputHandler.minecraftProvider = null
        StellarLangInputHandler.blockHitProvider = null
        StellarLangInputHandler.crosshairEntityProvider = null
        StellarLangInputHandler.heldItemProvider = null
        StellarLangInputHandler.playerProvider = null
        StellarLangInputHandler.screenProvider = null
        StellarLangInputHandler.targetProvider = null
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveTarget prioritizes custom targetProvider") {
        val custom = StellarLangInputHandler.RefreshTarget.Sign(createMockSign("Test"))
        StellarLangInputHandler.targetProvider = { custom }
        StellarLangInputHandler.resolveTarget() shouldBe custom
    }

    test("resolveTarget handles screenProvider") {
        // BookViewScreen allocated without constructor
        val bookScreen = unsafe.allocateInstance(BookViewScreen::class.java) as BookViewScreen
        StellarLangInputHandler.screenProvider = { bookScreen }
        val target1 = StellarLangInputHandler.resolveTarget()
        (target1 is StellarLangInputHandler.RefreshTarget.BookScreen) shouldBe true

        // Generic Screen returns null to prevent retry while in chat/other UI
        class GenericTestScreen : Screen(Component.literal("Menu"))
        val genericScreen = unsafe.allocateInstance(GenericTestScreen::class.java) as Screen
        StellarLangInputHandler.screenProvider = { genericScreen }
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveTarget resolves crosshair entity") {
        val entity = createMockEntity(Component.literal("Guard"))
        StellarLangInputHandler.crosshairEntityProvider = { entity }
        val target = StellarLangInputHandler.resolveTarget()
        (target is StellarLangInputHandler.RefreshTarget.EntityTarget) shouldBe true
        (target as StellarLangInputHandler.RefreshTarget.EntityTarget).entity shouldBe entity
    }

    test("resolveTarget resolves block hit for sign and lectern") {
        val hit = BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false)
        StellarLangInputHandler.blockHitProvider = { hit }

        val sign = createMockSign("Bienvenue")
        StellarLangInputHandler.blockEntityProvider = { sign }
        val signTarget = StellarLangInputHandler.resolveTarget()
        (signTarget is StellarLangInputHandler.RefreshTarget.Sign) shouldBe true
        (signTarget as StellarLangInputHandler.RefreshTarget.Sign).sign shouldBe sign

        val lectern = createMockLectern(null)
        StellarLangInputHandler.blockEntityProvider = { lectern }
        val lecternTarget = StellarLangInputHandler.resolveTarget()
        (lecternTarget is StellarLangInputHandler.RefreshTarget.Lectern) shouldBe true
        (lecternTarget as StellarLangInputHandler.RefreshTarget.Lectern).lectern shouldBe lectern

        // Unknown block entity returns null
        val dummyBlockEntity = unsafe.allocateInstance(
            net.minecraft.world.level.block.entity.EnderChestBlockEntity::class.java,
        ) as BlockEntity
        StellarLangInputHandler.blockEntityProvider = { dummyBlockEntity }
        StellarLangInputHandler.resolveTarget() shouldBe null

        // Miss hit skips block resolution
        val missHit = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO)
        StellarLangInputHandler.blockHitProvider = { missHit }
        StellarLangInputHandler.blockEntityProvider = { sign }
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveTarget resolves held item directly and via player fallback") {
        val item = createMockItem(Component.literal("Epee magique"))
        StellarLangInputHandler.heldItemProvider = { item }
        val heldTarget = StellarLangInputHandler.resolveTarget()
        (heldTarget is StellarLangInputHandler.RefreshTarget.HeldItem) shouldBe true
        (heldTarget as StellarLangInputHandler.RefreshTarget.HeldItem).itemStack shouldBe item

        // Empty held item provider falls back
        StellarLangInputHandler.heldItemProvider = { ItemStack.EMPTY }
        StellarLangInputHandler.resolveTarget() shouldBe null

        StellarLangInputHandler.heldItemProvider = null

        // Main hand item
        val mainPlayer = createMockPlayer(main = item, off = null)
        StellarLangInputHandler.playerProvider = { mainPlayer }
        val mainTarget = StellarLangInputHandler.resolveTarget()
        (mainTarget is StellarLangInputHandler.RefreshTarget.HeldItem) shouldBe true
        (mainTarget as StellarLangInputHandler.RefreshTarget.HeldItem).itemStack shouldBe item

        // Offhand item when main hand is empty
        val offItem = createMockItem(Component.literal("Bouclier"))
        val offPlayer = createMockPlayer(main = ItemStack.EMPTY, off = offItem)
        StellarLangInputHandler.playerProvider = { offPlayer }
        val offTarget = StellarLangInputHandler.resolveTarget()
        (offTarget is StellarLangInputHandler.RefreshTarget.HeldItem) shouldBe true
        (offTarget as StellarLangInputHandler.RefreshTarget.HeldItem).itemStack shouldBe offItem

        // Both hands empty
        val emptyPlayer = createMockPlayer(main = ItemStack.EMPTY, off = ItemStack.EMPTY)
        StellarLangInputHandler.playerProvider = { emptyPlayer }
        StellarLangInputHandler.resolveTarget() shouldBe null

        // Player provider returns null
        StellarLangInputHandler.playerProvider = { null }
        StellarLangInputHandler.resolveTarget() shouldBe null
    }

    test("resolveTarget resolves target via Minecraft fallback instance") {
        val entity = createMockEntity(Component.literal("Villager"))
        val mc = unsafe.allocateInstance(net.minecraft.client.Minecraft::class.java) as net.minecraft.client.Minecraft
        mc.crosshairPickEntity = entity
        StellarLangInputHandler.minecraftProvider = { mc }

        val targetEntity = StellarLangInputHandler.resolveTarget()
        (targetEntity is StellarLangInputHandler.RefreshTarget.EntityTarget) shouldBe true

        // Hit result with EntityHitResult
        mc.crosshairPickEntity = null
        mc.hitResult = net.minecraft.world.phys.EntityHitResult(entity)
        val targetEntity2 = StellarLangInputHandler.resolveTarget()
        (targetEntity2 is StellarLangInputHandler.RefreshTarget.EntityTarget) shouldBe true

        // Block hit via Minecraft
        mc.hitResult = BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false)
        val sign = createMockSign("Taverne")
        StellarLangInputHandler.blockEntityProvider = { sign }
        val targetBlock = StellarLangInputHandler.resolveTarget()
        (targetBlock is StellarLangInputHandler.RefreshTarget.Sign) shouldBe true

        // Held item via Minecraft player
        mc.hitResult = null
        val item = createMockItem(Component.literal("Baguette"))
        mc.player = createMockPlayer(main = item, off = null)
        val targetHeld = StellarLangInputHandler.resolveTarget()
        (targetHeld is StellarLangInputHandler.RefreshTarget.HeldItem) shouldBe true
    }

    test("retryTargetTranslation handles each RefreshTarget variant") {
        // 1. Sign
        val validSign = createMockSign("Bonjour le monde")
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.Sign(validSign) }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        val blankSign = createMockSign("   ")
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.Sign(blankSign) }
        StellarLangInputHandler.retryTargetTranslation() shouldBe false

        // 2. Lectern
        val emptyLectern = createMockLectern(null)
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.Lectern(emptyLectern) }
        StellarLangInputHandler.retryTargetTranslation() shouldBe false

        val bookLectern = createMockLectern(createMockItem(Component.literal("Livre"), isBook = true))
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.Lectern(bookLectern) }
        // In headless mode without Minecraft instance, BookAccess.fromItem returns null
        StellarLangInputHandler.retryTargetTranslation() shouldBe false

        // 3. Entity
        val validEntity = createMockEntity(Component.literal("Vendeur"))
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.EntityTarget(validEntity)
        }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        val blankEntity = createMockEntity(Component.literal("   "))
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.EntityTarget(blankEntity)
        }
        StellarLangInputHandler.retryTargetTranslation() shouldBe false

        // 4. Held item
        val normalItem = createMockItem(Component.literal("Hache"))
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.HeldItem(normalItem) }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        val bookItem = createMockItem(Component.literal("Grimoire"), isBook = true)
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.HeldItem(bookItem) }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        val writtenItem = createMockItem(Component.literal("Tome"), isWritten = true)
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.HeldItem(writtenItem) }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        val unnamedItem = createMockItem(null)
        StellarLangInputHandler.targetProvider = { StellarLangInputHandler.RefreshTarget.HeldItem(unnamedItem) }
        StellarLangInputHandler.retryTargetTranslation() shouldBe false

        // 5. BookScreen
        class TestRefreshableScreen : BookViewScreen(), RefreshableBookScreen {
            var res: Boolean = false
            override fun stellarGetBookAccess(): BookAccess? = null
            override fun stellarRefreshBook(): Boolean = res
        }
        val refreshableTrue = unsafe.allocateInstance(TestRefreshableScreen::class.java) as TestRefreshableScreen
        refreshableTrue.res = true
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.BookScreen(refreshableTrue)
        }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        val refreshableFalse = unsafe.allocateInstance(TestRefreshableScreen::class.java) as TestRefreshableScreen
        refreshableFalse.res = false
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.BookScreen(refreshableFalse)
        }
        StellarLangInputHandler.retryTargetTranslation() shouldBe false

        val plainBookScreen = unsafe.allocateInstance(BookViewScreen::class.java) as BookViewScreen
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.BookScreen(plainBookScreen)
        }
        StellarLangInputHandler.retryTargetTranslation() shouldBe false

        // 6. Container screen target
        class TestContainerScreen : AbstractContainerScreen<AbstractContainerMenu>(
            null as Any as AbstractContainerMenu,
            null as Any as net.minecraft.world.entity.player.Inventory,
            Component.empty(),
        )
        val containerScreen = unsafe.allocateInstance(TestContainerScreen::class.java)
            as AbstractContainerScreen<AbstractContainerMenu>
        val titleField = net.minecraft.client.gui.screens.Screen::class.java.getDeclaredField("title")
        titleField.isAccessible = true
        titleField.set(containerScreen, Component.literal("Coffre mystère"))
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.ContainerScreenTarget(containerScreen)
        }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        // 7. Container block entity target
        val containerBlock = unsafe.allocateInstance(
            net.minecraft.world.level.block.entity.ChestBlockEntity::class.java,
        ) as BaseContainerBlockEntity
        val nameField = BaseContainerBlockEntity::class.java.getDeclaredField("name")
        nameField.isAccessible = true
        nameField.set(containerBlock, Component.literal("Coffre au trésor"))
        StellarLangInputHandler.targetProvider = {
            StellarLangInputHandler.RefreshTarget.ContainerBlockTarget(containerBlock)
        }
        StellarLangInputHandler.retryTargetTranslation() shouldBe true

        // 8. Screen provider returns AbstractContainerScreen
        StellarLangInputHandler.targetProvider = null
        StellarLangInputHandler.screenProvider = { containerScreen }
        val resolved = StellarLangInputHandler.resolveTarget()
        (resolved is StellarLangInputHandler.RefreshTarget.ContainerScreenTarget) shouldBe true

        // 9. Block entity provider returns BaseContainerBlockEntity
        StellarLangInputHandler.screenProvider = null
        StellarLangInputHandler.blockHitProvider = {
            net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.core.Direction.UP,
                net.minecraft.core.BlockPos.ZERO,
                false,
            )
        }
        StellarLangInputHandler.blockEntityProvider = { containerBlock }
        val blockResolved = StellarLangInputHandler.resolveTarget()
        (blockResolved is StellarLangInputHandler.RefreshTarget.ContainerBlockTarget) shouldBe true

        // 10. No target
        StellarLangInputHandler.blockHitProvider = null
        StellarLangInputHandler.blockEntityProvider = null
        StellarLangInputHandler.targetProvider = { null }
        StellarLangInputHandler.retryTargetTranslation() shouldBe false
    }

    test("clearProviders resets all registered providers") {
        StellarLangInputHandler.minecraftProvider = { null }
        StellarLangInputHandler.minecraftProvider shouldNotBe null
        StellarLangInputHandler.keyStateProvider = { true }
        StellarLangInputHandler.windowProvider = { null }
        StellarLangInputHandler.targetProvider = { null }
        StellarLangInputHandler.screenProvider = { null }
        StellarLangInputHandler.crosshairEntityProvider = { null }
        StellarLangInputHandler.blockHitProvider = { null }
        StellarLangInputHandler.blockEntityProvider = { null }
        StellarLangInputHandler.heldItemProvider = { null }
        StellarLangInputHandler.playerProvider = { null }

        StellarLangInputHandler.clearProviders()

        StellarLangInputHandler.minecraftProvider shouldBe null
        StellarLangInputHandler.keyStateProvider shouldBe null
        StellarLangInputHandler.windowProvider shouldBe null
        StellarLangInputHandler.targetProvider shouldBe null
        StellarLangInputHandler.screenProvider shouldBe null
        StellarLangInputHandler.crosshairEntityProvider shouldBe null
        StellarLangInputHandler.blockHitProvider shouldBe null
        StellarLangInputHandler.blockEntityProvider shouldBe null
        StellarLangInputHandler.heldItemProvider shouldBe null
        StellarLangInputHandler.playerProvider shouldBe null
    }
})
