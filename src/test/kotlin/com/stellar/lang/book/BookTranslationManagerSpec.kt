package com.stellar.lang.book

import com.stellar.lang.service.TranslationCache
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
class BookTranslationManagerSpec : FunSpec({
    beforeEach {
        TranslationService.clearCache()
        BookTranslationManager.clearCache()
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

        val blankBook = BookViewScreen.BookAccess(listOf(Component.literal("   "), Component.literal("")))
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

        // Seed TranslationService cache for both pages
        val page1Result = TranslationResult(
            originalText = "Bonjour tout le monde.",
            translatedText = "Hello everyone.",
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        val page2Result = TranslationResult(
            originalText = "Voici le deuxieme chapitre.",
            translatedText = "Here is the second chapter.",
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        TranslationService.putCache(page1Result)
        TranslationService.putCache(page2Result)

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

        // Clear cache
        BookTranslationManager.clearCache()
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

    test("translateBookAsync preserves pages that do not need translation") {
        val originalPages = listOf(
            Component.literal("Kapitel Eins"),
            Component.literal("Chapter Two in English"),
        )
        val book = BookViewScreen.BookAccess(originalPages)

        val res1 = TranslationResult(
            originalText = "Kapitel Eins",
            translatedText = "Chapter One",
            detectedLanguage = "de",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        val res2 = TranslationResult(
            originalText = "Chapter Two in English",
            translatedText = "Chapter Two in English",
            detectedLanguage = "en",
            targetLanguage = "en",
            isSameLanguage = true,
        )
        TranslationService.putCache(res1)
        TranslationService.putCache(res2)

        val latch = CountDownLatch(1)
        var translatedAccess: BookViewScreen.BookAccess? = null
        BookTranslationManager.translateBookAsync(book) { r ->
            translatedAccess = r
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        translatedAccess shouldNotBe null
        translatedAccess!!.pageCount shouldBe 2
        translatedAccess!!.getPage(0).string shouldBe "Chapter One"
        translatedAccess!!.getPage(1).string shouldBe "Chapter Two in English"
    }

    test("translateBookDetailedAsync returns Failure with original access when translation fails") {
        val originalPages = listOf(Component.literal("Bonjour monde"))
        val book = BookViewScreen.BookAccess(originalPages)

        TranslationCache.tripCircuitBreaker(60_000L)
        try {
            val latch = CountDownLatch(1)
            var detailedResult: BookTranslationResult? = null
            BookTranslationManager.translateBookDetailedAsync(book) { res ->
                detailedResult = res
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS) shouldBe true
            val result = requireNotNull(detailedResult)
            result.isSameLanguage shouldBe false
            result.isFailed shouldBe true
            result.access shouldNotBe null
            result.access?.getPage(0)?.string shouldBe "Bonjour monde"

            // Second call without forceRetry returns cached result
            val latch2 = CountDownLatch(1)
            var cachedResult: BookTranslationResult? = null
            BookTranslationManager.translateBookDetailedAsync(book, forceRetry = false) { res ->
                cachedResult = res
                latch2.countDown()
            }
            latch2.await(2, TimeUnit.SECONDS) shouldBe true
            cachedResult shouldBe detailedResult

            // forceRetry = true bypasses cache
            val latch3 = CountDownLatch(1)
            var retryResult: BookTranslationResult? = null
            BookTranslationManager.translateBookDetailedAsync(book, forceRetry = true) { res ->
                retryResult = res
                latch3.countDown()
            }
            latch3.await(2, TimeUnit.SECONDS) shouldBe true
            retryResult shouldNotBe null
        } finally {
            TranslationCache.resetCircuitBreaker()
        }
    }

    test("translateBookDetailedAsync returns SameLanguage when detected is target language") {
        val originalPages = listOf(Component.literal("English text"))
        val book = BookViewScreen.BookAccess(originalPages)

        val sameResult = TranslationResult(
            originalText = "English text",
            translatedText = "English text",
            detectedLanguage = "en",
            targetLanguage = "en",
            isSameLanguage = true,
        )
        TranslationService.putCache(sameResult)

        val latch = CountDownLatch(1)
        var res: BookTranslationResult? = null
        BookTranslationManager.translateBookDetailedAsync(book) { r ->
            res = r
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val finalRes = requireNotNull(res)
        finalRes.isSameLanguage shouldBe true
        finalRes.isFailed shouldBe false
        finalRes.access shouldBe null
        finalRes.detectedLanguage shouldBe "en"
        finalRes.targetLanguage shouldBe "en"
    }

    test("isValidTranslation checks same language and blank translated text") {
        val method = BookTranslationManager::class.java.getDeclaredMethod(
            "isValidTranslation",
            TranslationResult::class.java,
        ).apply { isAccessible = true }

        val res1 = TranslationResult("Hello", "Hello", "en", "en", true)
        val res2 = TranslationResult("Bonjour", "   ", "fr", "en", false)
        val res3 = TranslationResult("Bonjour", "Hello", "fr", "en", false)

        method.invoke(BookTranslationManager, res1) as Boolean shouldBe false
        method.invoke(BookTranslationManager, res2) as Boolean shouldBe false
        method.invoke(BookTranslationManager, res3) as Boolean shouldBe true
    }

    test("createFallbackResult creates fallback with detected language") {
        val method = BookTranslationManager::class.java.getDeclaredMethod(
            "createFallbackResult",
            List::class.java,
            List::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val pages = listOf(Component.literal("Page 1"))
        val texts = listOf("Page 1")
        val fallback = method.invoke(BookTranslationManager, pages, texts, "fr") as BookTranslationResult
        fallback.targetLanguage shouldBe "fr"
        fallback.isFailed shouldBe true
        fallback.isSameLanguage shouldBe false
        fallback.access shouldNotBe null

        com.stellar.lang.plugin.PluginRegistry.getActiveDetector() shouldNotBe null
        com.stellar.lang.plugin.PluginRegistry.getActiveTranslator() shouldNotBe null
    }

    test("translatePagesDetailedAsync translates list of strings") {
        val originalPages = listOf("Page une", "Page deux")
        val targetLang = TranslationService.getTargetLanguage()
        TranslationCache.put(TranslationResult("Page une", "Page one", "fr", targetLang, false))
        TranslationCache.put(TranslationResult("Page deux", "Page two", "fr", targetLang, false))

        val latch = CountDownLatch(1)
        var res: BookTranslationResult? = null
        BookTranslationManager.translatePagesDetailedAsync(originalPages) { result ->
            res = result
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val finalRes = requireNotNull(res)
        finalRes.isSameLanguage shouldBe false
        finalRes.isFailed shouldBe false
        finalRes.access shouldNotBe null
        finalRes.access?.pageCount shouldBe 2
        finalRes.access?.getPage(0)?.string shouldBe "Page one"
        finalRes.access?.getPage(1)?.string shouldBe "Page two"
    }

    test("refreshBook clears cache and re-translates book") {
        val pages = listOf(Component.literal("Kapitel 1"), Component.literal("Kapitel 2"))
        val bookAccess = BookViewScreen.BookAccess(pages)

        val emptyAccess = BookViewScreen.BookAccess(emptyList())
        BookTranslationManager.refreshBook(emptyAccess) shouldBe false

        val blankAccess = BookViewScreen.BookAccess(listOf(Component.literal("   "), Component.literal("")))
        BookTranslationManager.refreshBook(blankAccess) shouldBe false

        BookTranslationManager.refreshBook(bookAccess) shouldBe true

        BookTranslationManager.refreshBook(net.minecraft.world.item.ItemStack.EMPTY) shouldBe false

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val dummyStack = unsafe.allocateInstance(net.minecraft.world.item.ItemStack::class.java)
            as net.minecraft.world.item.ItemStack
        BookTranslationManager.refreshBook(dummyStack) shouldBe false
    }

    test("createFallbackResult branches in BookTranslationManager") {
        val method = BookTranslationManager::class.java.getDeclaredMethod(
            "createFallbackResult",
            List::class.java,
            List::class.java,
            String::class.java,
        )
        method.isAccessible = true

        val sampleText = "This is the first chapter in English"
        TranslationCache.put(
            TranslationResult(
                originalText = sampleText,
                translatedText = sampleText,
                detectedLanguage = "en",
                targetLanguage = "en",
                isSameLanguage = true,
            ),
        )

        val res1 = method.invoke(
            BookTranslationManager,
            listOf(Component.literal(sampleText)),
            listOf(sampleText),
            "en",
        ) as BookTranslationResult
        res1.isSameLanguage shouldBe true
        res1.isFailed shouldBe false
        res1.access shouldBe null

        val res2 = method.invoke(
            BookTranslationManager,
            listOf(Component.literal("   ")),
            listOf("   "),
            "en",
        ) as BookTranslationResult
        res2.detectedLanguage shouldBe "unknown"
        res2.isFailed shouldBe true
    }
})
