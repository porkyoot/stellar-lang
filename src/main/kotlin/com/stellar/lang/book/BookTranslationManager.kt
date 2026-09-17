package com.stellar.lang.book

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of books by combining pages for continuous context.
 */
object BookTranslationManager {
    private const val PAGE_DELIMITER = "\n\n---PAGE_SPLIT---\n\n"
    private val translatedBooks = ConcurrentHashMap<String, BookViewScreen.BookAccess>()

    fun translateBookAsync(bookAccess: BookViewScreen.BookAccess, callback: (BookViewScreen.BookAccess?) -> Unit) {
        val joinedText = extractBookContent(bookAccess)
        if (joinedText == null) {
            callback(null)
            return
        }

        dispatchBookTranslation(joinedText, callback)
    }

    private fun extractBookContent(bookAccess: BookViewScreen.BookAccess): String? {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateBooks.value()) {
            return null
        }

        val pages = bookAccess.pages()
        if (pages.isEmpty()) return null

        val joined = pages.joinToString(separator = PAGE_DELIMITER) { it.string.trim() }
        return joined.ifBlank { null }
    }

    private fun dispatchBookTranslation(joinedText: String, callback: (BookViewScreen.BookAccess?) -> Unit) {
        val config = TranslationService.getConfig()
        val cacheKey = "${config.targetLanguage.value()}::${joinedText.hashCode()}"
        val cached = translatedBooks[cacheKey]
        if (cached != null) {
            callback(cached)
            return
        }

        TranslationService.translateAsync(joinedText) { result ->
            val access = parseTranslatedPages(result)
            if (access != null) {
                translatedBooks[cacheKey] = access
            }
            callback(access)
        }
    }

    private fun parseTranslatedPages(result: TranslationResult?): BookViewScreen.BookAccess? {
        if (result == null || result.isSameLanguage) return null

        val splitPages = result.translatedText.split("---PAGE_SPLIT---")
        val newPages = splitPages.map { pageText ->
            Component.literal(pageText.trim())
        }
        return BookViewScreen.BookAccess(newPages)
    }
}
