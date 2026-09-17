package com.stellar.lang.book

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BookTranslationManagerSpec : FunSpec({
    beforeEach {
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateBooks.setValue(true, false)
        config.targetLanguage.setValue("en", false)
    }

    test("translateBookAsync returns null when book content is empty or disabled") {
        val config = TranslationService.getConfig()
        val emptyBook = BookViewScreen.BookAccess(emptyList())

        val latch1 = CountDownLatch(1)
        var res1: BookViewScreen.BookAccess? = BookViewScreen.BookAccess(emptyList())
        BookTranslationManager.translateBookAsync(emptyBook) { res ->
            res1 = res
            latch1.countDown()
        }
        latch1.await(2, TimeUnit.SECONDS) shouldBe true
        res1 shouldBe null

        val blankBook = BookViewScreen.BookAccess(listOf(Component.literal("   ")))
        val latch2 = CountDownLatch(1)
        var res2: BookViewScreen.BookAccess? = BookViewScreen.BookAccess(emptyList())
        BookTranslationManager.translateBookAsync(blankBook) { res ->
            res2 = res
            latch2.countDown()
        }
        latch2.await(2, TimeUnit.SECONDS) shouldBe true
        res2 shouldBe null

        config.translateBooks.setValue(false, false)
        val validBook = BookViewScreen.BookAccess(listOf(Component.literal("Kapitel 1")))
        val latch3 = CountDownLatch(1)
        var res3: BookViewScreen.BookAccess? = BookViewScreen.BookAccess(emptyList())
        BookTranslationManager.translateBookAsync(validBook) { res ->
            res3 = res
            latch3.countDown()
        }
        latch3.await(2, TimeUnit.SECONDS) shouldBe true
        res3 shouldBe null
    }

    test("translateBookAsync parses multi-page translation and caches result") {
        val originalPages = listOf(
            Component.literal("Bonjour tout le monde."),
            Component.literal("Voici le deuxieme chapitre."),
        )
        val book = BookViewScreen.BookAccess(originalPages)

        // Seed TranslationService cache
        val joined = "Bonjour tout le monde.\n\n---PAGE_SPLIT---\n\nVoici le deuxieme chapitre."
        val translatedJoined = "Hello everyone.\n\n---PAGE_SPLIT---\n\nHere is the second chapter."
        val fakeResult = TranslationResult(
            originalText = joined,
            translatedText = translatedJoined,
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )

        // Put into cache directly
        TranslationService.putCache(fakeResult)

        val latch = CountDownLatch(1)
        var translatedAccess: BookViewScreen.BookAccess? = null
        BookTranslationManager.translateBookAsync(book) { res ->
            translatedAccess = res
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS) shouldBe true
        translatedAccess shouldNotBe null
        translatedAccess!!.pageCount shouldBe 2
        translatedAccess!!.getPage(0).string shouldBe "Hello everyone."
        translatedAccess!!.getPage(1).string shouldBe "Here is the second chapter."

        // Second call should hit the cache
        val latch2 = CountDownLatch(1)
        var secondAccess: BookViewScreen.BookAccess? = null
        BookTranslationManager.translateBookAsync(book) { res ->
            secondAccess = res
            latch2.countDown()
        }
        latch2.await(2, TimeUnit.SECONDS) shouldBe true
        secondAccess shouldBe translatedAccess
    }

    test("translateBookAsync returns null when detected language matches target language") {
        val originalPages = listOf(Component.literal("English page."))
        val book = BookViewScreen.BookAccess(originalPages)
        val sameResult = TranslationResult(
            originalText = "English page.",
            translatedText = "English page.",
            detectedLanguage = "en",
            targetLanguage = "en",
            isSameLanguage = true,
        )
        TranslationService.putCache(sameResult)

        val latch = CountDownLatch(1)
        var res: BookViewScreen.BookAccess? = BookViewScreen.BookAccess(emptyList())
        BookTranslationManager.translateBookAsync(book) { r ->
            res = r
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        res shouldBe null
    }
})
