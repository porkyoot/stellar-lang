@file:Suppress("TooManyFunctions")

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
import net.minecraft.network.chat.contents.PlainTextContents
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Manages translation of chat messages, compatible with Chat Heads and player prefixes,
 * providing clickable [T] toggle functionality.
 */
@Suppress("LargeClass")
object ChatTranslationManager {
    const val COMMAND_PREFIX: String = "/stellar_lang_chat_toggle"
    private const val MIN_TRANSLATABLE_LENGTH = 2
    private val idGenerator = AtomicLong(1000L)
    private val trackedMessages = ConcurrentHashMap<Long, TrackedChatMessage>()

    // Matches standard player chat prefixes, with or without Chat Heads player sprites:
    // e.g. "<[Porkyoot head]Porkyoot> ", "<Dev1lroot> ", "[VIP] <Dev1lroot> ", "Dev1lroot: ", "Dev1lroot » "
    private val CHAT_PREFIX_REGEX = Pattern.compile(
        """^(\s*(?:\[[^\]]*\]\s*)*<[^>]+>\s*|^\s*\[[^\]]+\]\s+<\w+>\s*|^\s*[\w\u00A7]{2,20}\s*[:»>]\s*)""",
    )

    @Volatile
    var refreshScheduler: ((TrackedChatMessage) -> Unit)? = null

    @Volatile
    var minecraftExecutor: ((Runnable) -> Unit)? = null

    @Volatile
    var chatAccessorProvider: (() -> ChatComponentAccessor?)? = null

    init {
        TranslationService.addSuccessListener { result ->
            onTranslationSuccess(result)
        }
    }

    internal fun onTranslationSuccess(result: TranslationResult) {
        if (result.isSameLanguage) return
        for (tracked in trackedMessages.values) {
            if (tracked.messageText == result.originalText) {
                val translated = createTranslatedComponent(tracked.id, result, tracked.prefixComponent)
                tracked.translatedComponent = translated
                scheduleChatRefresh(tracked)
            }
        }
    }

    data class ParsedChatPayload(
        val prefixComponent: Component?,
        val messageText: String,
    )

    class TrackedChatMessage(
        val id: Long,
        val originalComponent: Component,
        val plainText: String,
        val prefixComponent: Component? = null,
        val messageText: String = plainText,
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

    fun extractChatPayload(component: Component): ParsedChatPayload {
        val fullText = component.string
        val matcher = CHAT_PREFIX_REGEX.matcher(fullText)
        if (!matcher.find() || matcher.start() != 0) {
            return ParsedChatPayload(null, fullText.trim())
        }

        val prefixText = matcher.group(1)
        val prefixLength = prefixText.length
        val messageText = fullText.substring(prefixLength).trim()
        if (messageText.isBlank()) {
            return ParsedChatPayload(null, fullText.trim())
        }

        val prefixComp = buildPrefixComponent(component.toFlatList(), prefixLength)
        return ParsedChatPayload(prefixComp, messageText)
    }

    private fun buildPrefixComponent(flatList: List<Component>, prefixLength: Int): Component? {
        val prefixComponents = mutableListOf<Component>()
        var currentLen = 0

        for (comp in flatList) {
            if (currentLen >= prefixLength) break

            val compLen = comp.string.length
            if (currentLen + compLen <= prefixLength) {
                prefixComponents.add(comp)
                currentLen += compLen
            } else {
                val needed = prefixLength - currentLen
                prefixComponents.add(sliceComponent(comp, needed))
                currentLen += needed
            }
        }

        if (prefixComponents.isEmpty()) return null
        val res = Component.empty()
        prefixComponents.forEach { res.append(it) }
        return res
    }

    private fun sliceComponent(comp: Component, needed: Int): Component {
        val contents = comp.contents
        if (contents is PlainTextContents) {
            val text = contents.text()
            if (needed <= text.length) {
                return Component.literal(text.substring(0, needed)).setStyle(comp.style)
            }
        }
        return comp
    }

    fun processIncomingMessage(component: Component): Component {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateChat.value()) {
            return component
        }

        val plainText = component.string.trim()
        val payload = extractChatPayload(component)
        if (shouldSkipMessage(plainText) || shouldSkipMessage(payload.messageText)) {
            return component
        }

        return resolveAndTranslate(component, payload, TranslationService.getTargetLanguage())
    }

    private fun resolveAndTranslate(
        component: Component,
        payload: ParsedChatPayload,
        targetLang: String,
    ): Component {
        val id = idGenerator.incrementAndGet()
        val plainText = component.string.trim()
        val tracked = TrackedChatMessage(
            id = id,
            originalComponent = component,
            plainText = plainText,
            prefixComponent = payload.prefixComponent,
            messageText = payload.messageText,
        )
        trackedMessages[id] = tracked

        val cached = TranslationService.getCached(payload.messageText, targetLang)
        if (cached != null && !cached.isSameLanguage) {
            val translated = createTranslatedComponent(id, cached, payload.prefixComponent)
            tracked.translatedComponent = translated
            return translated
        }

        if (cached == null && TranslationService.isFailed(payload.messageText, targetLang)) {
            val failed = createFailedComponent(id, payload.messageText, payload.prefixComponent)
            tracked.translatedComponent = failed
            return failed
        }

        if (cached == null) {
            triggerBackgroundChatTranslation(tracked, payload.messageText)
        }

        return component
    }

    private fun triggerBackgroundChatTranslation(tracked: TrackedChatMessage, messageText: String) {
        TranslationService.translateAsync(messageText) { result ->
            if (result != null && !result.isSameLanguage) {
                val translated = createTranslatedComponent(tracked.id, result, tracked.prefixComponent)
                tracked.translatedComponent = translated
                scheduleChatRefresh(tracked)
            } else if (result == null && TranslationService.isFailed(messageText)) {
                val failedComp = createFailedComponent(tracked.id, messageText, tracked.prefixComponent)
                tracked.translatedComponent = failedComp
                scheduleChatRefresh(tracked)
            }
        }
    }

    private fun shouldSkipMessage(text: String): Boolean {
        return text.length < MIN_TRANSLATABLE_LENGTH || text.startsWith("/") || text.startsWith("[T]")
    }

    fun createTranslatedComponent(
        id: Long,
        result: TranslationResult,
        prefixComponent: Component? = null,
    ): MutableComponent {
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

        val root = Component.empty().append(badge)
        if (prefixComponent != null) {
            root.append(prefixComponent)
        }
        root.append(Component.literal(result.translatedText))
        return root
    }

    fun createFailedComponent(
        id: Long,
        originalText: String,
        prefixComponent: Component? = null,
    ): MutableComponent {
        val badge = Component.literal("[T] ").withStyle { style ->
            style.withColor(ChatFormatting.RED)
                .withBold(true)
                .withStrikethrough(true)
                .withHoverEvent(
                    HoverEvent.ShowText(
                        Component.literal(
                            "Translation failed\n" +
                                "Original: $originalText\n" +
                                "Click to toggle original/translated text",
                        ),
                    ),
                )
                .withClickEvent(ClickEvent.RunCommand("$COMMAND_PREFIX $id"))
        }

        val root = Component.empty().append(badge)
        if (prefixComponent != null) {
            root.append(prefixComponent)
        }
        root.append(Component.literal(originalText))
        return root
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

        val index = messages.indexOfFirst { msg -> isMatchingMessage(msg, tracked) }

        if (index != -1) {
            val oldMsg = messages[index]
            val newMsg = GuiMessage(
                oldMsg.addedTime(),
                activeContent,
                oldMsg.signature(),
                oldMsg.source(),
                oldMsg.tag(),
            )
            copyChatHeadsData(oldMsg, newMsg)
            messages[index] = newMsg
            accessor.stellarRefreshTrimmedMessages()
        }
    }

    private fun isMatchingMessage(msg: GuiMessage, tracked: TrackedChatMessage): Boolean {
        val content = msg.content()
        val text = content.string
        val matchesComponent = content === tracked.originalComponent ||
            content == tracked.originalComponent ||
            content === tracked.translatedComponent ||
            content == tracked.translatedComponent
        val matchesText = text == tracked.plainText ||
            tracked.translatedComponent != null && text == tracked.translatedComponent?.string
        return matchesComponent || matchesText
    }

    internal fun copyChatHeadsData(source: Any, target: Any) {
        runCatching {
            val allSourceMethods = source.javaClass.methods + source.javaClass.declaredMethods
            val allTargetMethods = target.javaClass.methods + target.javaClass.declaredMethods
            val getMethod = allSourceMethods.firstOrNull {
                it.name.contains("chatheads") && it.name.contains("getHeadData")
            }
            val setMethod = allTargetMethods.firstOrNull {
                it.name.contains("chatheads") && it.name.contains("setHeadData")
            }
            if (getMethod != null && setMethod != null) {
                getMethod.isAccessible = true
                setMethod.isAccessible = true
                val data = getMethod.invoke(source)
                if (data != null) {
                    setMethod.invoke(target, data)
                }
            }
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

    fun clearCache() {
        trackedMessages.clear()
    }
}
