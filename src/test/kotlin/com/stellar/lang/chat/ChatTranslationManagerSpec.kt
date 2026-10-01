@file:Suppress("LargeClass", "VariableNaming")

package com.stellar.lang.chat

import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.network.chat.contents.objects.AtlasSprite
import net.minecraft.resources.Identifier

class DummyFieldSource {
    @Suppress("UnusedPrivateMember")
    val `chatheads$headData`: String = "field_head_data"
}

class DummyFieldTarget {
    var `chatheads$headData`: String? = null
}

class ChatTranslationManagerSpec : FunSpec({
    beforeEach {
        TranslationService.clearCache()
        val config = TranslationService.getConfig()
        config.enabled.setValue(true, false)
        config.translateChat.setValue(true, false)
        config.targetLanguage.setValue("en", false)
        config.translationPlugin.setValue("libretranslate", false)
        config.detectionPlugin.setValue("libretranslate", false)
        config.hideIndicators.setValue(false, false)
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
        val frFlag = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar("fr")
        resultComp.string shouldContain "$frFlag "
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
        val esFlag = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar("es")
        withBadge.string shouldContain "$esFlag "

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
    }

    test("extractChatPayload with Chat Heads sprite glyph does not duplicate message text") {
        val headComp = Component.literal("\uFFFC")
        val chatHeadsMessage = Component.empty()
            .append(Component.literal("<"))
            .append(headComp)
            .append(Component.literal("Porkyoot"))
            .append(Component.literal("> "))
            .append(Component.literal("Bonjour"))

        val payload = ChatTranslationManager.extractChatPayload(chatHeadsMessage)
        payload.messageText shouldBe "Bonjour"
        payload.prefixComponent shouldNotBe null
        payload.prefixComponent!!.string shouldBe "<\uFFFCPorkyoot> "

        val failedComp = ChatTranslationManager.createFailedComponent(
            123L,
            payload.messageText,
            payload.prefixComponent,
        )
        failedComp.string shouldBe "<\uFFFCPorkyoot> Bonjour"
        failedComp.string shouldNotContain "BonjourBonjour"

        val translatingComp = ChatTranslationManager.createTranslatingComponent(
            123L,
            payload.messageText,
            payload.prefixComponent,
        )
        translatingComp.string shouldBe "<\uFFFCPorkyoot> [...] Bonjour"
        translatingComp.string shouldNotContain "BonjourBonjour"
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
        val frFlag = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar("fr")
        result.string shouldContain "$frFlag "
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
        val frFlag = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar("fr")
        comp.string shouldContain "$frFlag "
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

    test("createFailedComponent generates component without badge") {
        val comp = ChatTranslationManager.createFailedComponent(123L, "Untranslatable text")
        comp.string shouldNotContain "[T]"
        comp.string shouldBe "Untranslatable text"
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
        failedComp!!.string shouldNotContain "[T]"
        failedComp.string shouldBe "Bonjour les amis"
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
        resultComp.string shouldNotContain "[T]"
        resultComp.string shouldBe "Echec immediat"
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

    test("translatable component prefix extraction does not duplicate message text") {
        val translatable = Component.translatable("chat.type.text", "Player", "Hello")
        val payload = ChatTranslationManager.extractChatPayload(translatable)
        payload.messageText shouldBe "Hello"
        payload.prefixComponent shouldNotBe null
        payload.prefixComponent!!.string shouldBe "<Player> "

        val translating = ChatTranslationManager.createTranslatingComponent(
            100L,
            payload.messageText,
            payload.prefixComponent,
        )
        translating.string shouldBe "<Player> [...] Hello"
        translating.string shouldNotContain "HelloHello"

        val fakeResult = TranslationResult("Hello", "Bonjour", "en", "fr", false)
        val translated = ChatTranslationManager.createTranslatedComponent(
            100L,
            fakeResult,
            payload.prefixComponent,
        )
        val enFlag = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar("en")
        translated.string shouldBe "<Player> $enFlag Bonjour"
        translated.string shouldNotContain "HelloHello"
    }

    test("translation timeout transitions translating message to failed badge after 5 seconds") {
        var refreshed = false
        ChatTranslationManager.refreshScheduler = { refreshed = true }

        val orig = Component.literal("<Player> Slow translation")
        val payload = ChatTranslationManager.extractChatPayload(orig)
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 7001L,
            originalComponent = orig,
            plainText = orig.string,
            prefixComponent = payload.prefixComponent,
            messageText = payload.messageText,
        )
        val translatingComp = ChatTranslationManager.createTranslatingComponent(
            7001L,
            payload.messageText,
            payload.prefixComponent,
        )
        tracked.translatedComponent = translatingComp
        tracked.isPending = true
        tracked.currentAttempt = 1L

        refreshed shouldBe false
        ChatTranslationManager.handleTimeout(tracked, 1L)

        refreshed shouldBe true
        tracked.isPending shouldBe false
        tracked.translatedComponent shouldNotBe null
        val failed = tracked.translatedComponent!!
        failed.string shouldNotContain "[T]"
        failed.string shouldBe "<Player> Slow translation"
        failed.string shouldNotContain "Slow translationSlow translation"
        TranslationService.isFailed("Slow translation") shouldBe true
    }

    test("timeout does not affect message if attempt has already changed or not pending") {
        var refreshed = false
        ChatTranslationManager.refreshScheduler = { refreshed = true }

        val orig = Component.literal("Already done")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 7002L,
            originalComponent = orig,
            plainText = "Already done",
            isPending = false,
            currentAttempt = 2L,
        )

        ChatTranslationManager.handleTimeout(tracked, 1L)
        refreshed shouldBe false

        ChatTranslationManager.handleTimeout(tracked, 2L)
        refreshed shouldBe false
    }

    test("triggerBackgroundChatTranslation reverts translating component when result is same language") {
        var refreshed = false
        ChatTranslationManager.refreshScheduler = { refreshed = true }

        val orig = Component.literal("<Alice> Hello world")
        val payload = ChatTranslationManager.extractChatPayload(orig)
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 7003L,
            originalComponent = orig,
            plainText = orig.string,
            prefixComponent = payload.prefixComponent,
            messageText = payload.messageText,
        )
        tracked.translatedComponent = ChatTranslationManager.createTranslatingComponent(
            7003L,
            payload.messageText,
            payload.prefixComponent,
        )

        val sameLangResult = TranslationResult("Hello world", "Hello world", "en", "en", true)
        TranslationService.putCache(sameLangResult)

        ChatTranslationManager.triggerBackgroundChatTranslation(tracked, payload.messageText)

        refreshed shouldBe true
        tracked.isPending shouldBe false
        tracked.translatedComponent shouldBe null
    }

    test("isMatchingMessage matches GuiMessage containing toggle command click event") {
        val orig = Component.literal("<Bob> Test matching")
        val payload = ChatTranslationManager.extractChatPayload(orig)
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 7004L,
            originalComponent = orig,
            plainText = orig.string,
            prefixComponent = payload.prefixComponent,
            messageText = payload.messageText,
        )
        val translating = ChatTranslationManager.createTranslatingComponent(
            7004L,
            payload.messageText,
            payload.prefixComponent,
        )

        val guiMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            1,
            translating,
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )

        // When tracked.translatedComponent is updated to translated text, guiMsg still holds translating
        val fakeResult = TranslationResult("Test matching", "Test traduction", "en", "fr", false)
        val translated = ChatTranslationManager.createTranslatedComponent(
            7004L,
            fakeResult,
            payload.prefixComponent,
        )
        tracked.translatedComponent = translated

        ChatTranslationManager.isMatchingMessage(guiMsg, tracked) shouldBe true
    }

    test("timeoutExecutor getter and setter and custom executor") {
        val original = ChatTranslationManager.timeoutExecutor
        ChatTranslationManager.timeoutExecutor = original
        ChatTranslationManager.timeoutExecutor shouldBe original
    }

    test("sliceComponent with needed greater than text length") {
        val method = ChatTranslationManager::class.java.getDeclaredMethod(
            "sliceComponent",
            Component::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val comp = Component.literal("abc")
        val sliced = method.invoke(ChatTranslationManager, comp, 10) as Component
        sliced.string shouldBe "abc"
    }

    test("buildPrefixComponent with empty components or empty list") {
        val method = ChatTranslationManager::class.java.getDeclaredMethod(
            "buildPrefixComponent",
            List::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val emptyListResult = method.invoke(ChatTranslationManager, emptyList<Component>(), 5)
        emptyListResult shouldBe null

        val withEmptyString = listOf(Component.literal(""), Component.literal("<Tag> "))
        val res = method.invoke(ChatTranslationManager, withEmptyString, 6) as? Component
        res shouldNotBe null
        res!!.string shouldBe "<Tag> "
    }

    test("TranslationService markFailed with targetLang and default targetLang") {
        TranslationService.markFailed("unique_fail_text_1", "es")
        TranslationService.isFailed("unique_fail_text_1", "es") shouldBe true

        TranslationService.markFailed("unique_fail_text_2")
        TranslationService.isFailed("unique_fail_text_2") shouldBe true
    }

    test("triggerBackgroundChatTranslation schedules timeout and fires handler") {
        val customExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val origExecutor = ChatTranslationManager.timeoutExecutor
        ChatTranslationManager.timeoutExecutor = customExecutor

        var refreshed = false
        ChatTranslationManager.refreshScheduler = { refreshed = true }

        val orig = Component.literal("<Eve> Timeout trigger test")
        val payload = ChatTranslationManager.extractChatPayload(orig)
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 7005L,
            originalComponent = orig,
            plainText = orig.string,
            prefixComponent = payload.prefixComponent,
            messageText = payload.messageText,
        )
        tracked.translatedComponent = ChatTranslationManager.createTranslatingComponent(
            7005L,
            payload.messageText,
            payload.prefixComponent,
        )

        ChatTranslationManager.triggerBackgroundChatTranslation(tracked, payload.messageText)

        ChatTranslationManager.handleTimeout(tracked, tracked.currentAttempt)
        refreshed shouldBe true
        tracked.translatedComponent!!.string shouldNotContain "[T]"
        tracked.translatedComponent!!.string shouldContain "Timeout trigger test"

        ChatTranslationManager.timeoutExecutor = origExecutor
        customExecutor.shutdownNow()
    }

    test("updateChatDisplay when chatAccessorProvider returns null") {
        val updateMethod = ChatTranslationManager::class.java.getDeclaredMethod(
            "updateChatDisplay",
            ChatTranslationManager.TrackedChatMessage::class.java,
        )
        updateMethod.isAccessible = true
        ChatTranslationManager.chatAccessorProvider = { null }
        val tracked = ChatTranslationManager.TrackedChatMessage(99_999L, Component.literal("x"), "x")
        updateMethod.invoke(ChatTranslationManager, tracked)
        ChatTranslationManager.chatAccessorProvider = null
    }

    test("TranslationService normalizeLanguageCode edge cases") {
        val method = TranslationService::class.java.getDeclaredMethod(
            "normalizeLanguageCode",
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(TranslationService, "lol_us") shouldBe "en"
        method.invoke(TranslationService, "toolonglanguage") shouldBe "en"
        method.invoke(TranslationService, "x") shouldBe "en"
        method.invoke(TranslationService, null as String?) shouldBe "en"
    }

    test("buildPrefixComponent flattens component with siblings when within prefixLength") {
        val method = ChatTranslationManager::class.java.getDeclaredMethod(
            "buildPrefixComponent",
            List::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val compWithSiblings = Component.literal("<").append(Component.literal("Dev> "))
        val res = method.invoke(ChatTranslationManager, listOf(compWithSiblings), 10) as? Component
        res shouldNotBe null
        res!!.string shouldBe "<Dev> "
        res.siblings.size shouldBe 1
    }

    test("updateChatDisplayWithAccessor when translatedComponent is null uses originalComponent") {
        val orig = Component.literal("No translation")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 8888L,
            originalComponent = orig,
            plainText = "No translation",
            translatedComponent = null,
            isShowingOriginal = false,
        )
        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        val dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            1,
            orig,
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        messageList.add(dummyMsg)

        val fakeAccessor = object : com.stellar.lang.mixin.ChatComponentAccessor {
            override fun stellarGetAllMessages(): MutableList<net.minecraft.client.multiplayer.chat.GuiMessage> =
                messageList

            @Suppress("EmptyFunctionBlock")
            override fun stellarRefreshTrimmedMessages() {}
        }

        ChatTranslationManager.updateChatDisplayWithAccessor(fakeAccessor, tracked)
        messageList[0].content() shouldBe orig
    }

    test("isMatchingMessage matches when text equals translatedComponent string but not plainText") {
        val orig = Component.literal("Original text")
        val trans = Component.literal("Translated text")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 8889L,
            originalComponent = orig,
            plainText = "Original text",
            translatedComponent = trans,
        )
        // A message with plain string equal to translated text but not the same Component instance
        val guiMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            1,
            Component.literal("Translated text"),
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
            null,
        )
        ChatTranslationManager.isMatchingMessage(guiMsg, tracked) shouldBe true
    }

    test("ChatTranslationManager respects hideIndicators when creating components") {
        val config = TranslationService.getConfig()
        config.hideIndicators.setValue(true, false)
        try {
            val fakeResult = TranslationResult("Bonjour", "Hello", "fr", "en", false)
            val prefix = Component.literal("<Dev1lroot> ")
            val transComp = ChatTranslationManager.createTranslatedComponent(1234L, fakeResult, prefix)
            transComp.string shouldNotContain "[T]"
            transComp.string shouldBe "<Dev1lroot> Hello"

            val failedComp = ChatTranslationManager.createFailedComponent(1234L, "Bonjour", prefix)
            failedComp.string shouldNotContain "[T]"
            failedComp.string shouldBe "<Dev1lroot> Bonjour"

            val translatingComp = ChatTranslationManager.createTranslatingComponent(1234L, "Bonjour", prefix)
            translatingComp.string shouldNotContain "[...]"
            translatingComp.string shouldBe "<Dev1lroot> Bonjour"

            // Verify incoming message processing without indicators
            TranslationService.putCache(fakeResult)
            val incoming = Component.literal("<Dev1lroot> Bonjour")
            val processed = ChatTranslationManager.processIncomingMessage(incoming)
            processed.string shouldNotContain "[T]"
            processed.string shouldBe "<Dev1lroot> Hello"
        } finally {
            config.hideIndicators.setValue(false, false)
        }
    }

    test("does not translate death messages with translatable key") {
        val deathMsg = Component.translatable(
            "death.attack.player",
            Component.literal("Steve"),
            Component.literal("Alex"),
        )
        ChatTranslationManager.processIncomingMessage(deathMsg) shouldBe deathMsg
        ChatTranslationManager.processIncomingMessage(
            deathMsg,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_SERVER,
        ) shouldBe deathMsg
    }

    test("does not translate plain text death messages without player prefix") {
        val death1 = Component.literal("Steve drowned")
        ChatTranslationManager.processIncomingMessage(death1) shouldBe death1

        val death2 = Component.literal("Alex was slain by Zombie")
        ChatTranslationManager.processIncomingMessage(death2) shouldBe death2

        val death3 = Component.literal("Dev1lroot fell from a high place")
        ChatTranslationManager.processIncomingMessage(
            death3,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_SERVER,
        ) shouldBe death3
    }

    test("does not translate achievement and advancement messages") {
        val advMsg = Component.translatable(
            "chat.type.advancement.task",
            Component.literal("Steve"),
            Component.literal("Stone Age"),
        )
        ChatTranslationManager.processIncomingMessage(advMsg) shouldBe advMsg
        ChatTranslationManager.processIncomingMessage(
            advMsg,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_SERVER,
        ) shouldBe advMsg

        val plainAdv = Component.literal("Steve has made the advancement [Suit Up]")
        ChatTranslationManager.processIncomingMessage(plainAdv) shouldBe plainAdv
    }

    test("does not translate player join and leave messages") {
        val joinMsg = Component.translatable("multiplayer.player.joined", Component.literal("Steve"))
        ChatTranslationManager.processIncomingMessage(joinMsg) shouldBe joinMsg

        val plainLeave = Component.literal("Steve left the game")
        ChatTranslationManager.processIncomingMessage(plainLeave) shouldBe plainLeave
    }

    test("does not translate client system messages") {
        val clientMsg = Component.literal("Debug: Client chunk reloaded")
        ChatTranslationManager.processIncomingMessage(
            clientMsg,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_CLIENT,
        ) shouldBe clientMsg
    }

    test("translates player chat message even if it mentions dying") {
        val playerChat = Component.literal("<Steve> I died in the nether")
        val fakeResult = TranslationResult("I died in the nether", "Je suis mort dans le nether", "en", "en", false)
        TranslationService.putCache(fakeResult)
        val processed = ChatTranslationManager.processIncomingMessage(
            playerChat,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.PLAYER,
        )
        processed shouldNotBe playerChat
        processed.string shouldContain "Je suis mort dans le nether"
    }

    test("translates server MOTD and welcome messages") {
        val motd1 = Component.literal("Welcome to the server! Please read the rules.")
        val res1 = TranslationResult(
            "Welcome to the server! Please read the rules.",
            "Bienvenue sur le serveur ! Veuillez lire les règles.",
            "en",
            "en",
            false,
        )
        TranslationService.putCache(res1)
        val processed1 = ChatTranslationManager.processIncomingMessage(
            motd1,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_SERVER,
        )
        processed1 shouldNotBe motd1
        processed1.string shouldContain "Bienvenue sur le serveur"

        val motd2 = Component.literal("MOTD: Double Coins Weekend is active!")
        val res2 = TranslationResult(
            "Double Coins Weekend is active!",
            "Le week-end double pièces est actif !",
            "en",
            "en",
            false,
        )
        TranslationService.putCache(res2)
        val processed2 = ChatTranslationManager.processIncomingMessage(
            motd2,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.SYSTEM_SERVER,
        )
        processed2 shouldNotBe motd2
        processed2.string shouldContain "Le week-end double pièces est actif"
    }

    test("isAdminOrCommandMessage identifies admin and command message keys") {
        val cmd = Component.translatable("commands.give.success")
        val admin = Component.translatable("chat.type.admin")
        val gameMode = Component.translatable("gameMode.changed")
        val normal = Component.translatable("chat.type.text")

        ChatTranslationManager.isAdminOrCommandMessage(cmd) shouldBe true
        ChatTranslationManager.isAdminOrCommandMessage(admin) shouldBe true
        ChatTranslationManager.isAdminOrCommandMessage(gameMode) shouldBe true
        ChatTranslationManager.isAdminOrCommandMessage(normal) shouldBe false
    }

    test("isAdvancementMessage and isJoinLeaveMessage check translatable keys") {
        val adv1 = Component.translatable("chat.type.advancement.task")
        val adv2 = Component.translatable("advancement.test")
        val adv3 = Component.translatable("advancements.test")
        ChatTranslationManager.isAdvancementMessage(adv1, "foo") shouldBe true
        ChatTranslationManager.isAdvancementMessage(adv2, "foo") shouldBe true
        ChatTranslationManager.isAdvancementMessage(adv3, "foo") shouldBe true

        val join = Component.translatable("multiplayer.player.joined")
        val leave = Component.translatable("multiplayer.player.left")
        ChatTranslationManager.isJoinLeaveMessage(join, "foo") shouldBe true
        ChatTranslationManager.isJoinLeaveMessage(leave, "foo") shouldBe true
    }

    test("hasTranslatableKey traverses nested args and siblings") {
        val inner = Component.translatable("death.attack.cactus")
        val outerWithArg = Component.translatable("chat.type.text", "Steve", inner)
        val outerWithSibling = Component.empty().append(Component.translatable("death.attack.drown"))

        ChatTranslationManager.hasTranslatableKey(outerWithArg) { it.startsWith("death.") } shouldBe true
        ChatTranslationManager.hasTranslatableKey(outerWithSibling) { it.startsWith("death.") } shouldBe true
    }

    test("isMotdMessage detects server news and translatable welcome keys") {
        val news = Component.literal("Breaking: server news update")
        ChatTranslationManager.isMotdMessage(news, news.string) shouldBe true

        val welcomeTranslatable = Component.translatable("server.welcome.title")
        ChatTranslationManager.isMotdMessage(welcomeTranslatable, "Title") shouldBe true
    }

    test("createTranslatedComponent handles same language with and without prefix") {
        val sameResult = TranslationResult("Hello", "Hello", "en", "en", true)
        val withoutPrefix = ChatTranslationManager.createTranslatedComponent(1L, sameResult)
        withoutPrefix.string shouldBe "Hello"
        withoutPrefix.string.contains("[T]") shouldBe false

        val prefix = Component.literal("<Player> ")
        val withPrefix = ChatTranslationManager.createTranslatedComponent(2L, sameResult, prefix)
        withPrefix.string shouldBe "<Player> Hello"
        withPrefix.string.contains("[T]") shouldBe false
    }

    test("onTranslationSuccess reverts pending message on same language") {
        var refreshed = false
        ChatTranslationManager.refreshScheduler = { refreshed = true }

        val comp = Component.literal("<User> Bonjour")
        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 9999L,
            originalComponent = comp,
            plainText = "<User> Bonjour",
            messageText = "Bonjour",
            isPending = true,
        )
        ChatTranslationManager.trackedMessages[9999L] = tracked

        val sameResult = TranslationResult("Bonjour", "Bonjour", "en", "en", true)
        ChatTranslationManager.onTranslationSuccess(sameResult)

        refreshed shouldBe true
        tracked.isPending shouldBe false
        tracked.translatedComponent shouldBe null
    }

    test("flattenComponent and extractChatPayload preserve non-plain ComponentContents like Chat Heads sprites") {
        val sprite = Component.`object`(
            AtlasSprite(
                Identifier.fromNamespaceAndPath("minecraft", "gui"),
                Identifier.fromNamespaceAndPath("minecraft", "head"),
            ),
        )
        val msg = Component.empty()
            .append(Component.literal("<"))
            .append(sprite)
            .append(Component.literal("Dev1lroot"))
            .append(Component.literal("> "))
            .append(Component.literal("Ja ek hore deg"))

        val leaves = ChatTranslationManager.flattenComponent(msg)
        leaves.size shouldBe 5
        leaves[1].contents shouldNotBe net.minecraft.network.chat.contents.PlainTextContents.EMPTY
        (leaves[1].contents is net.minecraft.network.chat.contents.ObjectContents) shouldBe true

        val payload = ChatTranslationManager.extractChatPayload(msg)
        payload.messageText shouldBe "Ja ek hore deg"
        payload.prefixComponent shouldNotBe null
        payload.prefixComponent!!.string shouldBe "<[head@gui]Dev1lroot> "

        val prefixLeaves = ChatTranslationManager.flattenComponent(payload.prefixComponent)
        val spriteLeaf = prefixLeaves.firstOrNull { it.contents is net.minecraft.network.chat.contents.ObjectContents }
        spriteLeaf shouldNotBe null
    }

    test("extractChatPayload matches diverse Chat Heads prefix formats") {
        val formats = listOf(
            "<\uFFFCDev1lroot> Ja",
            "\uFFFC<Dev1lroot> Ja",
            "\uFFFC <Dev1lroot> Ja",
            "[\uFFFC] <Dev1lroot> Ja",
            "[VIP] <\uFFFCDev1lroot> Ja",
            "\uFFFCDev1lroot: Ja",
            "\uFFFC Dev1lroot: Ja",
        )

        for (fmt in formats) {
            val comp = Component.literal(fmt)
            val payload = ChatTranslationManager.extractChatPayload(comp)
            payload.messageText shouldBe "Ja"
            payload.prefixComponent shouldNotBe null
        }
    }

    test("createOriginalComponent renders flag with strikethrough and original text") {
        val result = TranslationResult("Ja ek hore deg", "Yes I hear you", "no", "en", false)
        val prefix = Component.literal("<Dev1lroot> ")
        val comp = ChatTranslationManager.createOriginalComponent(555L, result, prefix)

        val noFlag = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar("no")
        comp.string shouldContain "<Dev1lroot> "
        comp.string shouldContain "$noFlag "
        comp.string shouldContain "Ja ek hore deg"

        // Verify click event is present on badge
        val badge = comp.siblings.firstOrNull { it.string.contains(noFlag) }
        val cmd = (badge?.style?.clickEvent as? net.minecraft.network.chat.ClickEvent.RunCommand)?.command()
        cmd shouldBe "/stellar_lang_chat_toggle 555"
    }

    test("copyChatHeadsData copies via field reflection if method reflection unavailable") {
        val src = DummyFieldSource()
        val dst = DummyFieldTarget()
        ChatTranslationManager.copyChatHeadsData(src, dst)
        dst.`chatheads$headData` shouldBe "field_head_data"
    }

    test("updateChatDisplayWithAccessor preserves prepended head from oldMsg") {
        val headSprite = Component.`object`(
            AtlasSprite(
                Identifier.fromNamespaceAndPath("minecraft", "gui"),
                Identifier.fromNamespaceAndPath("minecraft", "head"),
            ),
        )
        val oldContent = Component.empty().append(headSprite).append(Component.literal("<Dev1lroot> Ja"))
        val orig = Component.literal("<Dev1lroot> Ja")
        val trans = Component.literal("<Dev1lroot> Yes")

        val tracked = ChatTranslationManager.TrackedChatMessage(
            id = 888L,
            originalComponent = orig,
            plainText = "<Dev1lroot> Ja",
            translatedComponent = trans,
        )

        val messageList = mutableListOf<net.minecraft.client.multiplayer.chat.GuiMessage>()
        val dummyMsg = net.minecraft.client.multiplayer.chat.GuiMessage(
            10,
            oldContent,
            null,
            net.minecraft.client.multiplayer.chat.GuiMessageSource.PLAYER,
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
        val newContent = messageList[0].content()
        newContent.string shouldBe "[head@gui]<Dev1lroot> Yes"
        val leaves = ChatTranslationManager.flattenComponent(newContent)
        (leaves[0].contents is net.minecraft.network.chat.contents.ObjectContents) shouldBe true
    }
})
