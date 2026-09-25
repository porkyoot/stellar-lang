package com.stellar.lang.input

import com.mojang.blaze3d.platform.InputConstants
import com.stellar.core.input.KeyMappingRegistry
import com.stellar.lang.book.BookTranslationManager
import com.stellar.lang.book.RefreshableBookScreen
import com.stellar.lang.container.ContainerTranslationManager
import com.stellar.lang.entity.EntityTranslationManager
import com.stellar.lang.item.ItemTranslationManager
import com.stellar.lang.service.TranslationService
import com.stellar.lang.sign.SignTranslationManager
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.WritableBookItem
import net.minecraft.world.item.WrittenBookItem
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/**
 * Handles keyboard state polling and key press actions for Stellar Lang.
 */
@Suppress("TooManyFunctions")
object StellarLangInputHandler {
    const val GLFW_RELEASE: Int = 0
    const val GLFW_PRESS: Int = 1

    val retryTargetKeyMapping: KeyMapping = KeyMappingRegistry.register(
        name = "key.stellar.lang.retry_target",
        keyCode = InputConstants.KEY_PERIOD,
        category = KeyMappingRegistry.CATEGORY_STELLAR,
    )

    val showOriginalKeyMapping: KeyMapping = KeyMappingRegistry.register(
        name = "key.stellar.lang.show_original",
        keyCode = InputConstants.KEY_COMMA,
        category = KeyMappingRegistry.CATEGORY_STELLAR,
    )

    sealed class RefreshTarget {
        data class Sign(val sign: SignBlockEntity) : RefreshTarget()
        data class Lectern(val lectern: LecternBlockEntity) : RefreshTarget()
        data class EntityTarget(val entity: Entity) : RefreshTarget()
        data class HeldItem(val itemStack: ItemStack) : RefreshTarget()
        data class BookScreen(val screen: BookViewScreen) : RefreshTarget()
        data class ContainerScreenTarget(val screen: AbstractContainerScreen<*>) : RefreshTarget()
        data class ContainerBlockTarget(val blockEntity: BaseContainerBlockEntity) : RefreshTarget()
    }

    @Volatile
    var minecraftProvider: (() -> Minecraft?)? = null

    @Volatile
    var keyStateProvider: ((key: Int) -> Boolean)? = null

    @Volatile
    var windowProvider: (() -> com.mojang.blaze3d.platform.Window?)? = null

    @Volatile
    var targetProvider: (() -> RefreshTarget?)? = null

    @Volatile
    var screenProvider: (() -> Screen?)? = null

    @Volatile
    var crosshairEntityProvider: (() -> Entity?)? = null

    @Volatile
    var blockHitProvider: (() -> BlockHitResult?)? = null

    @Volatile
    var blockEntityProvider: ((BlockPos) -> BlockEntity?)? = null

    @Volatile
    var heldItemProvider: (() -> ItemStack?)? = null

    @Volatile
    var playerProvider: (() -> Player?)? = null

    fun clearProviders() {
        minecraftProvider = null
        keyStateProvider = null
        windowProvider = null
        targetProvider = null
        screenProvider = null
        crosshairEntityProvider = null
        blockHitProvider = null
        blockEntityProvider = null
        heldItemProvider = null
        playerProvider = null
    }

    private fun getMinecraft(): Minecraft? =
        minecraftProvider?.invoke() ?: runCatching { Minecraft.getInstance() }.getOrNull()

    internal fun checkKeyDown(window: com.mojang.blaze3d.platform.Window?, key: Int): Boolean {
        if (window == null) return false
        return runCatching {
            InputConstants.isKeyDown(window, key)
        }.getOrDefault(false)
    }

    /**
     * Returns true if the configured "show original" key (default: COMMA) is currently held down.
     */
    fun isShowingOriginal(): Boolean {
        val configKey = TranslationService.getConfig().showOriginalKey.value()
        val key = if (showOriginalKeyMapping.isDefault && configKey != showOriginalKeyMapping.defaultKey.value) {
            configKey
        } else {
            runCatching { KeyMappingRegistry.getBoundKeyCode(showOriginalKeyMapping) }.getOrDefault(configKey)
        }

        val provider = keyStateProvider
        if (provider != null) {
            return provider(key)
        }

        if (KeyMappingRegistry.isDown(showOriginalKeyMapping)) return true

        val window = windowProvider?.invoke() ?: getMinecraft()?.window
        return checkKeyDown(window, key)
    }

    /**
     * Handles key press events. Returns true if consumed.
     */
    fun onKey(key: Int, action: Int): Boolean {
        if (action != GLFW_PRESS) return false
        val config = TranslationService.getConfig()
        if (!config.enabled.value()) return false
        val matchesMapping = KeyMappingRegistry.matches(retryTargetKeyMapping, key)
        val matchesConfig = key == config.retryTargetKey.value()
        if (!matchesMapping && !matchesConfig) return false
        return retryTargetTranslation()
    }

    /**
     * Handles key press events with [KeyEvent]. Returns true if consumed.
     */
    fun onKey(event: KeyEvent, action: Int): Boolean {
        if (action != GLFW_PRESS) return false
        val config = TranslationService.getConfig()
        if (!config.enabled.value()) return false
        val matchesMapping = KeyMappingRegistry.matches(retryTargetKeyMapping, event)
        val matchesConfig = event.key() == config.retryTargetKey.value()
        if (!matchesMapping && !matchesConfig) return false
        return retryTargetTranslation()
    }

    /**
     * Resolves the current targeted object (sign, lectern, entity, held item, or active book screen).
     */
    fun resolveTarget(): RefreshTarget? {
        val custom = targetProvider?.invoke()
        if (custom != null) return custom

        val screen = screenProvider?.invoke() ?: runCatching { getMinecraft()?.gui?.screen() }.getOrNull()
        if (screen != null) {
            return when (screen) {
                is BookViewScreen -> RefreshTarget.BookScreen(screen)
                is AbstractContainerScreen<*> -> RefreshTarget.ContainerScreenTarget(screen)
                else -> null
            }
        }

        return resolveEntityTarget()
            ?: resolveBlockTarget()
            ?: resolveHeldItemTarget()
    }

    private fun resolveEntityTarget(): RefreshTarget.EntityTarget? {
        val entity = crosshairEntityProvider?.invoke()
            ?: runCatching {
                getMinecraft()?.let { mc ->
                    mc.crosshairPickEntity ?: (mc.hitResult as? EntityHitResult)?.entity
                }
            }.getOrNull()
        return entity?.let { RefreshTarget.EntityTarget(it) }
    }

    private fun resolveBlockTarget(): RefreshTarget? {
        val mcHit = runCatching { getMinecraft()?.hitResult as? BlockHitResult }.getOrNull()
        val hit = blockHitProvider?.invoke() ?: mcHit
        if (hit == null || hit.type != HitResult.Type.BLOCK) return null

        val blockEntity = blockEntityProvider?.invoke(hit.blockPos)
            ?: runCatching { getMinecraft()?.level?.getBlockEntity(hit.blockPos) }.getOrNull()
        return when (blockEntity) {
            is SignBlockEntity -> RefreshTarget.Sign(blockEntity)
            is LecternBlockEntity -> RefreshTarget.Lectern(blockEntity)
            is BaseContainerBlockEntity -> RefreshTarget.ContainerBlockTarget(blockEntity)
            else -> null
        }
    }

    private fun resolveHeldItemTarget(): RefreshTarget.HeldItem? {
        val held = heldItemProvider?.invoke() ?: getPlayerHeldItem()
        return if (held != null && !held.isEmpty) RefreshTarget.HeldItem(held) else null
    }

    private fun getPlayerHeldItem(): ItemStack? {
        val player = playerProvider?.invoke() ?: getMinecraft()?.player ?: return null
        val main = player.mainHandItem
        if (!main.isEmpty) return main
        val off = player.offhandItem
        if (!off.isEmpty) return off
        return null
    }

    /**
     * Retries or refreshes translation for the targeted object.
     */
    fun retryTargetTranslation(): Boolean {
        val target = resolveTarget() ?: return false
        return when (target) {
            is RefreshTarget.Sign -> SignTranslationManager.refreshSign(target.sign)
            is RefreshTarget.Lectern -> retryLectern(target.lectern)
            is RefreshTarget.EntityTarget -> EntityTranslationManager.refreshEntity(target.entity)
            is RefreshTarget.HeldItem -> retryHeldItem(target.itemStack)
            is RefreshTarget.BookScreen -> {
                (target.screen as? RefreshableBookScreen)?.stellarRefreshBook() ?: false
            }
            is RefreshTarget.ContainerScreenTarget -> ContainerTranslationManager.refreshScreen(target.screen)
            is RefreshTarget.ContainerBlockTarget -> ContainerTranslationManager.refreshBlockEntity(target.blockEntity)
        }
    }

    private fun retryLectern(lectern: LecternBlockEntity): Boolean {
        return if (lectern.hasBook()) {
            BookTranslationManager.refreshBook(lectern.book)
        } else {
            false
        }
    }

    private fun retryHeldItem(item: ItemStack): Boolean {
        val bookRefreshed = if (item.item is WritableBookItem || item.item is WrittenBookItem) {
            BookTranslationManager.refreshBook(item)
        } else {
            false
        }
        val itemRefreshed = ItemTranslationManager.refreshItem(item)
        return bookRefreshed || itemRefreshed
    }
}
