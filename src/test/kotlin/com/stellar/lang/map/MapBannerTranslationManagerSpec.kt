package com.stellar.lang.map

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.service.TranslationCache
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponents
import net.minecraft.core.component.PatchedDataComponentMap
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapBanner
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import sun.misc.Unsafe
import java.util.Optional

@Suppress("LargeClass")
class MapBannerTranslationManagerSpec : FunSpec({
    val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null) as Unsafe
    }

    lateinit var server: com.sun.net.httpserver.HttpServer
    var serverPort: Int = 0

    beforeSpec {
        net.minecraft.SharedConstants.tryDetectVersion()
        net.minecraft.server.Bootstrap.bootStrap()
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
        MapBannerTranslationManager.clearCache()
        TranslationService.clearCache()
        StellarLangInputHandler.clearProviders()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.apiHost.setValue("http://127.0.0.1:$serverPort", false)
        config.translateMapBanners.setValue(true, false)
        config.targetLanguage.setValue("en", false)
    }

    test("translateBannerName returns original when disabled or showing original") {
        val config = TranslationService.getConfig()
        val original = Component.literal("Poste avancé")

        config.enabled.setValue(false, false)
        MapBannerTranslationManager.translateBannerName(original) shouldBe original

        config.enabled.setValue(true, false)
        config.translateMapBanners.setValue(false, false)
        MapBannerTranslationManager.translateBannerName(original) shouldBe original

        config.translateMapBanners.setValue(true, false)
        StellarLangInputHandler.keyStateProvider = { true }
        MapBannerTranslationManager.translateBannerName(original) shouldBe original
        StellarLangInputHandler.keyStateProvider = null
    }

    test("translateBannerName returns original for short or badged components") {
        val shortComp = Component.literal("A")
        MapBannerTranslationManager.translateBannerName(shortComp) shouldBe shortComp

        val badgedComp = Component.literal("[T] Outpost")
        MapBannerTranslationManager.translateBannerName(badgedComp) shouldBe badgedComp

        val translatingComp = Component.literal("[...] Loading")
        MapBannerTranslationManager.translateBannerName(translatingComp) shouldBe translatingComp
    }

    test("translateBannerName returns in-flight badge when translation is pending") {
        val text = "Base secrète"
        val original = Component.literal(text)
        val targetLang = "en"
        val key = TranslationService.cacheKey(text, targetLang)

        TranslationCache.queueInFlight(key) {}

        val result = MapBannerTranslationManager.translateBannerName(original)
        result.string shouldContain "[...]"
        result.string shouldContain text

        TranslationCache.completeInFlight(key, null)
    }

    test("translateBannerName returns original for same language result") {
        val text = "North Castle"
        val original = Component.literal(text)
        val targetLang = "en"

        TranslationService.putCache(
            TranslationResult(
                originalText = text,
                translatedText = text,
                detectedLanguage = targetLang,
                targetLanguage = targetLang,
                isSameLanguage = true,
            ),
        )

        val result = MapBannerTranslationManager.translateBannerName(original)
        result shouldBe original
        result.string shouldBe text
        result.string shouldNotBe "[T] North Castle"
    }

    test("translateBannerName formats and caches translated banner name") {
        val frenchText = "Tour de guet"
        val original = Component.literal(frenchText)
        val targetLang = "en"

        TranslationService.putCache(
            TranslationResult(
                originalText = frenchText,
                translatedText = "Watchtower",
                detectedLanguage = "fr",
                targetLanguage = targetLang,
                isSameLanguage = false,
            ),
        )

        val result = MapBannerTranslationManager.translateBannerName(original)
        result.string shouldContain "[T]"
        result.string shouldContain "Watchtower"

        // Cache hit
        val cachedHit = MapBannerTranslationManager.translateBannerName(original)
        cachedHit shouldBe result
    }

    test("translateBannerName handles failed banner translation and retry cooldown") {
        val text = "Ruines antiques"
        val original = Component.literal(text)
        val targetLang = "en"
        val textKey = "$targetLang::$text"

        val lastRetryField = MapBannerTranslationManager::class.java.getDeclaredField("lastBannerRetryTimes")
        lastRetryField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = lastRetryField.get(MapBannerTranslationManager) as MutableMap<String, Long>
        map[textKey] = System.currentTimeMillis()

        MapBannerTranslationManager.failedBanners.add(textKey)

        val failedResult = MapBannerTranslationManager.translateBannerName(original)
        failedResult.string shouldContain "[T]"
        failedResult.string shouldContain text

        // Simulate cooldown expiry
        map[textKey] = System.currentTimeMillis() - 10_000L

        val retryingResult = MapBannerTranslationManager.translateBannerName(original)
        retryingResult.string shouldContain "[...]"
    }

    test("getValidCachedBanner handles valid, failed-and-cached, and recovered states") {
        val text = "Forteresse"
        val targetLang = "en"
        val textKey = "$targetLang::$text"
        val serviceKey = TranslationService.cacheKey(text, targetLang)

        // 1. Valid cached item
        val validComp = Component.literal("[T] Fortress")
        MapBannerTranslationManager.textComponentCache[textKey] = validComp
        MapBannerTranslationManager.translateBannerName(Component.literal(text)) shouldBe validComp

        // 2. Failed cached item while TranslationService still marks it failed
        TranslationCache.markFailed(serviceKey)
        MapBannerTranslationManager.failedBanners.add(textKey)
        MapBannerTranslationManager.translateBannerName(Component.literal(text)) shouldBe validComp

        // 3. Failed cached item when TranslationService has recovered
        TranslationCache.removeFailed(serviceKey)
        val recovered = MapBannerTranslationManager.translateBannerName(Component.literal(text))
        recovered.string shouldBe text
    }

    test("onTranslationSuccess handles same-language and translated results") {
        val sameResult = TranslationResult("Fort", "Fort", "en", "en", true)
        MapBannerTranslationManager.onTranslationSuccess(sameResult)
        MapBannerTranslationManager.textComponentCache.containsKey("en::Fort") shouldBe false

        val diffResult = TranslationResult("Campement", "Camp", "fr", "en", false)
        MapBannerTranslationManager.onTranslationSuccess(diffResult)
        val comp = MapBannerTranslationManager.textComponentCache["en::Campement"]
        comp shouldNotBe null
        comp?.string shouldContain "Camp"
    }

    test("refreshBanner flushes caches and triggers async translation") {
        val text = "Phare maritime"
        val targetLang = "en"
        val textKey = "$targetLang::$text"
        val serviceKey = TranslationService.cacheKey(text, targetLang)

        MapBannerTranslationManager.textComponentCache[textKey] = Component.literal("Old")
        MapBannerTranslationManager.failedBanners.add(textKey)

        val refreshed = MapBannerTranslationManager.refreshBanner(text)
        refreshed shouldBe true
        MapBannerTranslationManager.textComponentCache.containsKey(textKey) shouldBe false
        MapBannerTranslationManager.failedBanners.contains(textKey) shouldBe false

        // Invalid short string
        MapBannerTranslationManager.refreshBanner("X") shouldBe false

        // Component overload with badge stripping
        val badgedComp = Component.literal("[T] Port royal")
        val badgedRefreshed = MapBannerTranslationManager.refreshBanner(badgedComp)
        badgedRefreshed shouldBe true

        val translatingComp = Component.literal("[...] Village")
        MapBannerTranslationManager.refreshBanner(translatingComp) shouldBe true
    }

    test("refreshBanner async callback updates cache on success and failure") {
        val textSuccess = "Cité perdue"
        val targetLang = "en"
        val serviceKey = TranslationService.cacheKey(textSuccess, targetLang)
        val textKey = "$targetLang::$textSuccess"

        MapBannerTranslationManager.refreshBanner(textSuccess)
        val result = TranslationResult(textSuccess, "Lost City", "fr", targetLang, false)
        TranslationCache.completeInFlight(serviceKey, result)
        MapBannerTranslationManager.textComponentCache[textKey]?.string shouldContain "Lost City"

        val textFail = "Grotte sombre"
        val failKey = TranslationService.cacheKey(textFail, targetLang)
        val failTextKey = "$targetLang::$textFail"

        MapBannerTranslationManager.refreshBanner(textFail)
        TranslationCache.completeInFlight(failKey, null)
        MapBannerTranslationManager.failedBanners.contains(failTextKey) shouldBe true
        MapBannerTranslationManager.textComponentCache[failTextKey]?.string shouldContain "[T]"
    }

    test("requestTranslation async callback updates cache on success and failure") {
        val text = "Manoir"
        val targetLang = "en"
        val textKey = "$targetLang::$text"
        val serviceKey = TranslationService.cacheKey(text, targetLang)

        // Trigger requestTranslation through translateBannerName
        MapBannerTranslationManager.translateBannerName(Component.literal(text))
        TranslationCache.completeInFlight(serviceKey, TranslationResult(text, "Mansion", "fr", targetLang, false))
        MapBannerTranslationManager.textComponentCache[textKey]?.string shouldContain "Mansion"

        // Failure callback
        val failText = "Donjon"
        val failKey = TranslationService.cacheKey(failText, targetLang)
        val failTextKey = "$targetLang::$failText"

        MapBannerTranslationManager.translateBannerName(Component.literal(failText))
        TranslationCache.markFailed(failKey)
        TranslationCache.completeInFlight(failKey, null)
        MapBannerTranslationManager.failedBanners.contains(failTextKey) shouldBe true
    }

    test("handleRetryOrFailed callback updates cache on success and failure") {
        val text = "Sanctuaire"
        val targetLang = "en"
        val textKey = "$targetLang::$text"
        val serviceKey = TranslationService.cacheKey(text, targetLang)

        val lastRetryField = MapBannerTranslationManager::class.java.getDeclaredField("lastBannerRetryTimes")
        lastRetryField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = lastRetryField.get(MapBannerTranslationManager) as MutableMap<String, Long>
        map[textKey] = System.currentTimeMillis() - 10_000L
        MapBannerTranslationManager.failedBanners.add(textKey)

        // Retry success
        MapBannerTranslationManager.translateBannerName(Component.literal(text))
        TranslationCache.completeInFlight(serviceKey, TranslationResult(text, "Sanctuary", "fr", targetLang, false))
        MapBannerTranslationManager.textComponentCache[textKey]?.string shouldContain "Sanctuary"

        // Retry failure
        val failText = "Crypte"
        val failKey = TranslationService.cacheKey(failText, targetLang)
        val failTextKey = "$targetLang::$failText"
        map[failTextKey] = System.currentTimeMillis() - 10_000L
        MapBannerTranslationManager.failedBanners.add(failTextKey)

        MapBannerTranslationManager.translateBannerName(Component.literal(failText))
        TranslationCache.completeInFlight(failKey, null)
        MapBannerTranslationManager.failedBanners.contains(failTextKey) shouldBe true
    }

    fun createMockMapStack(mapId: MapId?): ItemStack {
        val stack = unsafe.allocateInstance(ItemStack::class.java) as ItemStack
        val itemField = ItemStack::class.java.getDeclaredField("item")
        itemField.isAccessible = true
        itemField.set(stack, Holder.direct(unsafe.allocateInstance(MapItem::class.java) as MapItem))
        val countField = ItemStack::class.java.getDeclaredField("count")
        countField.isAccessible = true
        countField.set(stack, 1)
        val compField = ItemStack::class.java.getDeclaredField("components")
        compField.isAccessible = true
        val compMap = PatchedDataComponentMap(DataComponentMap.EMPTY)
        if (mapId != null) {
            compMap.set(DataComponents.MAP_ID, mapId)
        }
        compField.set(stack, compMap)
        return stack
    }

    test("refreshMap returns false for non-map items, null level, or null saved data") {
        val nonMapItem = unsafe.allocateInstance(ItemStack::class.java) as ItemStack
        val itemField = ItemStack::class.java.getDeclaredField("item")
        itemField.isAccessible = true
        itemField.set(nonMapItem, Holder.direct(unsafe.allocateInstance(Item::class.java) as Item))

        MapBannerTranslationManager.refreshMap(nonMapItem) shouldBe false

        // MapItem with null level
        val mapItemStack = createMockMapStack(MapId(10))
        MapBannerTranslationManager.levelProvider = { null }
        MapBannerTranslationManager.refreshMap(mapItemStack) shouldBe false

        // MapItem with level where savedData is null
        val clientLevel = unsafe.allocateInstance(ClientLevel::class.java) as ClientLevel
        val mapDataField = ClientLevel::class.java.getDeclaredField("mapData")
        mapDataField.isAccessible = true
        val mapDataMap = mutableMapOf<MapId, MapItemSavedData>()
        mapDataField.set(clientLevel, mapDataMap)
        MapBannerTranslationManager.levelProvider = { clientLevel }

        MapBannerTranslationManager.refreshMap(mapItemStack) shouldBe false
    }

    test("refreshMap refreshes banners and decorations with names") {
        val clientLevel = unsafe.allocateInstance(ClientLevel::class.java) as ClientLevel
        val mapDataField = ClientLevel::class.java.getDeclaredField("mapData")
        mapDataField.isAccessible = true
        val mapDataMap = mutableMapOf<MapId, MapItemSavedData>()
        mapDataField.set(clientLevel, mapDataMap)
        MapBannerTranslationManager.levelProvider = { clientLevel }

        val savedData = unsafe.allocateInstance(MapItemSavedData::class.java) as MapItemSavedData
        val bannerMarkersField = MapItemSavedData::class.java.getDeclaredField("bannerMarkers")
        bannerMarkersField.isAccessible = true
        val bannerMap = mutableMapOf<String, MapBanner>()
        bannerMarkersField.set(savedData, bannerMap)

        val decorationsField = MapItemSavedData::class.java.getDeclaredField("decorations")
        decorationsField.isAccessible = true
        val decoMap = mutableMapOf<String, MapDecoration>()
        decorationsField.set(savedData, decoMap)

        val mapId = MapId(20)
        mapDataMap[mapId] = savedData

        val mapItemStack = createMockMapStack(mapId)

        // When savedData has no named banners or decorations
        MapBannerTranslationManager.refreshMap(mapItemStack) shouldBe false

        // Add named banner
        val banner = MapBanner(
            net.minecraft.core.BlockPos.ZERO,
            net.minecraft.world.item.DyeColor.WHITE,
            Optional.of(Component.literal("Avant-poste nord")),
        )
        bannerMap["banner_1"] = banner

        // Add named decoration
        val deco = MapDecoration(
            net.minecraft.world.level.saveddata.maps.MapDecorationTypes.WHITE_BANNER,
            0.toByte(),
            0.toByte(),
            0.toByte(),
            Optional.of(Component.literal("Château du sud")),
        )
        decoMap["deco_1"] = deco

        val refreshed = MapBannerTranslationManager.refreshMap(mapItemStack)
        refreshed shouldBe true
    }

    test("refreshAll and clearCache reset state properly") {
        MapBannerTranslationManager.textComponentCache["en::Test"] = Component.literal("Test")
        MapBannerTranslationManager.failedBanners.add("en::Fail")

        val count = MapBannerTranslationManager.refreshAll()
        count shouldBe 1
        MapBannerTranslationManager.textComponentCache.isEmpty() shouldBe true
        MapBannerTranslationManager.failedBanners.isEmpty() shouldBe true
    }
})
