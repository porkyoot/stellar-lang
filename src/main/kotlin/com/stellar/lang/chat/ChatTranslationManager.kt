package com.stellar.lang.chat

import com.stellar.lang.mixin.ChatComponentAccessor
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages translation of chat messages and provides clickable [T] toggle functionality.
 */
object ChatTranslationManager {
    const val COMMAND_PREFIX: String = "/stellar_lang_chat_toggle"
    private const val MIN_TRANSLATABLE_LENGTH = 2
    private val idGenerator = AtomicLong(1000L)
    private val trackedMessages = ConcurrentHashMap<Long, TrackedChatMessage>()

    @Volatile
    var refreshScheduler: ((TrackedChatMessage) -> Unit)? = null

    @Volatile
    var minecraftExecutor: ((Runnable) -> Unit)? = null

    @Volatile
    var chatAccessorProvider: (() -> ChatComponentAccessor?)? = null

    class TrackedChatMessage(
        val id: Long,
        val originalComponent: Component,
        val plainText: String,
        var translatedComponent: Component? = null,
        var isShowingOriginal: Boolean = false,
    ) {
        fun toggleOriginal(): Boolean {
            isShowingOriginal = !isShowingOriginal
            return isShowingOriginal
        }

        fun updateTranslation(component: Component) {
            translatedComponent = component
        }
    }

    fun processIncomingMessage(component: Component): Component {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateChat.value()) {
            return component
        }

        val plainText = component.string.trim()
        if (shouldSkipMessage(plainText)) {
            return component
        }

        return resolveAndTranslate(component, plainText, config.targetLanguage.value())
    }

    private fun resolveAndTranslate(component: Component, plainText: String, targetLang: String): Component {
        val id = idGenerator.incrementAndGet()
        val tracked = TrackedChatMessage(id, component, plainText)
        trackedMessages[id] = tracked

        val cached = TranslationService.getCached(plainText, targetLang)
        if (cached != null && !cached.isSameLanguage) {
            val translated = createTranslatedComponent(id, cached)
            tracked.translatedComponent = translated
            return translated
        }

        if (cached == null) {
            triggerBackgroundChatTranslation(tracked, plainText)
        }

        return component
    }

    private fun triggerBackgroundChatTranslation(tracked: TrackedChatMessage, plainText: String) {
        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                val translated = createTranslatedComponent(tracked.id, result)
                tracked.translatedComponent = translated
                scheduleChatRefresh(tracked)
            }
        }
    }

    private fun shouldSkipMessage(text: String): Boolean {
        return text.length < MIN_TRANSLATABLE_LENGTH || text.startsWith("/") || text.startsWith("[T]")
    }

    private fun createTranslatedComponent(id: Long, result: TranslationResult): MutableComponent {
        val badge = Component.literal("[T] ").withStyle { style ->
            style.withColor(ChatFormatting.AQUA)
                .withBold(true)
                .withHoverEvent(
                    HoverEvent.ShowText(
                        Component.literal(
                            "Translated: [${result.detectedLanguage} -> ${result.targetLanguage}]\n" +
                                "Original: ${result.originalText}\n" +
                                "Click to toggle original/translated text",
                        ),
                    ),
                )
                .withClickEvent(ClickEvent.RunCommand("$COMMAND_PREFIX $id"))
        }

        return Component.empty().append(badge).append(Component.literal(result.translatedText))
    }

    private fun scheduleChatRefresh(tracked: TrackedChatMessage) {
        val customScheduler = refreshScheduler
        if (customScheduler != null) {
            customScheduler(tracked)
            return
        }

        val customExecutor = minecraftExecutor
        if (customExecutor != null) {
            customExecutor { updateChatDisplay(tracked) }
            return
        }

        val mc = runCatching { Minecraft.getInstance() }.getOrNull() ?: return
        mc.execute {
            updateChatDisplay(tracked)
        }
    }

    internal fun updateChatDisplayWithAccessor(accessor: ChatComponentAccessor, tracked: TrackedChatMessage) {
        val messages = accessor.stellarGetAllMessages()

        val activeContent = if (tracked.isShowingOriginal) {
            tracked.originalComponent
        } else {
            tracked.translatedComponent ?: tracked.originalComponent
        }

        val index = messages.indexOfFirst { msg ->
            msg.content() == tracked.originalComponent || msg.content() == tracked.translatedComponent
        }

        if (index != -1) {
            val oldMsg = messages[index]
            val newMsg = GuiMessage(
                oldMsg.addedTime(),
                activeContent,
                oldMsg.signature(),
                oldMsg.source(),
                oldMsg.tag(),
            )
            messages[index] = newMsg
            accessor.stellarRefreshTrimmedMessages()
        }
    }

    private fun updateChatDisplay(tracked: TrackedChatMessage) {
        val customAccessor = chatAccessorProvider?.invoke()
        if (customAccessor != null) {
            updateChatDisplayWithAccessor(customAccessor, tracked)
            return
        }

        val mc = runCatching { Minecraft.getInstance() }.getOrNull() ?: return
        val chat = mc.gui.hud.getChat()
        val accessor = chat as? ChatComponentAccessor ?: return
        updateChatDisplayWithAccessor(accessor, tracked)
    }

    fun handleCommandClick(command: String): Boolean {
        if (!command.startsWith(COMMAND_PREFIX)) return false

        val idStr = command.removePrefix(COMMAND_PREFIX).trim()
        val id = idStr.toLongOrNull() ?: return false
        val tracked = trackedMessages[id] ?: return false

        tracked.isShowingOriginal = !tracked.isShowingOriginal
        updateChatDisplay(tracked)
        return true
    }
}
