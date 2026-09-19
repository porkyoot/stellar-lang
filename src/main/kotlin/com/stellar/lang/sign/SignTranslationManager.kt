package com.stellar.lang.sign

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationService
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation for signs with text-based caching, format preservation, and lifecycle triggers.
 */
@Suppress("TooManyFunctions")
object SignTranslationManager {
    internal const val MAX_LINE_LENGTH = SignFormatHelper.MAX_LINE_LENGTH
    private const val KEY_DELIMITER = "::"

    // Cache is strictly for TEXT: "$targetLang::$sentence" -> SignFormatHelper.SignTranslationOutcome
    internal val textOutcomeCache = ConcurrentHashMap<String, SignFormatHelper.SignTranslationOutcome>()
    internal val signCache = ConcurrentHashMap<String, SignText>()

    private fun buildTextKey(targetLang: String, sentence: String): String = "$targetLang$KEY_DELIMITER$sentence"

    fun onSignLoaded(sign: SignBlockEntity) {
        translateSignText(sign.frontText)
        translateSignText(sign.backText)
    }

    fun onSignTextChanged(signText: SignText) {
        translateSignText(signText)
    }

    @Suppress("CognitiveComplexMethod")
    fun translateSignText(signText: SignText) {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateSigns.value()) return

        val sentence = extractSentence(signText)
        if (sentence.isBlank()) return

        val targetLang = config.targetLanguage.value()
        val textKey = buildTextKey(targetLang, sentence)

        val cached = TranslationService.getCached(sentence, targetLang)
        if (cached != null) {
            if (!cached.isSameLanguage && !textOutcomeCache.containsKey(textKey)) {
                textOutcomeCache[textKey] = applyTranslatedLinesWithOutcome(signText, cached.translatedText)
            }
            return
        }

        TranslationService.translateAsync(sentence) { result ->
            if (result != null && !result.isSameLanguage) {
                textOutcomeCache[textKey] = applyTranslatedLinesWithOutcome(signText, result.translatedText)
            }
        }
    }

    @Suppress("ReturnCount")
    fun getOutcome(signText: SignText): SignFormatHelper.SignTranslationOutcome? {
        if (StellarLangInputHandler.isShowingOriginal()) return null
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateSigns.value()) return null

        val sentence = extractSentence(signText)
        if (sentence.isBlank()) return null

        val targetLang = config.targetLanguage.value()
        val textKey = buildTextKey(targetLang, sentence)

        val outcome = textOutcomeCache[textKey]
        if (outcome != null) return outcome

        val cachedResult = TranslationService.getCached(sentence, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) return null
            val newOutcome = applyTranslatedLinesWithOutcome(signText, cachedResult.translatedText)
            textOutcomeCache[textKey] = newOutcome
            return newOutcome
        }

        return null
    }

    fun getOutcome(sign: SignBlockEntity, isFront: Boolean): SignFormatHelper.SignTranslationOutcome? {
        val text = if (isFront) sign.frontText else sign.backText
        return getOutcome(text)
    }

    fun getTranslatedSignText(signText: SignText): SignText? {
        val outcome = getOutcome(signText)
        if (outcome != null) {
            return copyAppearance(signText, outcome.signText)
        }
        onSignTextChanged(signText)
        return null
    }

    private fun copyAppearance(original: SignText, translated: SignText): SignText {
        var result = original
        for (i in 0 until SignText.LINES) {
            result = result.setMessage(i, translated.getMessage(i, false))
        }
        return result
    }

    fun getOrRequestTranslatedSignText(sign: SignBlockEntity, isFront: Boolean): SignText? {
        val text = if (isFront) sign.frontText else sign.backText
        val translated = getTranslatedSignText(text)
        if (translated == null) {
            translateSignText(text)
        }
        return translated
    }

    fun getExcessText(signText: SignText): String? = getOutcome(signText)?.excessText

    fun getExcessText(sign: SignBlockEntity, isFront: Boolean): String? {
        val text = if (isFront) sign.frontText else sign.backText
        return getExcessText(text)
    }

    fun getFullTranslation(signText: SignText): String? = getOutcome(signText)?.fullTranslation

    fun getFullTranslation(sign: SignBlockEntity, isFront: Boolean): String? {
        val text = if (isFront) sign.frontText else sign.backText
        return getFullTranslation(text)
    }

    fun clearCache() {
        textOutcomeCache.clear()
        signCache.clear()
    }

    fun applyTranslatedLines(originalText: SignText, translatedSentence: String): SignText {
        return SignFormatHelper.applyTranslatedLinesWithOutcome(originalText, translatedSentence).signText
    }

    fun applyTranslatedLinesWithOutcome(
        originalText: SignText,
        translatedSentence: String,
    ): SignFormatHelper.SignTranslationOutcome {
        return SignFormatHelper.applyTranslatedLinesWithOutcome(originalText, translatedSentence)
    }

    fun isPureFormattingLine(line: String): Boolean = SignFormatHelper.isPureFormattingLine(line)

    fun extractFraming(line: String): SignFormatHelper.LineFraming = SignFormatHelper.extractFraming(line)

    fun wrapToSignLines(text: String): List<String> = SignFormatHelper.wrapToSignLines(text)

    internal fun splitIntoWords(text: String, maxWordLength: Int = MAX_LINE_LENGTH): List<String> {
        return SignFormatHelper.splitIntoWords(text, maxWordLength)
    }

    fun wrapTooltipLines(
        text: String,
        maxCharsPerLine: Int = SignTooltipRenderer.DEFAULT_TOOLTIP_WRAP_LENGTH,
    ): List<String> {
        return SignTooltipRenderer.wrapTooltipLines(text, maxCharsPerLine)
    }

    fun renderSignTooltipIfLooking(extractor: GuiGraphicsExtractor) {
        SignTooltipRenderer.renderSignTooltipIfLooking(extractor)
    }

    fun extractSentence(signText: SignText): String {
        return signText.getMessages(false)
            .map { it.string.trim() }
            .filter { it.isNotEmpty() && !isPureFormattingLine(it) }
            .map { extractFraming(it).content }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
}
