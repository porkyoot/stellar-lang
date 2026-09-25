@file:Suppress("LargeClass")

package com.stellar.lang.chat

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor

class ChatTranslationManagerSpec : FunSpec({
    beforeEach {
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateChat.setValue(true, false)
        config.targetLanguage.setValue("en", false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
    }

    test("TrackedChatMessage state management and toggling") {
        val orig = Component.literal("Bonjour")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 42L,
            originalComponent = orig,
            plainText = "Bonjour",
        )

        tracked.id shouldBe 42L
        tracked.originalComponent shouldBe orig
        tracked.plainText shouldBe "Bonjour"
        tracked.translatedComponent shouldBe null
        tracked.isShowingOriginal shouldBe false

        tracked.toggleOriginal() shouldBe true
        tracked.isShowingOriginal shouldBe true

        tracked.toggleOriginal() shouldBe false
        tracked.isShowingOriginal shouldBe false

        val translated = Component.literal("Hello")
        tracked.updateTranslation(translated)
        tracked.translatedComponent shouldBe translated
    }

    test("processIncomingMessage skips when mod or chat is disabled") {
        val config = TranslationService.getConfig()
        val msg = Component.literal("Bonjour le monde")

        config.enabled.setValue(false, false)
        ChatTranslationManager.processIncomingMessage(msg) shouldBe msg

        config.enabled.setValue(true, false)
        config.translateChat.setValue(false, false)
        ChatTranslationManager.processIncomingMessage(msg) shouldBe msg
    }

    test("processIncomingMessage skips commands, short messages, and already translated messages") {
        val cmd = Component.literal("/gamemode creative")
        ChatTranslationManager.processIncomingMessage(cmd) shouldBe cmd

        val short = Component.literal("a")
        ChatTranslationManager.processIncomingMessage(short) shouldBe short

        val empty = Component.literal("   ")
        ChatTranslationManager.processIncomingMessage(empty) shouldBe empty

        val alreadyTrans = Component.literal("[T] Hello World")
        ChatTranslationManager.processIncomingMessage(alreadyTrans) shouldBe alreadyTrans
    }

    test("processIncomingMessage returns original when detected language is same as target") {
        val msg = Component.literal("Hello friend")
        val fakeResult = TranslationResult("Hello friend", "Hello friend", "en", "en", true)
        TranslationService.putCache(fakeResult)

        val resultComp = ChatTranslationManager.processIncomingMessage(msg)
        resultComp.string shouldBe "Hello friend"
    }

    test("processIncomingMessage returns translated component with [T] badge on cache hit") {
        val msg = Component.literal("Bonjour mon ami")
        val fakeResult = TranslationResult("Bonjour mon ami", "Hello my friend", "fr", "en", false)
        TranslationService.putCache(fakeResult)

        val resultComp = ChatTranslationManager.processIncomingMessage(msg)
        resultComp.string shouldContain "[T] "
        resultComp.string shouldContain "Hello my friend"
    }

    test("handleCommandClick validates prefix and toggles valid message state") {
        ChatTranslationManager.handleCommandClick("invalid_cmd") shouldBe false
        ChatTranslationManager.handleCommandClick("/stellar_lang_chat_toggle not_a_number") shouldBe false
        ChatTranslationManager.handleCommandClick("/stellar_lang_chat_toggle 99999999") shouldBe false

        // Seed a message through processIncomingMessage
        val msg = Component.literal("Hola a todos")
        val fakeResult = TranslationResult("Hola a todos", "Hello everyone", "es", "en", false)
        TranslationService.putCache(fakeResult)

        val withBadge = ChatTranslationManager.processIncomingMessage(Component.literal("Hola a todos"))
        withBadge.string shouldContain "[T] "

        val tracked = ChatTranslationManager.TrackedChatMessage(12_345L, msg, "Hola a todos")
        val mapField = ChatTranslationManager::class.java.getDeclaredField("trackedMessages")
        mapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = mapField.get(ChatTranslationManager) as
            java.util.concurrent.ConcurrentHashMap<Long, ChatTranslationManager.TrackedChatMessage>
        map[12_345L] = tracked

        ChatTranslationManager.handleCommandClick("/stellar_lang_chat_toggle 12345") shouldBe true
        tracked.isShowingOriginal shouldBe true
        ChatTranslationManager.handleCommandClick("/stellar_lang_chat_toggle 12345") shouldBe true
        tracked.isShowingOriginal shouldBe false
    }

    test("processIncomingMessage triggers background translation and scheduler") {
        var scheduledTracked: ChatTranslationManager.TrackedChatMessage? = null
        ChatTranslationManager.refreshScheduler = { tracked ->
            scheduledTracked = tracked
        }

        val orig = Component.literal("Wie geht es dir?")
        val returned = ChatTranslationManager.processIncomingMessage(orig)
        returned.string shouldBe "[...] Wie geht es dir?"

        // Test background translation callback execution with seeded cache for translateAsync
        val tracked = ChatTranslationManager.TrackedChatMessage(88L, orig, "Wie geht es dir?")
        val fakeResult = TranslationResult("Wie geht es dir?", "How are you?", "de", "en", false)
        TranslationService.putCache(fakeResult)

        val triggerMethod = ChatTranslationManager::class.java.getDeclaredMethod(
            "triggerBackgroundChatTranslation",
            ChatTranslationManager.TrackedChatMessage::class.java,
            String::class.java,
        )
        triggerMethod.isAccessible = true
        triggerMethod.invoke(ChatTranslationManager, tracked, "Wie geht es dir?")

        scheduledTracked shouldBe tracked
        tracked.translatedComponent shouldNotBe null

        val scheduleMethod = ChatTranslationManager::class.java.getDeclaredMethod(
            "scheduleChatRefresh",
            ChatTranslationManager.TrackedChatMessage::class.java,
        )
        scheduleMethod.isAccessible = true

        val updateMethod = ChatTranslationManager::class.java.getDeclaredMethod(
            "updateChatDisplay",
            ChatTranslationManager.TrackedChatMessage::class.java,
        )
        updateMethod.isAccessible = true

        // Clear refreshScheduler so minecraftExecutor is tested
        ChatTranslationManager.refreshScheduler = null
        var executedRunnable = false
        ChatTranslationManager.minecraftExecutor = { runnable ->
            executedRunnable = true
            runnable.run()
        }

        var accessorProvided = false
        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        val dummyAccessor = object : com.stellar.lang.mixin.ChatComponentAccessor {
            override fun stellarGetAllMessages(): MutableList<net.minecraft.client.multiplayer.chat.GuiMessage> =
                messageList
            override fun stellarRefreshTrimmedMessages() {
                // No-op for dummy accessor in test
            }
        }
        ChatTranslationManager.chatAccessorProvider = {
            accessorProvided = true
            dummyAccessor
        }

        scheduleMethod.invoke(ChatTranslationManager, tracked)
        executedRunnable shouldBe true
        accessorProvided shouldBe true

        // Test scheduleChatRefresh and updateChatDisplay headless fallbacks
        ChatTranslationManager.refreshScheduler = null
        ChatTranslationManager.minecraftExecutor = null
        ChatTranslationManager.chatAccessorProvider = null
        scheduleMethod.invoke(ChatTranslationManager, tracked)
        updateMethod.invoke(ChatTranslationManager, tracked)
    }

    test("updateChatDisplayWithAccessor updates message content in accessor") {
        val orig = Component.literal("Original text")
        val trans = Component.literal("Translated text")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 55L,
            originalComponent = orig,
            plainText = "Original text",
            translatedComponent = trans,
            isShowingOriginal = false,
        )

        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        var refreshed = false
        val fakeAccessor = object : com.stellar.lang.mixin.ChatComponentAccessor {
            override fun stellarGetAllMessages(): MutableList<net.minecraft.client.multiplayer.chat.GuiMessage> =
                messageList
            override fun stellarRefreshTrimmedMessages() {
                refreshed = true
            }
        }

        val dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            100,
            orig,
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.add(dummyMsg)

        // When showing translated
        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        refreshed shouldBe true
        messageList[0].content() shouldBe trans

        // When showing original
        tracked.isShowingOriginal = true
        refreshed = false
        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        refreshed shouldBe true
        messageList[0].content() shouldBe orig

        // When message is not found
        messageList.clear()
        refreshed = false
        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        refreshed shouldBe false
    }

    test("scheduleChatRefresh invokes minecraftExecutor and updates chat display") {
        val orig = Component.literal("Original scheduled")
        val trans = Component.literal("Translated scheduled")
        val tracked = ChatTranslationManager.TrackedChatMessage(5555L, orig, "Original scheduled")
        tracked.updateTranslation(trans)

        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        val dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            100,
            orig,
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.add(dummyMsg)

        var refreshed = false
        val fakeAccessor = object : com.stellar.lang.mixin.ChatComponentAccessor {
            override fun stellarGetAllMessages(): MutableList<net.minecraft.client.multiplayer.chat.GuiMessage> =
                messageList
            override fun stellarRefreshTrimmedMessages() {
                refreshed = true
            }
        }

        ChatTranslationManager.chatAccessorProvider = { fakeAccessor }
        ChatTranslationManager.chatAccessorProvider shouldNotBe null
        ChatTranslationManager.minecraftExecutor = { runnable -> runnable.run() }
        ChatTranslationManager.minecraftExecutor shouldNotBe null

        val scheduleMethod = ChatTranslationManager::class.java.getDeclaredMethod(
            "scheduleChatRefresh",
            ChatTranslationManager.TrackedChatMessage::class.java,
        )
        scheduleMethod.isAccessible = true
        scheduleMethod.invoke(ChatTranslationManager, tracked)

        refreshed shouldBe true
        messageList[0].content() shouldBe trans

        // Test refreshScheduler hook
        var customScheduled = false
        ChatTranslationManager.refreshScheduler = { customScheduled = true }
        ChatTranslationManager.refreshScheduler shouldNotBe null
        scheduleMethod.invoke(ChatTranslationManager, tracked)
        customScheduled shouldBe true
        ChatTranslationManager.refreshScheduler = null

        // Test handleCommandClick updating display via chatAccessorProvider
        val trackedMapField = ChatTranslationManager::class.java.getDeclaredField("trackedMessages")
        trackedMapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val trackedMap = trackedMapField.get(ChatTranslationManager) as
            java.util.concurrent.ConcurrentHashMap<Long, ChatTranslationManager.TrackedChatMessage>
        trackedMap[5555L] = tracked

        refreshed = false
        ChatTranslationManager.handleCommandClick("/stellar_lang_chat_toggle 5555") shouldBe true
        refreshed shouldBe true

        ChatTranslationManager.chatAccessorProvider = null
        ChatTranslationManager.minecraftExecutor = null
    }

    test("extractChatPayload separates Chat Heads prefix and retains player head component") {
        // Build a simulated Chat Heads component: < + [Player head] + Player + >  + message
        val headComp = Component.literal("[Dev1lroot head]")
        val chatHeadsMessage = Component.empty()
            .append(Component.literal("<"))
            .append(headComp)
            .append(Component.literal("Dev1lroot"))
            .append(Component.literal("> "))
            .append(Component.literal("Bonjour tout le monde"))

        val payload = ChatTranslationManager.extractChatPayload(chatHeadsMessage)
        payload.messageText shouldBe "Bonjour tout le monde"
        payload.prefixComponent shouldNotBe null
        payload.prefixComponent!!.string shouldBe "<[Dev1lroot head]Dev1lroot> "
    }

    test("extractChatPayload separates standard player brackets and colon prefixes") {
        val standard = Component.literal("<Dev1lroot> Hola amigo")
        val payload1 = ChatTranslationManager.extractChatPayload(standard)
        payload1.messageText shouldBe "Hola amigo"
        payload1.prefixComponent?.string shouldBe "<Dev1lroot> "

        val colonFormat = Component.literal("Dev1lroot: Guten Tag")
        val payload2 = ChatTranslationManager.extractChatPayload(colonFormat)
        payload2.messageText shouldBe "Guten Tag"
        payload2.prefixComponent?.string shouldBe "Dev1lroot: "

        val systemMsg = Component.literal("Server is restarting")
        val payload3 = ChatTranslationManager.extractChatPayload(systemMsg)
        payload3.messageText shouldBe "Server is restarting"
        payload3.prefixComponent shouldBe null
    }

    test("processIncomingMessage with Chat Heads format preserves prefix and prepends [T]") {
        val headComp = Component.literal("[Porkyoot head]")
        val incoming = Component.empty()
            .append(Component.literal("<"))
            .append(headComp)
            .append(Component.literal("Porkyoot"))
            .append(Component.literal("> "))
            .append(Component.literal("J'ai trouve un spawner"))

        val fakeResult = TranslationResult(
            originalText = "J'ai trouve un spawner",
            translatedText = "I found a spawner",
            detectedLanguage = "fr",
            targetLanguage = "en",
            isSameLanguage = false,
        )
        TranslationService.putCache(fakeResult)

        val result = ChatTranslationManager.processIncomingMessage(incoming)
        result.string shouldContain "[T] "
        result.string shouldContain "<[Porkyoot head]Porkyoot> "
        result.string shouldContain "I found a spawner"
    }

    test("updateChatDisplayWithAccessor matches by plain text fallback") {
        val orig = Component.literal("Unique original message")
        val trans = Component.literal("Unique translated message")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 777L,
            originalComponent = orig,
            plainText = "Unique original message",
            translatedComponent = trans,
        )

        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        val dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            10,
            Component.literal("Unique original message"),
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.add(dummyMsg)

        var refreshed = false
        val fakeAccessor = object : com.stellar.lang.mixin.ChatComponentAccessor {
            override fun stellarGetAllMessages(): MutableList<net.minecraft.client.multiplayer.chat.GuiMessage> =
                messageList
            override fun stellarRefreshTrimmedMessages() {
                refreshed = true
            }
        }

        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        refreshed shouldBe true
        messageList[0].content() shouldBe trans
    }

    test("copyChatHeadsData copies head data between objects with matching methods") {
        data class DummyHeadData(val id: String)
        class SourceObj(val head: DummyHeadData?) {
            fun `chatheads$getHeadData`(): DummyHeadData? = head
        }
        class TargetObj {
            var head: DummyHeadData? = null
            fun `chatheads$setHeadData`(data: DummyHeadData) {
                head = data
            }
        }

        val src = SourceObj(DummyHeadData("player123"))
        val dst = TargetObj()
        ChatTranslationManager.copyChatHeadsData(src, dst)
        dst.head shouldBe DummyHeadData("player123")
    }

    test("copyChatHeadsData handles objects without chat heads methods gracefully") {
        ChatTranslationManager.copyChatHeadsData("plain source", "plain target")
    }

    test("createTranslatedComponent with default prefix and isMatchingMessage edge cases") {
        val fakeResult = TranslationResult("Bonjour", "Hello", "fr", "en", false)
        val comp = ChatTranslationManager.createTranslatedComponent(999L, fakeResult)
        comp.string shouldContain "[T] "
        comp.string shouldContain "Hello"

        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 999L,
            originalComponent = Component.literal("Bonjour"),
            plainText = "Bonjour",
            translatedComponent = comp,
        )

        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        var dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            10,
            comp,
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.add(dummyMsg)

        var refreshed = false
        val fakeAccessor = object : com.stellar.lang.mixin.ChatComponentAccessor {
            override fun stellarGetAllMessages(): MutableList<net.minecraft.client.multiplayer.chat.GuiMessage> =
                messageList
            override fun stellarRefreshTrimmedMessages() {
                refreshed = true
            }
        }

        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        refreshed shouldBe true

        // Match by original component
        dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            11,
            tracked.originalComponent,
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.clear()
        messageList.add(dummyMsg)
        refreshed = false
        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        refreshed shouldBe true

        // Match by translated string
        dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            12,
            Component.literal(comp.string),
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.clear()
        messageList.add(dummyMsg)
        refreshed = false
        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        refreshed shouldBe true
    }

    test("extractChatPayload edge cases with blank message after prefix") {
        val blankAfterPrefix = Component.literal("<Player>   ")
        val payload = ChatTranslationManager.extractChatPayload(blankAfterPrefix)
        payload.prefixComponent shouldBe null
        payload.messageText shouldBe "<Player>"
    }

    test("createFailedComponent generates red strikethrough bold badge") {
        val comp = ChatTranslationManager.createFailedComponent(123L, "Untranslatable text")
        comp.string shouldContain "[T] "
        comp.string shouldContain "Untranslatable text"
        val badge = comp.siblings.first()
        badge.style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.RED)
        badge.style.isBold shouldBe true
        badge.style.isStrikethrough shouldBe true
    }

    test("processIncomingMessage marks chat message failed when translation fails") {
        val config = TranslationService.getConfig()
        config.apiHost.setValue("http://127.0.0.1:1", false)

        var refreshedTracked: ChatTranslationManager.TrackedChatMessage? = null
        ChatTranslationManager.refreshScheduler = { refreshedTracked = it }

        val input = Component.literal("Bonjour les amis")
        val resultComp = ChatTranslationManager.processIncomingMessage(input)
        resultComp.string shouldBe "[...] Bonjour les amis"

        var attempts = 0
        while (attempts++ < 30 && refreshedTracked?.translatedComponent == null) {
            Thread.sleep(50)
        }

        val failedComp = refreshedTracked?.translatedComponent
        failedComp shouldNotBe null
        failedComp!!.string shouldContain "[T] "
        failedComp.string shouldContain "Bonjour les amis"
        val badge = failedComp.siblings.first()
        badge.style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.RED)
        badge.style.isStrikethrough shouldBe true
    }

    test("createFailedComponent with prefix component attaches prefix cleanly") {
        val prefix = Component.literal("<Alice> ")
        val comp = ChatTranslationManager.createFailedComponent(124L, "Error msg", prefix)
        comp.string shouldContain "<Alice> "
        comp.string shouldContain "Error msg"
    }

    test("processIncomingMessage immediately returns failed component if already failed in cache") {
        val key = TranslationService.cacheKey("Echec immediat", "en")
        TranslationService.isFailed("Echec immediat", "en") shouldBe false
        com.stellar.lang.service.TranslationCache.markFailed(key)

        val input = Component.literal("Echec immediat")
        val resultComp = ChatTranslationManager.processIncomingMessage(input)
        resultComp.string shouldContain "[T] "
        resultComp.string shouldContain "Echec immediat"
        val badge = resultComp.siblings.first()
        badge.style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.RED)
        badge.style.isStrikethrough shouldBe true
    }

    test("scheduleChatRefresh falls back to custom minecraftExecutor and chatAccessorProvider") {
        ChatTranslationManager.refreshScheduler = null
        var executed = false
        ChatTranslationManager.minecraftExecutor = { runnable ->
            runnable.run()
            executed = true
        }

        var accessorCalled = false
        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        val dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            1,
            Component.literal("Refresh Target"),
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.add(dummyMsg)

        val fakeAccessor = object : com.stellar.lang.mixin.ChatComponentAccessor {
            override fun stellarGetAllMessages(): MutableList<net.minecraft.client.multiplayer.chat.GuiMessage> {
                accessorCalled = true
                return messageList
            }
            override fun stellarRefreshTrimmedMessages() {
                // No-op for test
            }
        }
        ChatTranslationManager.chatAccessorProvider = { fakeAccessor }

        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 555L,
            originalComponent = Component.literal("Refresh Target"),
            plainText = "Refresh Target",
            translatedComponent = Component.literal("Refreshed"),
        )

        val method = ChatTranslationManager::class.java.getDeclaredMethod(
            "scheduleChatRefresh",
            ChatTranslationManager.TrackedChatMessage::class.java,
        )
        method.isAccessible = true
        method.invoke(ChatTranslationManager, tracked)

        executed shouldBe true
        accessorCalled shouldBe true
        ChatTranslationManager.minecraftExecutor = null
        ChatTranslationManager.chatAccessorProvider = null
    }

    test("handleCommandClick on failed message triggers retry and shows translating badge") {
        var refreshed = false
        ChatTranslationManager.refreshScheduler = { refreshed = true }

        val targetLang = TranslationService.getTargetLanguage()
        val text = "Echec a retester"
        val key = TranslationService.cacheKey(text, targetLang)
        com.stellar.lang.service.TranslationCache.markFailed(key)

        val orig = Component.literal(text)
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 55_555L,
            originalComponent = orig,
            plainText = text,
            messageText = text,
        )
        tracked.translatedComponent = ChatTranslationManager.createFailedComponent(55_555L, text)

        val mapField = ChatTranslationManager::class.java.getDeclaredField("trackedMessages")
        mapField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = mapField.get(ChatTranslationManager) as
            java.util.concurrent.ConcurrentHashMap<Long, ChatTranslationManager.TrackedChatMessage>
        map[55_555L] = tracked

        ChatTranslationManager.handleCommandClick("/stellar_lang_chat_toggle 55555") shouldBe true
        tracked.translatedComponent!!.string shouldBe "[...] $text"
        tracked.translatedComponent!!.siblings.first().style.color shouldBe
            TextColor.fromLegacyFormat(ChatFormatting.GRAY)
    }
})
