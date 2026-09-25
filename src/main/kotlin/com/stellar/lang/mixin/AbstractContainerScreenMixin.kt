package com.stellar.lang.mixin

import com.stellar.lang.container.ContainerTranslationManager
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyArg

/**
 * Mixin into AbstractContainerScreen to translate renamed container and inventory labels.
 */
@Suppress("UnusedPrivateMember", "MaxLineLength", "MaximumLineLength")
@Mixin(AbstractContainerScreen::class)
class AbstractContainerScreenMixin {
    @ModifyArg(
        method = ["extractLabels"],
        at = [
            At(
                value = "INVOKE",
                target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(" +
                    "Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            ),
        ],
        index = 1,
    )
    private fun stellarModifyContainerLabel(original: Component): Component {
        return ContainerTranslationManager.translateLabel(original)
    }
}
