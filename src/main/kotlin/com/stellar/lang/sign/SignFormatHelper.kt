package com.stellar.lang.sign

import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.entity.SignText

/**
 * Utility for formatting and wrapping sign text while preserving borders, bullets, and frames.
 */
object SignFormatHelper {
    internal const val MAX_LINE_LENGTH = 15
    private const val ELLIPSIS_LENGTH = 3
    private const val ELLIPSIS = "..."

    data class LineFraming(
        val prefix: String,
        val content: String,
        val suffix: String,
    )

    data class SignTranslationOutcome(
        val signText: SignText,
        val excessText: String?,
        val fullTranslation: String = "",
    ) {
        val hasOverflow: Boolean
            get() = !excessText.isNullOrBlank() ||
                (0 until SignText.LINES).any { signText.getMessage(it, false).string.endsWith(ELLIPSIS) } ||
                isDisplayedTextDifferent(signText, fullTranslation)
    }

    internal fun isDisplayedTextDifferent(signText: SignText, fullTranslation: String): Boolean {
        if (fullTranslation.isBlank()) return false
        val displayed = signText.getMessages(false)
            .map { it.string.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return displayed != fullTranslation.trim()
    }

    private fun isBorderChar(c: Char): Boolean =
        c in "|[]{}()=~#+<>_*/\\-"

    fun isPureFormattingLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() && trimmed.all { isBorderChar(it) || it.isWhitespace() }
    }

    fun extractFraming(line: String): LineFraming {
        val trimmed = line.trim()
        val firstContent = trimmed.indexOfFirst { !isBorderChar(it) && !it.isWhitespace() }
        val lastContent = trimmed.indexOfLast { !isBorderChar(it) && !it.isWhitespace() }
        if (firstContent == -1) {
            return LineFraming(prefix = trimmed, content = "", suffix = "")
        }
        val prefix = trimmed.substring(0, firstContent)
        val content = trimmed.substring(firstContent, lastContent + 1)
        val suffix = trimmed.substring(lastContent + 1)
        return LineFraming(prefix, content, suffix)
    }

    internal fun splitIntoWords(text: String, maxWordLength: Int = MAX_LINE_LENGTH): List<String> {
        val rawWords = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        for (word in rawWords) {
            appendSplitWord(result, word, maxWordLength)
        }
        return result
    }

    private fun appendSplitWord(target: MutableList<String>, word: String, maxWordLength: Int) {
        if (word.length <= maxWordLength) {
            target.add(word)
            return
        }
        var remaining = word
        val chunkLen = maxWordLength - 1
        while (remaining.length > maxWordLength) {
            target.add(remaining.take(chunkLen) + "-")
            remaining = remaining.drop(chunkLen)
        }
        if (remaining.isNotEmpty()) {
            target.add(remaining)
        }
    }

    fun applyTranslatedLinesWithOutcome(
        originalText: SignText,
        translatedSentence: String,
    ): SignTranslationOutcome {
        val pureFormattingIndices = (0 until SignText.LINES).filter { i ->
            isPureFormattingLine(originalText.getMessage(i, false).string)
        }
        val availableIndices = (0 until SignText.LINES).filter { it !in pureFormattingIndices }

        if (availableIndices.isEmpty()) {
            return SignTranslationOutcome(
                signText = originalText,
                excessText = translatedSentence.ifBlank { null },
                fullTranslation = translatedSentence,
            )
        }

        val words = splitIntoWords(translatedSentence, MAX_LINE_LENGTH).toMutableList()
        val assignedLines = mutableMapOf<Int, String>()
        var excessText: String? = null

        for (idx in availableIndices.indices) {
            val slot = availableIndices[idx]
            val isLastSlot = idx == availableIndices.size - 1
            val framing = extractFraming(originalText.getMessage(slot, false).string)
            val linePrefix = framing.prefix
            val framingBudget = linePrefix.length + framing.suffix.length
            val contentBudget = (MAX_LINE_LENGTH - framingBudget).coerceAtLeast(1)

            if (words.isEmpty()) {
                assignedLines[slot] = ""
            } else if (isLastSlot) {
                val (fittedContent, excess) = packLastSlot(words, contentBudget)
                assignedLines[slot] = (linePrefix + fittedContent + framing.suffix).take(MAX_LINE_LENGTH)
                excessText = excess
            } else {
                val content = packNonLastSlot(words, contentBudget)
                assignedLines[slot] = (linePrefix + content + framing.suffix).take(MAX_LINE_LENGTH)
            }
        }

        return buildFinalOutcome(originalText, pureFormattingIndices, assignedLines, excessText, translatedSentence)
    }

    private fun buildFinalOutcome(
        originalText: SignText,
        pureFormattingIndices: List<Int>,
        assignedLines: Map<Int, String>,
        excessText: String?,
        fullTranslation: String = "",
    ): SignTranslationOutcome {
        var newText = originalText
        for (i in 0 until SignText.LINES) {
            if (i !in pureFormattingIndices) {
                val text = assignedLines[i] ?: ""
                newText = newText.setMessage(i, Component.literal(text))
            }
        }
        return SignTranslationOutcome(newText, excessText?.ifBlank { null }, fullTranslation)
    }

    private fun packNonLastSlot(words: MutableList<String>, contentBudget: Int): String {
        val lineWords = mutableListOf<String>()
        var currentLen = 0
        while (words.isNotEmpty()) {
            val nextWord = words.first()
            val needed = if (currentLen == 0) nextWord.length else currentLen + 1 + nextWord.length
            if (needed <= contentBudget) {
                lineWords.add(words.removeAt(0))
                currentLen = needed
            } else {
                break
            }
        }
        return lineWords.joinToString(" ")
    }

    private fun collectFittedWords(words: List<String>, contentBudget: Int): Pair<List<String>, Int> {
        val lineWords = mutableListOf<String>()
        var currentLen = 0
        var wordIdx = 0
        while (wordIdx < words.size) {
            val word = words[wordIdx]
            val needed = if (currentLen == 0) word.length else currentLen + 1 + word.length
            if (needed > contentBudget) break
            lineWords.add(word)
            currentLen = needed
            wordIdx++
        }
        return Pair(lineWords, wordIdx)
    }

    private fun packLastSlot(words: MutableList<String>, contentBudget: Int): Pair<String, String?> {
        val (lineWords, wordIdx) = collectFittedWords(words, contentBudget)
        if (lineWords.isEmpty() && words.isNotEmpty()) {
            return truncateOversizedFirstWord(words, contentBudget)
        }

        val remainingWords = words.subList(wordIdx, words.size).toList()
        val fittedContent = lineWords.joinToString(" ")
        words.clear()
        if (remainingWords.isEmpty()) {
            return Pair(fittedContent, null)
        }

        return Pair(formatFittedWithEllipsis(fittedContent, contentBudget), remainingWords.joinToString(" "))
    }

    private fun truncateOversizedFirstWord(words: MutableList<String>, contentBudget: Int): Pair<String, String?> {
        val first = words.removeAt(0)
        val fit = if (contentBudget > ELLIPSIS_LENGTH) {
            first.take(contentBudget - ELLIPSIS_LENGTH) + ELLIPSIS
        } else {
            first.take(contentBudget)
        }
        val dropped = first.drop(contentBudget.coerceAtMost(first.length))
        val excess = (listOf(dropped) + words).filter { it.isNotBlank() }.joinToString(" ")
        words.clear()
        return Pair(fit, excess)
    }

    private fun formatFittedWithEllipsis(fittedContent: String, contentBudget: Int): String {
        return if (fittedContent.length + ELLIPSIS_LENGTH <= contentBudget) {
            fittedContent + ELLIPSIS
        } else if (fittedContent.length > ELLIPSIS_LENGTH) {
            fittedContent.dropLast(ELLIPSIS_LENGTH) + ELLIPSIS
        } else {
            fittedContent
        }
    }

    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    fun wrapToSignLines(text: String): List<String> {
        val words = splitIntoWords(text, MAX_LINE_LENGTH)
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

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
