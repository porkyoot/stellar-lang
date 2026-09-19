package com.stellar.lang.book

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap

data class BookTranslationResult(
    val access: BookViewScreen.BookAccess?,
    val isSameLanguage: Boolean,
    val isFailed: Boolean,
    val detectedLanguage: String?,
    val targetLanguage: String,
)

/**
 * Manages translation of written and writable books page-by-page using batch translation.
 */
object BookTranslationManager {
    private val translatedBooks = ConcurrentHashMap<String, BookViewScreen.BookAccess>()
    private val resultCache = ConcurrentHashMap<String, BookTranslationResult>()

    fun translateBookAsync(bookAccess: BookViewScreen.BookAccess, callback: (BookViewScreen.BookAccess?) -> Unit) {
        translateBookDetailedAsync(bookAccess) { result ->
            callback(result.access)
        }
    }

    fun translateBookDetailedAsync(
        bookAccess: BookViewScreen.BookAccess,
        forceRetry: Boolean = false,
        callback: (BookTranslationResult) -> Unit,
    ) {
        val originalPages = bookAccess.pages()
        val pageTexts = originalPages.map { it.string.trim() }
        val targetLang = TranslationService.getTargetLanguage()

        if (!isTranslationEligible(pageTexts)) {
            val emptyResult = BookTranslationResult(
                access = null,
                isSameLanguage = true,
                isFailed = false,
                detectedLanguage = null,
                targetLanguage = targetLang,
            )
            callback(emptyResult)
            return
        }

        val cacheKey = "$targetLang::${pageTexts.joinToString("||") { it.hashCode().toString() }}"
        if (!forceRetry) {
            val cached = resultCache[cacheKey]
            if (cached != null) {
                callback(cached)
                return
            }
        }

        dispatchBatchTranslationDetailed(originalPages, pageTexts, cacheKey, callback)
    }

    private fun isTranslationEligible(pageTexts: List<String>): Boolean {
        val config = TranslationService.getConfig()
        val enabled = config.enabled.value() && config.translateBooks.value()
        return enabled && pageTexts.isNotEmpty() && pageTexts.any { it.isNotBlank() }
    }

    private fun dispatchBatchTranslationDetailed(
        originalPages: List<Component>,
        pageTexts: List<String>,
        cacheKey: String,
        callback: (BookTranslationResult) -> Unit,
    ) {
        val targetLang = TranslationService.getTargetLanguage()
        TranslationService.translateBatchAsync(pageTexts) { results ->
            val detailedResult = processBatchResultsDetailed(originalPages, pageTexts, results, targetLang)
            resultCache[cacheKey] = detailedResult
            if (detailedResult.access != null && !detailedResult.isFailed) {
                translatedBooks[cacheKey] = detailedResult.access
            }
            callback(detailedResult)
        }
    }

    private fun createFallbackResult(
        originalPages: List<Component>,
        pageTexts: List<String>,
        targetLang: String,
    ): BookTranslationResult {
        val firstNonBlank = pageTexts.firstOrNull { it.isNotBlank() } ?: ""
        val detected = TranslationService.detectLanguageQuick(firstNonBlank) ?: "unknown"
        val isSame = detected.equals(targetLang, ignoreCase = true)
        return BookTranslationResult(
            access = if (isSame) null else BookViewScreen.BookAccess(originalPages),
            isSameLanguage = isSame,
            isFailed = !isSame,
            detectedLanguage = detected,
            targetLanguage = targetLang,
        )
    }

    private fun processBatchResultsDetailed(
        originalPages: List<Component>,
        pageTexts: List<String>,
        results: List<TranslationResult>?,
        targetLang: String,
    ): BookTranslationResult {
        if (results == null) {
            return createFallbackResult(originalPages, pageTexts, targetLang)
        }

        val firstRes = results.firstOrNull()
        val isAllSameLang = results.all { it.isSameLanguage }
        val detected = firstRes?.detectedLanguage ?: targetLang

        if (isAllSameLang) {
            return BookTranslationResult(
                access = null,
                isSameLanguage = true,
                isFailed = false,
                detectedLanguage = detected,
                targetLanguage = targetLang,
            )
        }

        val newPages = originalPages.mapIndexed { index, origComp ->
            val res = results.getOrNull(index)
            if (res != null && isValidTranslation(res)) {
                Component.literal(res.translatedText).setStyle(origComp.style)
            } else {
                origComp
            }
        }
        return BookTranslationResult(
            access = BookViewScreen.BookAccess(newPages),
            isSameLanguage = false,
            isFailed = false,
            detectedLanguage = detected,
            targetLanguage = targetLang,
        )
    }

    private fun isValidTranslation(res: TranslationResult): Boolean {
        return !res.isSameLanguage && res.translatedText.isNotBlank()
    }

    fun clearCache() {
        translatedBooks.clear()
        resultCache.clear()
    }
}
