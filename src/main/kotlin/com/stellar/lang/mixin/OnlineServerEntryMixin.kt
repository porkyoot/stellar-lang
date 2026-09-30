package com.stellar.lang.mixin

import com.stellar.lang.motd.ServerMotdTranslationManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList
import net.minecraft.client.multiplayer.ServerData
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin to translate server MOTD in the multiplayer server selection list.
 */
@Suppress("UnusedPrivateMember")
@Mixin(ServerSelectionList.OnlineServerEntry::class)
class OnlineServerEntryMixin {
    @Shadow
    @Final
    private lateinit var serverData: ServerData

    @Suppress("LongParameterList")
    @Inject(
        method = [
            "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V",
        ],
        at = [At("HEAD")],
    )
    private fun stellarOnExtractContent(
        extractor: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        ServerMotdTranslationManager.processMotd(serverData)
    }
}
