package com.stellar.lang.feature

import com.stellar.lang.entity.EntityTranslationManager
import com.stellar.lang.item.ItemTranslationManager
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.network.chat.Component

class EntityAndItemTranslationManagerSpec : FunSpec({
    lateinit var server: com.sun.net.httpserver.HttpServer
    var serverPort: Int = 0

    beforeSpec {
        server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port
        server.createContext("/translate") { exchange ->
            val body = """{"translatedText": "Translated Value", "detectedLanguage": "fr"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
    }

    afterSpec {
        server.stop(0)
    }

    beforeEach {
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.apiHost.setValue("http://127.0.0.1:$serverPort", false)
        config.translateEntities.setValue(true, false)
        config.translateItems.setValue(true, false)
        config.targetLanguage.setValue("en", false)
    }

    test("EntityTranslationManager getTranslatableText filters accurately") {
        val method = EntityTranslationManager::class.java
            .getDeclaredMethod("getTranslatableText", Component::class.java)
        method.isAccessible = true

        val valid = Component.literal("Garde Royal")
        val extracted = method.invoke(EntityTranslationManager, valid) as? String
        extracted shouldBe "Garde Royal"

        val short = Component.literal("a")
        method.invoke(EntityTranslationManager, short) shouldBe null

        val alreadyTrans = Component.literal("[T] Royal Guard")
        method.invoke(EntityTranslationManager, alreadyTrans) shouldBe null

        val config = TranslationService.getConfig()
        config.translateEntities.setValue(false, false)
        method.invoke(EntityTranslationManager, valid) shouldBe null
    }

    test("EntityTranslationManager resolveEntityTranslation formats badge and respects same language") {
        val method = EntityTranslationManager::class.java.getDeclaredMethod(
            "resolveEntityTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        )
        method.isAccessible = true

        val orig = Component.literal("Loup Sauvage")

        // 1. Same language (detected 'en' == target 'en')
        val sameResultFake = TranslationResult("Loup Sauvage", "Loup Sauvage", "en", "en", true)
        TranslationService.putCache(sameResultFake)

        val sameResult = method.invoke(
            EntityTranslationManager,
            "Loup Sauvage",
            "en",
            "en::${"Loup Sauvage".hashCode()}",
            orig,
        ) as Component
        sameResult.string shouldBe "Loup Sauvage"

        // 2. Different language (detected 'fr' -> target 'en')
        TranslationService.clearCache()
        val diffResultFake = TranslationResult("Loup Sauvage", "Wild Wolf", "fr", "en", false)
        TranslationService.putCache(diffResultFake)

        val diffResult = method.invoke(
            EntityTranslationManager,
            "Loup Sauvage",
            "en",
            "en::${"Loup Sauvage".hashCode()}",
            orig,
        ) as Component
        diffResult.string shouldContain "[T] "
        diffResult.string shouldContain "Wild Wolf"
    }

    test("ItemTranslationManager getTranslatableText and resolveItemTranslation format item tooltips") {
        val getTransMethod = ItemTranslationManager::class.java
            .getDeclaredMethod("getTranslatableText", Component::class.java)
        getTransMethod.isAccessible = true

        val itemComp = Component.literal("Épée en diamant")
        val extracted = getTransMethod.invoke(ItemTranslationManager, itemComp) as? String
        extracted shouldBe "Épée en diamant"

        val resolveMethod = ItemTranslationManager::class.java.getDeclaredMethod(
            "resolveItemTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        )
        resolveMethod.isAccessible = true

        val itemResultFake = TranslationResult("Épée en diamant", "Diamond Sword", "fr", "en", false)
        TranslationService.putCache(itemResultFake)

        val resolved = resolveMethod.invoke(
            ItemTranslationManager,
            "Épée en diamant",
            "en",
            "en::${"Épée en diamant".hashCode()}",
            itemComp,
        ) as Component
        resolved.string shouldContain "[T] "
        resolved.string shouldContain "Diamond Sword"

        // Disabled items test
        val config = TranslationService.getConfig()
        config.translateItems.setValue(false, false)
        getTransMethod.invoke(ItemTranslationManager, itemComp) shouldBe null
    }

    test("EntityTranslationManager translateEntityName full workflow and caching") {
        val orig = Component.literal("Cheval Brun")
        val fakeResult = TranslationResult("Cheval Brun", "Brown Horse", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        // 1. First translation (cache hit)
        val trans1 = EntityTranslationManager.translateEntityName(null, orig)
        trans1.string shouldContain "[T] "
        trans1.string shouldContain "Brown Horse"

        // 2. Second translation (entity cache hit)
        val trans2 = EntityTranslationManager.translateEntityName(null, orig)
        trans2 shouldBe trans1

        // 3. Showing original key held down
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = { true }
        val showingOrig = EntityTranslationManager.translateEntityName(null, orig)
        showingOrig shouldBe orig
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = null

        // 4. Uncached translation triggers async and returns original immediately
        val uncachedOrig = Component.literal("Oiseau Blanc")
        val immediatelyReturned = EntityTranslationManager.translateEntityName(original = uncachedOrig)
        immediatelyReturned shouldBe uncachedOrig

        // 5. Test async callback populating entityCache
        val resolveEntityMethod = EntityTranslationManager::class.java.getDeclaredMethod(
            "resolveEntityTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        )
        resolveEntityMethod.isAccessible = true
        val entityCacheField = EntityTranslationManager::class.java.getDeclaredField("entityCache")
        entityCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val entityCache = entityCacheField.get(EntityTranslationManager) as
            java.util.concurrent.ConcurrentHashMap<String, Component>
        entityCache.clear()
        TranslationService.clearCache()
        resolveEntityMethod.invoke(
            EntityTranslationManager,
            "Oiseau Blanc",
            "en",
            "en::${"Oiseau Blanc".hashCode()}",
            uncachedOrig,
        )
        var entityPopulated = false
        var entityAttempts = 0
        while (entityAttempts++ < 30) {
            if (entityCache.containsKey("en::${"Oiseau Blanc".hashCode()}")) {
                entityPopulated = true
                break
            }
            Thread.sleep(50)
        }
        entityPopulated shouldBe true
    }

    test("ItemTranslationManager translateItemName full workflow and caching") {
        val orig = Component.literal("Hache en fer")
        val fakeResult = TranslationResult("Hache en fer", "Iron Axe", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        // 1. First translation (cache hit) using default parameter syntax
        val trans1 = ItemTranslationManager.translateItemName(original = orig)
        trans1.string shouldContain "[T] "
        trans1.string shouldContain "Iron Axe"

        // 2. Second translation (item cache hit)
        val trans2 = ItemTranslationManager.translateItemName(null, orig)
        trans2 shouldBe trans1

        // 3. Same language returns original
        val sameOrig = Component.literal("Golden Apple")
        val sameResult = TranslationResult("Golden Apple", "Golden Apple", "en", "en", true)
        TranslationService.putCache(sameResult)
        val transSame = ItemTranslationManager.translateItemName(null, sameOrig)
        transSame shouldBe sameOrig

        // 4. Uncached translation triggers async and returns original immediately
        val uncachedOrig = Component.literal("Arc Magique")
        val immediatelyReturned = ItemTranslationManager.translateItemName(original = uncachedOrig)
        immediatelyReturned shouldBe uncachedOrig

        // 5. Test async callback populating itemCache
        val resolveItemMethod = ItemTranslationManager::class.java.getDeclaredMethod(
            "resolveItemTranslation",
            String::class.java,
            String::class.java,
            String::class.java,
            Component::class.java,
        )
        resolveItemMethod.isAccessible = true
        val itemCacheField = ItemTranslationManager::class.java.getDeclaredField("itemCache")
        itemCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val itemCache = itemCacheField.get(ItemTranslationManager) as
            java.util.concurrent.ConcurrentHashMap<String, Component>
        itemCache.clear()
        TranslationService.clearCache()
        resolveItemMethod.invoke(
            ItemTranslationManager,
            "Arc Magique",
            "en",
            "en::${"Arc Magique".hashCode()}",
            uncachedOrig,
        )
        var itemPopulated = false
        var itemAttempts = 0
        while (itemAttempts++ < 30) {
            if (itemCache.containsKey("en::${"Arc Magique".hashCode()}")) {
                itemPopulated = true
                break
            }
            Thread.sleep(50)
        }
        itemPopulated shouldBe true

        // 6. Test skip conditions directly via translateItemName
        ItemTranslationManager.translateItemName(null, Component.literal("x")) shouldBe Component.literal("x")
        ItemTranslationManager.translateItemName(
            null,
            Component.literal("[T] Item"),
        ) shouldBe Component.literal("[T] Item")

        val config = TranslationService.getConfig()
        config.translateItems.setValue(false, false)
        ItemTranslationManager.translateItemName(
            null,
            Component.literal("Diamond"),
        ) shouldBe Component.literal("Diamond")

        config.translateItems.setValue(true, false)
        config.enabled.setValue(false, false)
        ItemTranslationManager.translateItemName(
            null,
            Component.literal("Diamond"),
        ) shouldBe Component.literal("Diamond")
        config.enabled.setValue(true, false)
    }

    test("StellarLangInputHandler responds to keyStateProvider, windowProvider and config key") {
        val config = TranslationService.getConfig()
        config.showOriginalKey.setValue(44, false) // GLFW comma

        // Default headless returns false
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = null
        com.stellar.lang.input.StellarLangInputHandler.windowProvider = null
        com.stellar.lang.input.StellarLangInputHandler.isShowingOriginal() shouldBe false

        // Provider returning true for key 44
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = { key -> key == 44 }
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider shouldNotBe null
        com.stellar.lang.input.StellarLangInputHandler.isShowingOriginal() shouldBe true

        // Provider returning false for other keys
        config.showOriginalKey.setValue(45, false)
        com.stellar.lang.input.StellarLangInputHandler.isShowingOriginal() shouldBe false
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = null

        // Check checkKeyDown direct call with null and allocated window
        com.stellar.lang.input.StellarLangInputHandler.checkKeyDown(null, 44) shouldBe false

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val dummyWindow = unsafe.allocateInstance(com.mojang.blaze3d.platform.Window::class.java)
            as com.mojang.blaze3d.platform.Window
        com.stellar.lang.input.StellarLangInputHandler.checkKeyDown(dummyWindow, 44) shouldBe false

        // Window provider hook
        com.stellar.lang.input.StellarLangInputHandler.windowProvider = { dummyWindow }
        com.stellar.lang.input.StellarLangInputHandler.windowProvider shouldNotBe null
        com.stellar.lang.input.StellarLangInputHandler.isShowingOriginal() shouldBe false
        com.stellar.lang.input.StellarLangInputHandler.windowProvider = null
    }
})
