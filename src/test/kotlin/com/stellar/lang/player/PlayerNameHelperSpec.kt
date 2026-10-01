package com.stellar.lang.player

import com.stellar.lang.chat.ChatTranslationManager
import com.stellar.lang.entity.EntityTranslationManager
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.decoration.ArmorStand

@Suppress("LargeClass")
class PlayerNameHelperSpec : FunSpec({
    val unsafe by lazy {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        unsafeField.get(null) as sun.misc.Unsafe
    }

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    beforeEach {
        PlayerNameHelper.clearProviders()
        TranslationService.clearCache()
        EntityTranslationManager.clearCache()
        ChatTranslationManager.clearCache()

        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateChat.setValue(true, false)
        config.translateEntities.setValue(true, false)
        config.translatePlayerNames.setValue(false, false)
        config.targetLanguage.setValue("en", false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.hideIndicators.setValue(false, false)
    }

    afterEach {
        PlayerNameHelper.clearProviders()
        TranslationService.clearCache()
        EntityTranslationManager.clearCache()
        ChatTranslationManager.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateChat.setValue(true, false)
        config.translateEntities.setValue(true, false)
        config.translatePlayerNames.setValue(false, false)
        config.targetLanguage.setValue("en", false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.hideIndicators.setValue(false, false)
    }

    test("shouldTranslatePlayerNames reflects config value") {
        val config = TranslationService.getConfig()
        config.translatePlayerNames.setValue(false, false)
        PlayerNameHelper.shouldTranslatePlayerNames() shouldBe false

        config.translatePlayerNames.setValue(true, false)
        PlayerNameHelper.shouldTranslatePlayerNames() shouldBe true
    }

    test("isPlayerName validates name length, formatting, and custom providers") {
        // Blank or too short
        PlayerNameHelper.isPlayerName("") shouldBe false
        PlayerNameHelper.isPlayerName("a") shouldBe false
        PlayerNameHelper.isPlayerName("  x  ") shouldBe false

        // Custom provider match
        PlayerNameHelper.customPlayerNamesProvider = { setOf("Dev1lroot", "Porkyoot") }
        PlayerNameHelper.isPlayerName("Dev1lroot") shouldBe true
        PlayerNameHelper.isPlayerName("porkyoot") shouldBe true
        PlayerNameHelper.isPlayerName("<Dev1lroot>") shouldBe true
        PlayerNameHelper.isPlayerName("UnknownPlayer") shouldBe false

        // Local player provider match
        PlayerNameHelper.localPlayerNameProvider = { "LocalPlayer" }
        PlayerNameHelper.isPlayerName("LocalPlayer") shouldBe true
        PlayerNameHelper.localPlayerNameProvider = null

        // Online players provider match
        val profile = com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "OnlineAlice")
        val info = net.minecraft.client.multiplayer.PlayerInfo(profile, false)
        info.tabListDisplayName = Component.literal("DisplayAlice")
        PlayerNameHelper.onlinePlayersProvider = { listOf(info) }
        PlayerNameHelper.isPlayerName("OnlineAlice") shouldBe true
        PlayerNameHelper.isPlayerName("DisplayAlice") shouldBe true
        PlayerNameHelper.isPlayerName("OtherUnknown") shouldBe false
        PlayerNameHelper.onlinePlayersProvider = null
    }

    test("PlayerNameHelper providers and fallbacks") {
        PlayerNameHelper.customPlayerNamesProvider shouldBe null
        PlayerNameHelper.playerNamePredicate shouldBe null
        PlayerNameHelper.playerEntityPredicate shouldBe null
        PlayerNameHelper.localPlayerNameProvider shouldBe null
        PlayerNameHelper.onlinePlayersProvider shouldBe null

        PlayerNameHelper.getLocalPlayerName()
        PlayerNameHelper.getOnlinePlayers()
    }

    test("isPlayer identifies player entity and component player names") {
        val armorStand = unsafe.allocateInstance(ArmorStand::class.java) as ArmorStand
        PlayerNameHelper.isPlayer(armorStand) shouldBe false

        PlayerNameHelper.playerEntityPredicate = { it === armorStand }
        PlayerNameHelper.isPlayer(armorStand) shouldBe true
        PlayerNameHelper.playerEntityPredicate = null

        // With component matching known player name
        PlayerNameHelper.customPlayerNamesProvider = { setOf("Notch") }
        val notchComp = Component.literal("Notch")
        PlayerNameHelper.isPlayer(null, notchComp) shouldBe true

        val mobComp = Component.literal("Zombie")
        PlayerNameHelper.isPlayer(null, mobComp) shouldBe false
    }

    test("getKnownPlayerNames aggregates names from providers") {
        PlayerNameHelper.customPlayerNamesProvider = { setOf("Alice", "Bob") }
        PlayerNameHelper.localPlayerNameProvider = { "Charlie" }
        val profile = com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "David")
        val info = net.minecraft.client.multiplayer.PlayerInfo(profile, false)
        info.tabListDisplayName = Component.literal("DisplayDavid")
        PlayerNameHelper.onlinePlayersProvider = { listOf(info) }

        val names = PlayerNameHelper.getKnownPlayerNames()
        names.contains("Alice") shouldBe true
        names.contains("Bob") shouldBe true
        names.contains("Charlie") shouldBe true
        names.contains("David") shouldBe true
        names.contains("DisplayDavid") shouldBe true
    }

    test("protectPlayerNames wraps known player names in <ut> tags when disabled") {
        val config = TranslationService.getConfig()
        config.translatePlayerNames.setValue(false, false)
        PlayerNameHelper.customPlayerNamesProvider = { setOf("Porkyoot", "Dev1lroot") }

        val input = "Hello Porkyoot and Dev1lroot, welcome to the server!"
        val protectedText = PlayerNameHelper.protectPlayerNames(input)
        protectedText shouldContain "<ut>Porkyoot</ut>"
        protectedText shouldContain "<ut>Dev1lroot</ut>"

        // Should not wrap if already wrapped
        val doubleProtected = PlayerNameHelper.protectPlayerNames(protectedText)
        doubleProtected shouldNotContain "<ut><ut>"

        // Should not wrap partial matches (word boundary test)
        val partialInput = "PorkyootX is not Porkyoot"
        val partialProtected = PlayerNameHelper.protectPlayerNames(partialInput)
        partialProtected shouldNotContain "<ut>PorkyootX</ut>"
        partialProtected shouldContain "PorkyootX is not <ut>Porkyoot</ut>"
    }

    test("protectPlayerNames returns text unmodified when translatePlayerNames is true or no known names") {
        val config = TranslationService.getConfig()
        config.translatePlayerNames.setValue(true, false)
        PlayerNameHelper.customPlayerNamesProvider = { setOf("Porkyoot") }

        val input = "Hello Porkyoot"
        PlayerNameHelper.protectPlayerNames(input) shouldBe input

        // No known names
        config.translatePlayerNames.setValue(false, false)
        PlayerNameHelper.customPlayerNamesProvider = { emptySet() }
        PlayerNameHelper.protectPlayerNames(input) shouldBe input
    }

    test("EntityTranslationManager respects translatePlayerNames false for players") {
        val config = TranslationService.getConfig()
        config.translatePlayerNames.setValue(false, false)
        PlayerNameHelper.customPlayerNamesProvider = { setOf("Dev1lroot") }

        val orig = Component.literal("Dev1lroot")
        val fakeResult = TranslationResult("Dev1lroot", "RootOfTheDevil", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        // 1. When translatePlayerNames is false, player name is NOT translated
        val result = EntityTranslationManager.translateEntityName(null, orig)
        result shouldBe orig
        result.string shouldNotContain "[T] "

        // 2. Non-player name IS translated
        val mobOrig = Component.literal("Loup Sauvage")
        val mobResult = TranslationResult("Loup Sauvage", "Wild Wolf", "fr", "en", false)
        TranslationService.putCache(mobResult)
        val mobTranslated = EntityTranslationManager.translateEntityName(null, mobOrig)
        mobTranslated.string shouldContain "[T] "
        mobTranslated.string shouldContain "Wild Wolf"

        // 3. When translatePlayerNames is true, player name IS translated
        config.translatePlayerNames.setValue(true, false)
        val playerTranslated = EntityTranslationManager.translateEntityName(null, orig)
        playerTranslated.string shouldContain "[T] "
        playerTranslated.string shouldContain "RootOfTheDevil"
    }

    fun createMockEntity(nameComp: Component?): ArmorStand {
        val accessorField = net.minecraft.world.entity.Entity::class.java.getDeclaredField("DATA_CUSTOM_NAME")
        accessorField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val accessor = accessorField.get(null) as
            net.minecraft.network.syncher.EntityDataAccessor<java.util.Optional<Component>>

        val dataItem = net.minecraft.network.syncher.SynchedEntityData.DataItem(
            accessor,
            java.util.Optional.ofNullable(nameComp),
        )

        @Suppress("UNCHECKED_CAST")
        val items = java.lang.reflect.Array.newInstance(
            net.minecraft.network.syncher.SynchedEntityData.DataItem::class.java,
            accessor.id + 1,
        ) as Array<net.minecraft.network.syncher.SynchedEntityData.DataItem<*>?>
        items[accessor.id] = dataItem

        val synchedData = unsafe.allocateInstance(
            net.minecraft.network.syncher.SynchedEntityData::class.java,
        ) as net.minecraft.network.syncher.SynchedEntityData
        val itemsField = net.minecraft.network.syncher.SynchedEntityData::class.java.getDeclaredField("itemsById")
        itemsField.isAccessible = true
        itemsField.set(synchedData, items)

        val entity = unsafe.allocateInstance(ArmorStand::class.java) as ArmorStand
        val entityDataField = net.minecraft.world.entity.Entity::class.java.getDeclaredField("entityData")
        entityDataField.isAccessible = true
        entityDataField.set(entity, synchedData)
        return entity
    }

    test("EntityTranslationManager refreshEntity and onEntityLoaded respect translatePlayerNames") {
        val config = TranslationService.getConfig()
        config.translatePlayerNames.setValue(false, false)
        val armorStand = createMockEntity(Component.literal("PlayerOne"))
        PlayerNameHelper.playerEntityPredicate = { it === armorStand }

        // refreshEntity returns false for player when translatePlayerNames is false
        EntityTranslationManager.refreshEntity(armorStand) shouldBe false

        // onEntityLoaded does not trigger translation when translatePlayerNames is false
        EntityTranslationManager.onEntityLoaded(armorStand)
        EntityTranslationManager.textComponentCache.isEmpty() shouldBe true

        // When translatePlayerNames is true, refreshEntity proceeds
        config.translatePlayerNames.setValue(true, false)
        PlayerNameHelper.playerEntityPredicate = null
    }

    test("ChatTranslationManager extractChatPayload respects translatePlayerNames") {
        val config = TranslationService.getConfig()
        val standard = Component.literal("<Dev1lroot> Bonjour")

        // Default / false: prefix is separated
        config.translatePlayerNames.setValue(false, false)
        val payload1 = ChatTranslationManager.extractChatPayload(standard)
        payload1.prefixComponent shouldNotBe null
        payload1.prefixComponent?.string shouldBe "<Dev1lroot> "
        payload1.messageText shouldBe "Bonjour"

        // True: prefix is NOT separated, entire message is translated together
        config.translatePlayerNames.setValue(true, false)
        val payload2 = ChatTranslationManager.extractChatPayload(standard)
        payload2.prefixComponent shouldBe null
        payload2.messageText shouldBe "<Dev1lroot> Bonjour"
    }

    test("protectPlayerNames and decodeFromUntranslatableTags protect player names") {
        val config = TranslationService.getConfig()
        config.translatePlayerNames.setValue(false, false)
        PlayerNameHelper.customPlayerNamesProvider = { setOf("Porkyoot") }

        val protected = PlayerNameHelper.protectPlayerNames("Bonjour Porkyoot")
        protected shouldBe "Bonjour <ut>Porkyoot</ut>"

        val decoded = com.stellar.lang.format.FormattingTagHelper.decodeFromUntranslatableTags(
            "Hello <ut>Porkyoot</ut>",
        )
        decoded shouldBe "Hello Porkyoot"
    }

    test("isPlayerName respects playerNamePredicate when provided") {
        PlayerNameHelper.playerNamePredicate = { it == "AllowedPlayer" }
        PlayerNameHelper.isPlayerName("AllowedPlayer") shouldBe true
        PlayerNameHelper.isPlayerName("BlockedPlayer") shouldBe false
        PlayerNameHelper.playerNamePredicate = null
    }

    test("isPlayer with playerEntityPredicate and Player instance") {
        val armorStand = unsafe.allocateInstance(ArmorStand::class.java) as ArmorStand
        PlayerNameHelper.playerEntityPredicate = { false }
        PlayerNameHelper.isPlayer(armorStand) shouldBe false
        PlayerNameHelper.playerEntityPredicate = null

        val player = unsafe.allocateInstance(net.minecraft.client.player.RemotePlayer::class.java)
            as net.minecraft.client.player.RemotePlayer
        PlayerNameHelper.isPlayer(player) shouldBe true
    }

    test("playerProvider and connectionProvider hooks") {
        val remotePlayer = unsafe.allocateInstance(net.minecraft.client.player.RemotePlayer::class.java)
            as net.minecraft.client.player.RemotePlayer
        val profile = com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "TestRemote")
        val profileField = net.minecraft.world.entity.player.Player::class.java.getDeclaredField("gameProfile")
        profileField.isAccessible = true
        profileField.set(remotePlayer, profile)

        PlayerNameHelper.playerProvider = { remotePlayer }
        PlayerNameHelper.getLocalPlayerName() shouldBe "TestRemote"

        PlayerNameHelper.playerProvider = { null }
        PlayerNameHelper.getLocalPlayerName() shouldBe null

        val connection = unsafe.allocateInstance(net.minecraft.client.multiplayer.ClientPacketListener::class.java)
            as net.minecraft.client.multiplayer.ClientPacketListener
        PlayerNameHelper.connectionProvider = { connection }
        PlayerNameHelper.getOnlinePlayers() shouldBe null

        PlayerNameHelper.connectionProvider = { null }
        PlayerNameHelper.getOnlinePlayers() shouldBe null
    }

    test("isPlayerName and getKnownPlayerNames edge cases") {
        val profileBlank = com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "")
        val infoBlank = net.minecraft.client.multiplayer.PlayerInfo(profileBlank, false)
        infoBlank.tabListDisplayName = Component.literal("")

        val profileNoDisplay = com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "NoDisplay")
        val infoNoDisplay = net.minecraft.client.multiplayer.PlayerInfo(profileNoDisplay, false)
        infoNoDisplay.tabListDisplayName = null

        val profileMatchDisplay = com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "RealName")
        val infoMatchDisplay = net.minecraft.client.multiplayer.PlayerInfo(profileMatchDisplay, false)
        infoMatchDisplay.tabListDisplayName = Component.literal("CustomNick")

        PlayerNameHelper.localPlayerNameProvider = { "   " }
        PlayerNameHelper.onlinePlayersProvider = { listOf(infoBlank, infoNoDisplay, infoMatchDisplay) }

        PlayerNameHelper.isPlayerName("CustomNick") shouldBe true
        PlayerNameHelper.isPlayerName("NoDisplay") shouldBe true
        PlayerNameHelper.isPlayerName("RealName") shouldBe true
        PlayerNameHelper.isPlayerName("NotPresent") shouldBe false

        val known = PlayerNameHelper.getKnownPlayerNames()
        known.contains("NoDisplay") shouldBe true
        known.contains("RealName") shouldBe true
        known.contains("CustomNick") shouldBe true
        known.contains("") shouldBe false

        PlayerNameHelper.customPlayerNamesProvider = { setOf("X") }
        PlayerNameHelper.localPlayerNameProvider = null
        PlayerNameHelper.onlinePlayersProvider = null
        val shortText = "Test with X"
        PlayerNameHelper.protectPlayerNames(shortText) shouldBe shortText
    }

    test("EntityTranslationManager onEntityNameChanged ignores player name when translatePlayerNames is false") {
        val config = TranslationService.getConfig()
        config.translatePlayerNames.setValue(false, false)
        PlayerNameHelper.customPlayerNamesProvider = { setOf("PlayerBob") }
        EntityTranslationManager.onEntityNameChanged(Component.literal("PlayerBob"))
        EntityTranslationManager.textComponentCache.isEmpty() shouldBe true
    }

    test("EntityTranslationManager translateEntityName retry branch same language") {
        EntityTranslationManager.clearCache()
        val text = "RetrySameLangEnt"
        val comp = Component.literal(text)
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = "$targetLang::$text"

        EntityTranslationManager.failedEntities.add(textKey)
        EntityTranslationManager.lastEntityRetryTimes[textKey] = 0L
        TranslationService.markFailed(text)

        val sameResult = TranslationResult(text, text, targetLang, targetLang, true)
        TranslationService.putCache(sameResult)

        EntityTranslationManager.translateEntityName(null, comp)
    }

    test("ChatTranslationManager fast path for same language detection") {
        val config = TranslationService.getConfig()
        config.targetLanguage.setValue("ru", false)
        val russianMsg = Component.literal("<Dev1lroot> Привет мир")
        val result = ChatTranslationManager.processIncomingMessage(russianMsg)
        result.string shouldContain "Привет мир"
    }
})
