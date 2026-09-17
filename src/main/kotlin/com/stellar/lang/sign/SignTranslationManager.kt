package com.stellar.lang.sign

import com.stellar.lang.service.TranslationService
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation for signs with multi-line joining and nearby spatial context.
 */
object SignTranslationManager {
    private const val MAX_LINE_LENGTH = 15
    private const val AREA_RADIUS = 5
    private const val VERTICAL_RADIUS = 2
    private const val MAX_NEARBY_CONTEXT_SIGNS = 3
    private val signCache = ConcurrentHashMap<String, SignText>()

    @Volatile
    var nearbyContextProvider: ((centerPos: BlockPos) -> String)? = null

    fun getOrRequestTranslatedSignText(sign: SignBlockEntity, isFront: Boolean): SignText? {
        val sentence = extractSignSentence(sign, isFront) ?: return null
        val config = TranslationService.getConfig()
        val targetLang = config.targetLanguage.value()
        val pos = sign.blockPos
        val sideKey = if (isFront) "front" else "back"
        val cacheKey = "${pos.asLong()}::$sideKey::$targetLang::${sentence.hashCode()}"

        val cached = signCache[cacheKey]
        if (cached != null) return cached

        dispatchSignTranslation(sign, isFront, sentence, cacheKey)
        return null
    }

    private fun extractSignSentence(sign: SignBlockEntity, isFront: Boolean): String? {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateSigns.value()) {
            return null
        }

        val originalText = if (isFront) sign.frontText else sign.backText
        val sentence = extractSentence(originalText)
        return sentence.ifBlank { null }
    }

    private fun dispatchSignTranslation(
        sign: SignBlockEntity,
        isFront: Boolean,
        sentence: String,
        cacheKey: String,
    ) {
        val originalText = if (isFront) sign.frontText else sign.backText
        val nearbyContext = gatherNearbySignContext(sign.level, sign.blockPos)
        val fullQuery = if (nearbyContext.isNotBlank()) "$nearbyContext | $sentence" else sentence

        TranslationService.translateAsync(fullQuery) { result ->
            if (result != null && !result.isSameLanguage) {
                val translatedSentence = extractTargetSentence(result.translatedText, nearbyContext.isNotBlank())
                signCache[cacheKey] = applyTranslatedLines(originalText, translatedSentence)
            }
        }
    }

    private fun applyTranslatedLines(originalText: SignText, translatedSentence: String): SignText {
        val wrappedLines = wrapToSignLines(translatedSentence)
        var newText = originalText
        for (i in 0 until SignText.LINES) {
            val line = wrappedLines.getOrElse(i) { "" }
            newText = newText.setMessage(i, Component.literal(line))
        }
        return newText
    }

    private fun extractSentence(signText: SignText): String {
        return signText.getMessages(false)
            .map { it.string.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun gatherNearbySignContext(level: Level?, centerPos: BlockPos): String {
        val customContext = nearbyContextProvider?.invoke(centerPos)
        if (customContext != null) return customContext

        if (level == null) return ""
        return gatherNearbySignContextWithGetter({ level.getBlockEntity(it) }, centerPos)
    }

    internal fun gatherNearbySignContextWithGetter(
        blockEntityGetter: (BlockPos) -> Any?,
        centerPos: BlockPos,
    ): String {
        val minPos = centerPos.offset(-AREA_RADIUS, -VERTICAL_RADIUS, -AREA_RADIUS)
        val maxPos = centerPos.offset(AREA_RADIUS, VERTICAL_RADIUS, AREA_RADIUS)

        return BlockPos.betweenClosed(minPos, maxPos)
            .asSequence()
            .filter { it != centerPos }
            .mapNotNull { blockEntityGetter(it) as? SignBlockEntity }
            .map { extractSentence(it.frontText) }
            .filter { it.isNotBlank() }
            .take(MAX_NEARBY_CONTEXT_SIGNS)
            .joinToString(" | ")
    }

    private fun extractTargetSentence(fullTranslated: String, hasPrefix: Boolean): String {
        if (!hasPrefix || !fullTranslated.contains("|")) {
            return fullTranslated.trim()
        }
        val parts = fullTranslated.split("|")
        return parts.last().trim()
    }

    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    private fun wrapToSignLines(text: String): List<String> {
        val words = text.split("\\s+".toRegex())
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder("[T] ")

        for (word in words) {
            if (currentLine.length + word.length + 1 <= MAX_LINE_LENGTH) {
                if (currentLine.isNotEmpty() && !currentLine.endsWith(" ")) {
                    currentLine.append(" ")
                }
                currentLine.append(word)
            } else {
                lines.add(currentLine.toString().trim())
                currentLine = StringBuilder(word)
                if (lines.size == SignText.LINES - 1) break
            }
        }
        if (currentLine.isNotEmpty() && lines.size < SignText.LINES) {
            lines.add(currentLine.toString().trim())
        }
        return lines
    }
}
