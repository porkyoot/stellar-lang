package com.stellar.lang.sign

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.state.HangingSignRenderState
import net.minecraft.client.renderer.blockentity.state.SignRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.HangingSignBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Collections
import java.util.WeakHashMap

/**
 * Utility for rendering in-game sign indicators and excess text tooltips/nametags.
 */
@Suppress("TooManyFunctions", "LargeClass")
object SignTooltipRenderer {
    internal const val DEFAULT_TOOLTIP_WRAP_LENGTH = 35
    internal const val NAMETAG_SCALE = 0.5f
    private const val ATTACHMENT_X = 0.5
    private const val ATTACHMENT_OVER_Y = 0.95
    private const val ATTACHMENT_Z = 0.5
    private const val BLOCK_CENTER_Y_OFFSET = 0.5
    private const val NAMETAG_LINE_HEIGHT = 10
    private const val LOOK_DISTANCE_BLOCKS = 8.0
    private const val INDICATOR_STANDING_Y = 24f
    private const val INDICATOR_HANGING_Y = -26f
    private const val INDICATOR_COLOR = 0xFF55FFFF.toInt()
    private const val INDICATOR_FAILED_COLOR = 0xFFFF5555.toInt()

    data class SignRenderOutcomeData(
        val frontOutcome: SignFormatHelper.SignTranslationOutcome?,
        val backOutcome: SignFormatHelper.SignTranslationOutcome?,
        val isFacingFront: Boolean,
        val isHanging: Boolean = false,
        val isFrontFailed: Boolean = false,
        val isBackFailed: Boolean = false,
    )

    private val renderStateData = Collections.synchronizedMap(
        WeakHashMap<SignRenderState, SignRenderOutcomeData>(),
    )

    fun storeRenderStateData(
        state: SignRenderState,
        data: SignRenderOutcomeData,
    ) {
        renderStateData[state] = data
    }

    fun getRenderStateData(
        state: SignRenderState,
    ): SignRenderOutcomeData? = renderStateData[state]

    fun wrapTooltipLines(text: String, maxCharsPerLine: Int = DEFAULT_TOOLTIP_WRAP_LENGTH): List<String> {
        val words = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            current = appendTooltipWord(lines, current, word, maxCharsPerLine)
        }
        if (current.isNotEmpty()) {
            lines.add(current.toString())
        }
        return lines
    }

    private fun appendTooltipWord(
        lines: MutableList<String>,
        current: StringBuilder,
        word: String,
        maxCharsPerLine: Int,
    ): StringBuilder {
        val chunks = if (word.length > maxCharsPerLine) word.chunked(maxCharsPerLine) else listOf(word)
        var builder = current
        for (chunk in chunks) {
            if (builder.isEmpty()) {
                builder.append(chunk)
            } else if (builder.length + chunk.length + 1 <= maxCharsPerLine) {
                builder.append(" ").append(chunk)
            } else {
                lines.add(builder.toString())
                builder = StringBuilder(chunk)
            }
        }
        return builder
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    fun renderSignIndicatorAndNametag(
        state: SignRenderState,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        cameraRenderState: CameraRenderState,
    ) {
        if (StellarLangInputHandler.isShowingOriginal()) return
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateSigns.value()) return

        val mc = runCatching { Minecraft.getInstance() }.getOrNull() ?: return
        if (mc.gui.hud.isHidden) return
        if (!isPlayerLookingAtSign(state, mc)) return

        val data = getRenderStateData(state) ?: resolveDataFromLevel(mc, state) ?: return
        val facingFront = isFacingFront(state, data, mc)
        val activeOutcome = if (facingFront) data.frontOutcome else data.backOutcome
        val isFailed = if (facingFront) data.isFrontFailed else data.isBackFailed
        if (activeOutcome == null && !isFailed) return

        // 1. Display [T] on the visible face using the same system as sign text
        renderSignIndicator(state, data, facingFront, poseStack, submitNodeCollector, mc, isFailed)

        // 2. Display nametag over the sign strictly on the visible face if text has overflow
        if (activeOutcome != null && activeOutcome.hasOverflow) {
            renderOverSignNametag(activeOutcome, state, poseStack, submitNodeCollector, cameraRenderState)
        }
    }

    fun renderSignNametagIfHighlighted(
        state: SignRenderState,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        cameraRenderState: CameraRenderState,
    ) {
        renderSignIndicatorAndNametag(state, poseStack, submitNodeCollector, cameraRenderState)
    }

    @Suppress("LongParameterList")
    private fun renderSignIndicator(
        state: SignRenderState,
        data: SignRenderOutcomeData,
        facingFront: Boolean,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        mc: Minecraft,
        isFailed: Boolean = false,
    ) {
        val transformations = state.transformations
        val transformation = if (facingFront) transformations.frontText() else transformations.backText()

        val isHanging = data.isHanging || state is HangingSignRenderState
        val textY = if (isHanging) INDICATOR_HANGING_Y else INDICATOR_STANDING_Y

        val indicator = com.stellar.lang.badge.TranslationBadgeHelper.createBadge(isFailed, trailingSpace = false)
        val font = mc.font
        val formattedCharSeq = indicator.visualOrderText
        val textWidth = font.width(formattedCharSeq)
        val textX = -textWidth / 2f
        val color = if (isFailed) INDICATOR_FAILED_COLOR else INDICATOR_COLOR

        poseStack.pushPose()
        poseStack.mulPose(transformation)
        submitNodeCollector.submitText(
            poseStack,
            textX,
            textY,
            formattedCharSeq,
            false,
            Font.DisplayMode.POLYGON_OFFSET,
            state.lightCoords,
            color,
            0,
            0,
        )
        poseStack.popPose()
    }

    @Suppress("LongParameterList")
    private fun renderOverSignNametag(
        outcome: SignFormatHelper.SignTranslationOutcome,
        state: SignRenderState,
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        cameraRenderState: CameraRenderState,
    ) {
        val fullText = outcome.fullTranslation.ifBlank { outcome.excessText.orEmpty() }
        val lines = wrapTooltipLines(fullText, DEFAULT_TOOLTIP_WRAP_LENGTH)
        if (lines.isEmpty()) return

        val attachment = Vec3(ATTACHMENT_X, ATTACHMENT_OVER_Y, ATTACHMENT_Z)
        for (i in lines.indices) {
            val lineOffset = (i - lines.size + 1) * NAMETAG_LINE_HEIGHT
            val component = Component.literal(lines[i]).withStyle(ChatFormatting.WHITE)
            submitScaledNameTag(
                poseStack,
                submitNodeCollector,
                attachment,
                lineOffset,
                component,
                state.lightCoords,
                cameraRenderState,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun submitScaledNameTag(
        poseStack: com.mojang.blaze3d.vertex.PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        attachment: Vec3,
        lineOffset: Int,
        component: Component,
        lightCoords: Int,
        cameraRenderState: CameraRenderState,
    ) {
        poseStack.pushPose()
        val tx = attachment.x
        val ty = attachment.y + BLOCK_CENTER_Y_OFFSET
        val tz = attachment.z
        poseStack.translate(tx, ty, tz)
        poseStack.scale(NAMETAG_SCALE, NAMETAG_SCALE, NAMETAG_SCALE)
        poseStack.translate(-tx, -ty, -tz)
        submitNodeCollector.submitNameTag(
            poseStack,
            attachment,
            lineOffset,
            component,
            false,
            lightCoords,
            cameraRenderState,
        )
        poseStack.popPose()
    }

    private fun resolveDataFromLevel(
        mc: Minecraft,
        state: SignRenderState,
    ): SignRenderOutcomeData? {
        val level = mc.level ?: return null
        val sign = level.getBlockEntity(state.blockPos) as? SignBlockEntity ?: return null
        val frontOutcome = SignTranslationManager.getOutcome(sign, true)
        val backOutcome = SignTranslationManager.getOutcome(sign, false)
        val isFrontFailed = frontOutcome == null && SignTranslationManager.isFailed(sign, true)
        val isBackFailed = backOutcome == null && SignTranslationManager.isFailed(sign, false)
        val isFront = mc.player?.let { sign.isFacingFrontText(it) } ?: true
        val isHanging = sign is HangingSignBlockEntity || state is HangingSignRenderState
        return SignRenderOutcomeData(
            frontOutcome = frontOutcome,
            backOutcome = backOutcome,
            isFacingFront = isFront,
            isHanging = isHanging,
            isFrontFailed = isFrontFailed,
            isBackFailed = isBackFailed,
        )
    }

    private fun isFacingFront(
        state: SignRenderState,
        data: SignRenderOutcomeData,
        mc: Minecraft,
    ): Boolean {
        val player = mc.player ?: return data.isFacingFront
        val sign = mc.level?.getBlockEntity(state.blockPos) as? SignBlockEntity
        return sign?.isFacingFrontText(player) ?: data.isFacingFront
    }

    private fun isPlayerLookingAtSign(
        state: SignRenderState,
        mc: Minecraft,
    ): Boolean {
        val hit = mc.hitResult
        if (hit is BlockHitResult && isDirectHit(hit, state)) {
            return true
        }

        val player = mc.player
        val level = mc.level
        return if (player != null && level != null) {
            hasLineOfSight(player, level, state)
        } else {
            false
        }
    }

    private fun hasLineOfSight(
        player: Player,
        level: Level,
        state: SignRenderState,
    ): Boolean {
        val eyePos = player.getEyePosition(1.0f)
        val lookVec = player.getViewVector(1.0f)
        val endPos = eyePos.add(lookVec.scale(LOOK_DISTANCE_BLOCKS))

        val clipResult = level.clip(
            ClipContext(
                eyePos,
                endPos,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                player,
            ),
        )
        if (clipResult.type == HitResult.Type.BLOCK && clipResult.blockPos == state.blockPos) {
            return true
        }

        val aabb = AABB(
            state.blockPos.x.toDouble(),
            state.blockPos.y.toDouble(),
            state.blockPos.z.toDouble(),
            state.blockPos.x + 1.0,
            state.blockPos.y + 1.0,
            state.blockPos.z + 1.0,
        )
        return clipResult.type == HitResult.Type.MISS && aabb.clip(eyePos, endPos).isPresent
    }

    private fun isDirectHit(hit: BlockHitResult, state: SignRenderState): Boolean =
        hit.type == HitResult.Type.BLOCK && hit.blockPos == state.blockPos

    @Suppress("UnusedParameter")
    fun renderSignTooltipIfLooking(extractor: GuiGraphicsExtractor) {
        // Obsolete 2D HUD tooltip: replaced by in-game nametag rendering
    }
}
