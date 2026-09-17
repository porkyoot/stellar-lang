package com.stellar.lang.chat

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.network.chat.Component

class ChatTranslationManagerSpec : FunSpec({
    beforeEach {
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateChat.setValue(true, false)
        config.targetLanguage.setValue("en", false)
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
        returned shouldBe orig

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
})
