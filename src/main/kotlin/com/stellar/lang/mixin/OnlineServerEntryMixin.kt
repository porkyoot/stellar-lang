@file:Suppress(
    "UnusedPrivateMember",
    "UnusedParameter",
    "LongParameterList",
    "TooGenericExceptionCaught",
)

package com.stellar.lang.mixin

import com.stellar.lang.geoip.ServerFlagManager
import com.stellar.lang.geoip.ServerFlagRenderHelper
import com.stellar.lang.motd.ServerMotdTranslationManager
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList
import net.minecraft.client.multiplayer.ServerData
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Mixin to display GeoIP country flag badges under the ping indicator and translate MOTD.
 */
@Mixin(ServerSelectionList.OnlineServerEntry::class)
class OnlineServerEntryMixin {
    @Shadow
    @Final
    private lateinit var serverData: ServerData

    @Unique
    private var lastMouseX: Int = 0

    @Unique
    private var lastMouseY: Int = 0

    @Inject(
        method = [
            "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V",
        ],
        at = [At("HEAD")],
        require = 0,
    )
    private fun stellarOnExtractContent(
        extractor: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        runCatching {
            lastMouseX = x
            lastMouseY = y
            if (::serverData.isInitialized) {
                ServerMotdTranslationManager.processMotd(serverData)
                ServerFlagManager.processServer(serverData)
            }
        }
    }

    @Inject(
        method = [
            "extractContent(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIZF)V",
        ],
        at = [At("TAIL")],
        require = 0,
    )
    private fun stellarRenderTail(
        extractor: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        runCatching {
            if (::serverData.isInitialized) {
                ServerFlagRenderHelper.renderFlagUnderPing(this, serverData, extractor, lastMouseX, lastMouseY)
            }
        }
    }
}
