package com.stellar.lang.input

import com.mojang.blaze3d.platform.InputConstants
import com.stellar.lang.service.TranslationService
import net.minecraft.client.Minecraft

/**
 * Handles keyboard state polling for Stellar Lang actions (such as showing original text).
 */
object StellarLangInputHandler {
    @Volatile
    var keyStateProvider: ((key: Int) -> Boolean)? = null

    @Volatile
    var windowProvider: (() -> com.mojang.blaze3d.platform.Window?)? = null

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
        val key = TranslationService.getConfig().showOriginalKey.value()
        val provider = keyStateProvider
        if (provider != null) return provider(key)

        val window = windowProvider?.invoke() ?: runCatching { Minecraft.getInstance().window }.getOrNull()
        return checkKeyDown(window, key)
    }
}
