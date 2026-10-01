package com.stellar.lang.error

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component

/**
 * Dispatches SystemToast notifications to the Minecraft toast manager.
 * Isolated to allow headless unit testing without requiring an active Minecraft rendering context.
 */
object StellarToastHelper {
    fun showToast(title: Component, description: Component) {
        runCatching {
            val mc = Minecraft.getInstance()
            mc.execute {
                val toastManager = mc.gui.toastManager()
                SystemToast.addOrUpdate(
                    toastManager,
                    SystemToast.SystemToastId(),
                    title,
                    description,
                )
            }
        }
    }
}
