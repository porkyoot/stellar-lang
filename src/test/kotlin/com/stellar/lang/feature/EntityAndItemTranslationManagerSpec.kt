package com.stellar.lang.feature

import com.stellar.lang.entity.EntityTranslationManager
import com.stellar.lang.item.ItemTranslationManager
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponents
import net.minecraft.core.component.PatchedDataComponentMap
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.Optional

@Suppress("LargeClass")
class EntityAndItemTranslationManagerSpec : FunSpec({
    lateinit var server: com.sun.net.httpserver.HttpServer
    var serverPort: Int = 0

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
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
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
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

    test("EntityTranslationManager formats badge and respects same language") {
        EntityTranslationManager.clearCache()
        val orig = Component.literal("Loup Sauvage")

        // 1. Same language (detected 'en' == target 'en')
        val sameResultFake = TranslationResult("Loup Sauvage", "Loup Sauvage", "en", "en", true)
        TranslationService.putCache(sameResultFake)

        val sameResult = EntityTranslationManager.translateEntityName(null, orig)
        sameResult.string shouldBe "Loup Sauvage"

        // 2. Different language (detected 'fr' -> target 'en')
        EntityTranslationManager.clearCache()
        TranslationService.clearCache()
        val diffResultFake = TranslationResult("Loup Sauvage", "Wild Wolf", "fr", "en", false)
        TranslationService.putCache(diffResultFake)

        val diffResult = EntityTranslationManager.translateEntityName(null, orig)
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

        // 5. Test onEntityNameChanged edge cases & async callback populating textComponentCache
        EntityTranslationManager.clearCache()
        TranslationService.clearCache()

        // 5a. Blank or short text returns early
        EntityTranslationManager.onEntityNameChanged(Component.literal("   "))
        EntityTranslationManager.onEntityNameChanged(Component.literal("A"))

        // 5b. Already in TranslationService cache (different language)
        val frResult = TranslationResult("Chien Noir", "Black Dog", "fr", "en", false)
        TranslationService.putCache(frResult)
        EntityTranslationManager.onEntityNameChanged(Component.literal("Chien Noir"))
        EntityTranslationManager.textComponentCache.containsKey("en::Chien Noir") shouldBe true

        // 5c. Already in textComponentCache (no-op)
        EntityTranslationManager.onEntityNameChanged(Component.literal("Chien Noir"))

        // 5d. In TranslationService cache but sameLanguage
        val sameLangResult = TranslationResult("Same", "Same", "en", "en", true)
        TranslationService.putCache(sameLangResult)
        EntityTranslationManager.onEntityNameChanged(Component.literal("Same"))
        EntityTranslationManager.textComponentCache.containsKey("en::Same") shouldBe false

        // 5e. Uncached triggers async translation
        EntityTranslationManager.onEntityNameChanged(uncachedOrig)
        var entityPopulated = false
        var entityAttempts = 0
        while (entityAttempts++ < 30) {
            if (EntityTranslationManager.textComponentCache.containsKey("en::Oiseau Blanc")) {
                entityPopulated = true
                break
            }
            Thread.sleep(50)
        }
        entityPopulated shouldBe true
    }

    test("EntityTranslationManager onEntityLoaded triggers on customName and handles null") {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe

        val accessorField = net.minecraft.world.entity.Entity::class.java.getDeclaredField("DATA_CUSTOM_NAME")
        accessorField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val accessor = accessorField.get(null) as EntityDataAccessor<Optional<Component>>

        val dataItem = SynchedEntityData.DataItem(accessor, Optional.of(Component.literal("Petit Cochon")))
        val items = java.lang.reflect.Array.newInstance(
            SynchedEntityData.DataItem::class.java,
            accessor.id + 1,
        ) as Array<SynchedEntityData.DataItem<*>?>
        items[accessor.id] = dataItem

        val synchedData = unsafe.allocateInstance(SynchedEntityData::class.java) as SynchedEntityData
        val itemsField = SynchedEntityData::class.java.getDeclaredField("itemsById")
        itemsField.isAccessible = true
        itemsField.set(synchedData, items)

        val entity = unsafe.allocateInstance(ArmorStand::class.java) as ArmorStand
        val entityDataField = net.minecraft.world.entity.Entity::class.java.getDeclaredField("entityData")
        entityDataField.isAccessible = true
        entityDataField.set(entity, synchedData)

        val emptyItem = SynchedEntityData.DataItem(accessor, Optional.empty<Component>())
        items[accessor.id] = emptyItem
        EntityTranslationManager.onEntityLoaded(entity)

        items[accessor.id] = dataItem
        EntityTranslationManager.onEntityLoaded(entity)

        var entityPopulated = false
        var attempts = 0
        while (attempts++ < 30) {
            if (EntityTranslationManager.textComponentCache.containsKey("en::Petit Cochon")) {
                entityPopulated = true
                break
            }
            Thread.sleep(50)
        }
        entityPopulated shouldBe true
    }

    fun createMockStack(customName: Component?): ItemStack {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val stack = unsafe.allocateInstance(ItemStack::class.java) as ItemStack
        val compField = ItemStack::class.java.getDeclaredField("components")
        compField.isAccessible = true
        val itemField = ItemStack::class.java.getDeclaredField("item")
        itemField.isAccessible = true
        val countField = ItemStack::class.java.getDeclaredField("count")
        countField.isAccessible = true

        val dummyItem = unsafe.allocateInstance(Item::class.java) as Item
        itemField.set(stack, Holder.direct(dummyItem))
        countField.set(stack, 1)

        val map = PatchedDataComponentMap(DataComponentMap.EMPTY)
        if (customName != null) {
            map.set(DataComponents.CUSTOM_NAME, customName)
        }
        compField.set(stack, map)
        return stack
    }

    test("ItemTranslationManager translateItemName full workflow and caching") {
        ItemTranslationManager.clearCache()
        val orig = Component.literal("Hache en fer")
        val fakeResult = TranslationResult("Hache en fer", "Iron Axe", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        // 0. Non-renamed vanilla stack or null stack should return original
        val vanillaStack = createMockStack(null)
        ItemTranslationManager.translateItemName(vanillaStack, orig) shouldBe orig
        ItemTranslationManager.translateItemName(null, orig) shouldBe orig
        ItemTranslationManager.translateItemName(original = orig) shouldBe orig

        // 1. Renamed stack translates correctly (cache hit)
        val renamedStack = createMockStack(orig)
        val trans1 = ItemTranslationManager.translateItemName(renamedStack, orig)
        trans1.string shouldContain "[T] "
        trans1.string shouldContain "Iron Axe"

        // 2. Second translation (item cache hit)
        val trans2 = ItemTranslationManager.translateItemName(renamedStack, orig)
        trans2 shouldBe trans1

        // 3. Showing original key held down
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = { true }
        val showingOrig = ItemTranslationManager.translateItemName(renamedStack, orig)
        showingOrig shouldBe orig
        com.stellar.lang.input.StellarLangInputHandler.keyStateProvider = null

        // 4. Same language returns original
        val sameOrig = Component.literal("Golden Apple")
        val sameResult = TranslationResult("Golden Apple", "Golden Apple", "en", "en", true)
        TranslationService.putCache(sameResult)
        val sameStack = createMockStack(sameOrig)
        val transSame = ItemTranslationManager.translateItemName(sameStack, sameOrig)
        transSame shouldBe sameOrig

        // 5. Uncached translation triggers async and returns original immediately
        val uncachedOrig = Component.literal("Arc Magique")
        val uncachedStack = createMockStack(uncachedOrig)
        val immediatelyReturned = ItemTranslationManager.translateItemName(uncachedStack, uncachedOrig)
        immediatelyReturned shouldBe uncachedOrig

        // 6. Test async callback populating itemCache
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

        // 7. Test skip conditions directly via translateItemName
        val shortStack = createMockStack(Component.literal("x"))
        ItemTranslationManager.translateItemName(shortStack, Component.literal("x")) shouldBe Component.literal("x")

        val alreadyTransStack = createMockStack(Component.literal("[T] Item"))
        ItemTranslationManager.translateItemName(
            alreadyTransStack,
            Component.literal("[T] Item"),
        ) shouldBe Component.literal("[T] Item")

        val diamondStack = createMockStack(Component.literal("Diamond"))
        val config = TranslationService.getConfig()
        config.translateItems.setValue(false, false)
        ItemTranslationManager.translateItemName(
            diamondStack,
            Component.literal("Diamond"),
        ) shouldBe Component.literal("Diamond")

        config.translateItems.setValue(true, false)
        config.enabled.setValue(false, false)
        ItemTranslationManager.translateItemName(
            diamondStack,
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
