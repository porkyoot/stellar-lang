package com.stellar.lang.book

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of written and writable books page-by-page using batch translation.
 */
object BookTranslationManager {
    private val translatedBooks = ConcurrentHashMap<String, BookViewScreen.BookAccess>()

    fun translateBookAsync(bookAccess: BookViewScreen.BookAccess, callback: (BookViewScreen.BookAccess?) -> Unit) {
        val originalPages = bookAccess.pages()
        val pageTexts = originalPages.map { it.string.trim() }

        if (!isTranslationEligible(pageTexts)) {
            callback(null)
            return
        }

        val config = TranslationService.getConfig()
        val targetLang = TranslationService.getTargetLanguage()
        val cacheKey = "$targetLang::${pageTexts.joinToString("||") { it.hashCode().toString() }}"
        val cached = translatedBooks[cacheKey]
        if (cached != null) {
            callback(cached)
            return
        }

        dispatchBatchTranslation(originalPages, pageTexts, cacheKey, callback)
    }

    private fun isTranslationEligible(pageTexts: List<String>): Boolean {
        val config = TranslationService.getConfig()
        val enabled = config.enabled.value() && config.translateBooks.value()
        return enabled && pageTexts.isNotEmpty() && pageTexts.any { it.isNotBlank() }
    }

    private fun dispatchBatchTranslation(
        originalPages: List<Component>,
        pageTexts: List<String>,
        cacheKey: String,
        callback: (BookViewScreen.BookAccess?) -> Unit,
    ) {
        TranslationService.translateBatchAsync(pageTexts) { results ->
            val access = processBatchResults(originalPages, results)
            if (access != null) {
                translatedBooks[cacheKey] = access
            }
            callback(access)
        }
    }

    private fun processBatchResults(
        originalPages: List<Component>,
        results: List<TranslationResult>?,
    ): BookViewScreen.BookAccess? {
        if (results.isNullOrEmpty()) return null

        val hasAnyTranslation = results.any { isValidTranslation(it) }
        if (!hasAnyTranslation) return null

        val newPages = originalPages.mapIndexed { index, origComp ->
            val res = results.getOrNull(index)
            if (res != null && isValidTranslation(res)) {
                Component.literal(res.translatedText).setStyle(origComp.style)
            } else {
                origComp
            }
        }
        return BookViewScreen.BookAccess(newPages)
    }

    private fun isValidTranslation(res: TranslationResult): Boolean {
        return !res.isSameLanguage && res.translatedText.isNotBlank()
    }

    fun clearCache() {
        translatedBooks.clear()
    }
}
