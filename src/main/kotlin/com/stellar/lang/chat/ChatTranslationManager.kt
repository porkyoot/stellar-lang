@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongParameterList",
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LabeledExpression",
)

package com.stellar.lang.chat

import com.stellar.lang.badge.TranslationBadgeHelper
import com.stellar.lang.mixin.ChatComponentAccessor
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Manages translation of chat messages, compatible with Chat Heads and player prefixes,
 * providing clickable [T] toggle functionality.
 */
@Suppress("LargeClass")
object ChatTranslationManager {
    const val COMMAND_PREFIX: String = "/stellar_lang_chat_toggle"
    const val TRANSLATION_TIMEOUT_SECONDS: Long = 5L
    private const val MIN_TRANSLATABLE_LENGTH = 2
    private const val TOGGLE_HOVER_TEXT = "Click to toggle original/translated text"
    private val idGenerator = AtomicLong(1000L)
    internal val trackedMessages = ConcurrentHashMap<Long, TrackedChatMessage>()

    private val defaultTimeoutExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "StellarLang-ChatTimeout").apply { isDaemon = true }
        }

    @Volatile
    var timeoutExecutor: ScheduledExecutorService = defaultTimeoutExecutor

    // Matches standard player chat prefixes, with or without Chat Heads player sprites:
    // e.g. "<[Porkyoot head]Porkyoot> ", "<Dev1lroot> ", "[VIP] <Dev1lroot> ", "Dev1lroot: ", "Dev1lroot » "
    private val CHAT_PREFIX_REGEX = Pattern.compile(
        """^(\s*(?:\[[^\]]*\]\s*)*<[^>]+>\s*|^\s*\[[^\]]+\]\s+<\w+>\s*|^\s*[\w\u00A7]{2,20}\s*[:»>]\s*)""",
    )

    private val DEATH_PATTERN = Pattern.compile(
        """\b(?:was slain by|was shot by|was blown up by|was killed by|was squashed by|was pricked to death|""" +
            """was impaled by|was roasted in dragon's breath|was doomed to fall|fell from a high place|""" +
            """fell off a ladder|fell off scaffolding|fell while climbing|hit the ground too hard|drowned|""" +
            """experienced kinetic energy|burned to death|went up in flames|tried to swim in lava|""" +
            """discovered the floor was lava|starved to death|suffocated in a wall|withered away|died)\b""",
        Pattern.CASE_INSENSITIVE,
    )

    private val ADVANCEMENT_PATTERN = Pattern.compile(
        """\bhas (?:made the advancement|completed the challenge|reached the goal)\b""",
        Pattern.CASE_INSENSITIVE,
    )

    private val JOIN_LEAVE_PATTERN = Pattern.compile(
        """\b(?:joined the game|left the game)\b""",
        Pattern.CASE_INSENSITIVE,
    )

    private val ADVANCEMENT_KEY_PREFIXES = listOf(
        "chat.type.advancement.",
        "advancement.",
        "advancements.",
    )

    private val JOIN_LEAVE_KEY_PREFIXES = listOf(
        "multiplayer.player.joined",
        "multiplayer.player.left",
    )

    private val MOTD_PHRASES = listOf(
        "motd",
        "message of the day",
        "server news",
        "welcome to",
        "bienvenue sur",
        "bienvenue à",
        "willkommen auf",
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
        if (result.isSameLanguage) {
            for (tracked in trackedMessages.values) {
                if (tracked.messageText == result.originalText && tracked.isPending) {
                    tracked.isPending = false
                    tracked.translatedComponent = null
                    scheduleChatRefresh(tracked)
                }
            }
            return
        }
        for (tracked in trackedMessages.values) {
            if (tracked.messageText == result.originalText) {
                tracked.isPending = false
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
        @Volatile var isPending: Boolean = false,
        @Volatile var currentAttempt: Long = 0L,
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
        val config = TranslationService.getConfig()
        val flatList = component.toFlatList()
        val fullText = flatList.joinToString("") { it.string }
        val matcher = CHAT_PREFIX_REGEX.matcher(fullText)
        val shouldExtractPrefix = !config.translatePlayerNames.value()
        val hasPrefix = shouldExtractPrefix && matcher.find() && matcher.start() == 0
        if (!hasPrefix) {
            val formatted = com.stellar.lang.format.FormattingTagHelper.componentToFormattedText(component).trim()
            return ParsedChatPayload(null, formatted.ifEmpty { fullText.trim() })
        }

        val prefixText = matcher.group(1)
        val prefixLength = prefixText.length
        val prefixComp = buildPrefixComponent(flatList, prefixLength)
        val messageComp = buildRemainingComponent(flatList, prefixLength)
        val messageText = if (messageComp != null) {
            com.stellar.lang.format.FormattingTagHelper.componentToFormattedText(messageComp).trim()
        } else {
            fullText.substring(prefixLength).trim()
        }

        if (com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(messageText).isBlank()) {
            return ParsedChatPayload(null, fullText.trim())
        }

        return ParsedChatPayload(prefixComp, messageText)
    }

    private fun buildRemainingComponent(flatList: List<Component>, prefixLength: Int): Component? {
        val result = Component.empty()
        var currentLen = 0

        for (comp in flatList) {
            val str = comp.string
            val compLen = str.length
            if (currentLen + compLen <= prefixLength) {
                currentLen += compLen
                continue
            }

            if (currentLen < prefixLength) {
                val skip = prefixLength - currentLen
                val remainingText = str.substring(skip)
                result.append(Component.literal(remainingText).setStyle(comp.style))
                currentLen += compLen
            } else {
                result.append(comp)
            }
        }

        return if (result.siblings.isEmpty() && result.string.isEmpty()) null else result
    }

    private fun buildPrefixComponent(flatList: List<Component>, prefixLength: Int): Component? {
        val prefixComponents = mutableListOf<Component>()
        var currentLen = 0

        for (comp in flatList) {
            if (currentLen >= prefixLength) break

            val str = comp.string
            val compLen = str.length
            if (compLen > 0) {
                if (currentLen + compLen <= prefixLength) {
                    val flatComp = if (comp.siblings.isEmpty() && comp.contents is PlainTextContents) {
                        comp
                    } else {
                        Component.literal(str).setStyle(comp.style)
                    }
                    prefixComponents.add(flatComp)
                    currentLen += compLen
                } else {
                    val needed = prefixLength - currentLen
                    prefixComponents.add(sliceComponent(comp, needed))
                    currentLen += needed
                }
            }
        }

        if (prefixComponents.isEmpty()) return null
        val res = Component.empty()
        prefixComponents.forEach { res.append(it) }
        return res
    }

    private fun sliceComponent(comp: Component, needed: Int): Component {
        val str = comp.string
        val slicedText = if (needed <= str.length) str.substring(0, needed) else str
        return Component.literal(slicedText).setStyle(comp.style)
    }

    @Suppress("ReturnCount")
    fun processIncomingMessage(
        component: Component,
        source: GuiMessageSource? = null,
    ): Component {
        val config = TranslationService.getConfig()
        if (!config.enabled.value() || !config.translateChat.value()) {
            return component
        }

        val plainText = component.string.trim()
        val payload = extractChatPayload(component)
        if (shouldSkipMessage(component, payload, plainText, source)) {
            return component
        }

        val targetLang = TranslationService.getTargetLanguage()
        val cached = TranslationService.getCached(payload.messageText, targetLang)
        if (cached != null) {
            if (cached.isSameLanguage) {
                return component
            }
            return resolveAndTranslate(component, payload, targetLang)
        }

        val quickLang = com.stellar.lang.plugin.LanguageDetectionHelper.detectQuick(payload.messageText)
        if (quickLang != null && TranslationService.isSameLanguage(quickLang, targetLang)) {
            TranslationService.putCache(
                TranslationResult(
                    originalText = payload.messageText,
                    translatedText = payload.messageText,
                    detectedLanguage = quickLang,
                    targetLanguage = targetLang,
                    isSameLanguage = true,
                ),
            )
            return component
        }

        return resolveAndTranslate(component, payload, targetLang)
    }

    @Suppress("ReturnCount")
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
        if (cached != null) {
            if (cached.isSameLanguage) {
                return component
            }
            val translated = createTranslatedComponent(id, cached, payload.prefixComponent)
            tracked.translatedComponent = translated
            return translated
        }

        if (TranslationService.isFailed(payload.messageText, targetLang)) {
            val failed = createFailedComponent(id, payload.messageText, payload.prefixComponent)
            tracked.translatedComponent = failed
            return failed
        }

        val translating = createTranslatingComponent(id, payload.messageText, payload.prefixComponent)
        tracked.translatedComponent = translating
        triggerBackgroundChatTranslation(tracked, payload.messageText)
        return translating
    }

    @JvmOverloads
    fun triggerBackgroundChatTranslation(
        tracked: TrackedChatMessage,
        messageText: String,
        forceRetry: Boolean = false,
    ) {
        val attempt = ++tracked.currentAttempt
        tracked.isPending = true

        timeoutExecutor.schedule({
            handleTimeout(tracked, attempt)
        }, TRANSLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        TranslationService.translateAsync(messageText, forceRetry = forceRetry) { result ->
            if (tracked.currentAttempt != attempt || !tracked.isPending) return@translateAsync
            tracked.isPending = false

            if (result != null) {
                if (result.isSameLanguage) {
                    tracked.translatedComponent = null
                } else {
                    val translated = createTranslatedComponent(tracked.id, result, tracked.prefixComponent)
                    tracked.translatedComponent = translated
                }
                scheduleChatRefresh(tracked)
            } else {
                markFailedAndRefresh(tracked)
            }
        }
    }

    internal fun handleTimeout(tracked: TrackedChatMessage, attempt: Long) {
        if (tracked.currentAttempt == attempt && tracked.isPending) {
            tracked.isPending = false
            markFailedAndRefresh(tracked)
        }
    }

    private fun markFailedAndRefresh(tracked: TrackedChatMessage) {
        TranslationService.markFailed(tracked.messageText)
        val failedComp = createFailedComponent(tracked.id, tracked.messageText, tracked.prefixComponent)
        tracked.translatedComponent = failedComp
        scheduleChatRefresh(tracked)
    }

    private fun isGameEventMessage(component: Component, messageText: String): Boolean {
        return isDeathMessage(component, messageText) ||
            isAdvancementMessage(component, messageText) ||
            isJoinLeaveMessage(component, messageText) ||
            isAdminOrCommandMessage(component)
    }

    @Suppress("ReturnCount")
    fun shouldSkipMessage(
        component: Component,
        payload: ParsedChatPayload,
        plainText: String,
        source: GuiMessageSource? = null,
    ): Boolean {
        if (shouldSkipMessage(plainText) || shouldSkipMessage(payload.messageText)) {
            return true
        }

        if (source == GuiMessageSource.SYSTEM_CLIENT) {
            return true
        }

        val isSystemServer = source == GuiMessageSource.SYSTEM_SERVER || payload.prefixComponent == null
        if (isSystemServer) {
            if (isMotdMessage(component, payload.messageText)) {
                return false
            }
            if (isGameEventMessage(component, payload.messageText)) {
                return true
            }
        }

        return false
    }

    internal fun shouldSkipMessage(text: String): Boolean {
        val isBadgePrefix = text.startsWith("[T]") || text.startsWith("[...]")
        return text.length < MIN_TRANSLATABLE_LENGTH || text.startsWith("/") || isBadgePrefix
    }

    fun hasTranslatableKey(component: Component, predicate: (String) -> Boolean): Boolean {
        val contents = component.contents
        val inKey = contents is TranslatableContents && predicate(contents.key)
        val inArgs = contents is TranslatableContents && contents.args.any {
            it is Component && hasTranslatableKey(it, predicate)
        }
        return inKey || inArgs || component.siblings.any { hasTranslatableKey(it, predicate) }
    }

    fun isDeathMessage(component: Component, messageText: String): Boolean {
        if (hasTranslatableKey(component) { it.startsWith("death.") }) {
            return true
        }
        return DEATH_PATTERN.matcher(messageText).find()
    }

    fun isAdvancementMessage(component: Component, messageText: String): Boolean {
        if (hasTranslatableKey(component) { key -> ADVANCEMENT_KEY_PREFIXES.any { key.startsWith(it) } }) {
            return true
        }
        return ADVANCEMENT_PATTERN.matcher(messageText).find()
    }

    fun isJoinLeaveMessage(component: Component, messageText: String): Boolean {
        if (hasTranslatableKey(component) { key -> JOIN_LEAVE_KEY_PREFIXES.any { key.startsWith(it) } }) {
            return true
        }
        return JOIN_LEAVE_PATTERN.matcher(messageText).find()
    }

    fun isAdminOrCommandMessage(component: Component): Boolean {
        return hasTranslatableKey(component) {
            it.startsWith("commands.") ||
                it.startsWith("chat.type.admin") ||
                it.startsWith("gameMode.")
        }
    }

    fun isMotdMessage(component: Component, messageText: String): Boolean {
        val fullLower = component.string.lowercase()
        val textLower = messageText.lowercase()
        val hasPhrase = MOTD_PHRASES.any { fullLower.contains(it) || textLower.contains(it) }
        val hasKey = hasTranslatableKey(component) { it.contains("motd") || it.contains("welcome") }
        return hasPhrase || hasKey
    }

    private data class ChatBadgeStyle(
        val badgeText: String,
        val color: ChatFormatting,
        val isBold: Boolean = false,
        val isStrikethrough: Boolean = false,
    )

    private fun buildChatComponent(
        id: Long,
        content: String,
        prefixComponent: Component?,
        hoverText: String,
        badgeStyle: ChatBadgeStyle,
    ): MutableComponent {
        val root = Component.empty()
        val isHidden = TranslationBadgeHelper.isHidden()
        if (!isHidden) {
            val badge = Component.literal(badgeStyle.badgeText).withStyle { style ->
                var s = style.withColor(badgeStyle.color)
                    .withHoverEvent(HoverEvent.ShowText(Component.literal(hoverText)))
                    .withClickEvent(ClickEvent.RunCommand(buildToggleCommand(id)))
                if (badgeStyle.isBold) s = s.withBold(true)
                if (badgeStyle.isStrikethrough) s = s.withStrikethrough(true)
                s
            }
            root.append(badge)
        }
        if (prefixComponent != null) {
            root.append(prefixComponent)
        }
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(content)
        if (isHidden) {
            textComp.withStyle { style ->
                style.withHoverEvent(HoverEvent.ShowText(Component.literal(hoverText)))
                    .withClickEvent(ClickEvent.RunCommand(buildToggleCommand(id)))
            }
        }
        root.append(textComp)
        return root
    }

    fun createTranslatedComponent(
        id: Long,
        result: TranslationResult,
        prefixComponent: Component? = null,
    ): MutableComponent {
        if (result.isSameLanguage) {
            val root = Component.empty()
            if (prefixComponent != null) {
                root.append(prefixComponent)
            }
            val contentComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(result.originalText)
            root.append(contentComp)
            return root
        }
        val hoverText = "Translated: [${result.detectedLanguage} -> ${result.targetLanguage}]\n" +
            "Original: ${result.originalText}\n$TOGGLE_HOVER_TEXT"
        return buildChatComponent(
            id = id,
            content = result.translatedText,
            prefixComponent = prefixComponent,
            hoverText = hoverText,
            badgeStyle = ChatBadgeStyle("[T] ", ChatFormatting.AQUA, isBold = true),
        )
    }

    fun createFailedComponent(
        id: Long,
        originalText: String,
        prefixComponent: Component? = null,
    ): MutableComponent {
        val hoverText = "Translation failed\nOriginal: $originalText\n$TOGGLE_HOVER_TEXT"
        return buildChatComponent(
            id = id,
            content = originalText,
            prefixComponent = prefixComponent,
            hoverText = hoverText,
            badgeStyle = ChatBadgeStyle(
                badgeText = "[T] ",
                color = ChatFormatting.RED,
                isBold = true,
                isStrikethrough = true,
            ),
        )
    }

    fun createTranslatingComponent(
        id: Long,
        originalText: String,
        prefixComponent: Component? = null,
    ): MutableComponent {
        val hoverText = "Translating...\nOriginal: $originalText\n$TOGGLE_HOVER_TEXT"
        return buildChatComponent(
            id = id,
            content = originalText,
            prefixComponent = prefixComponent,
            hoverText = hoverText,
            badgeStyle = ChatBadgeStyle("[...] ", ChatFormatting.GRAY),
        )
    }

    private fun buildToggleCommand(id: Long): String = "$COMMAND_PREFIX $id"

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

    internal fun isMatchingMessage(msg: GuiMessage, tracked: TrackedChatMessage): Boolean {
        val content = msg.content()
        if (matchesComponentExact(content, tracked)) return true

        val targetCmd = "$COMMAND_PREFIX ${tracked.id}"
        var matchesCommand = false
        content.visit({ style, _ ->
            val event = style.clickEvent
            if (event is ClickEvent.RunCommand && event.command() == targetCmd) {
                matchesCommand = true
                Optional.of(true)
            } else {
                Optional.empty()
            }
        }, Style.EMPTY)

        val text = content.string
        val matchesText = text == tracked.plainText ||
            tracked.translatedComponent != null && text == tracked.translatedComponent?.string

        return matchesCommand || matchesText
    }

    private fun matchesComponentExact(content: Component, tracked: TrackedChatMessage): Boolean {
        val translated = tracked.translatedComponent
        return content === tracked.originalComponent ||
            content == tracked.originalComponent ||
            translated != null && (content === translated || content == translated)
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

    @Suppress("ReturnCount")
    fun handleCommandClick(command: String): Boolean {
        if (!command.startsWith(COMMAND_PREFIX)) return false

        val idStr = command.removePrefix(COMMAND_PREFIX).trim()
        val id = idStr.toLongOrNull() ?: return false
        val tracked = trackedMessages[id] ?: return false

        val targetLang = TranslationService.getTargetLanguage()
        if (TranslationService.isFailed(tracked.messageText, targetLang)) {
            tracked.isShowingOriginal = false
            val translatingComp = createTranslatingComponent(id, tracked.messageText, tracked.prefixComponent)
            tracked.translatedComponent = translatingComp
            updateChatDisplay(tracked)
            triggerBackgroundChatTranslation(tracked, tracked.messageText, forceRetry = true)
            return true
        }

        tracked.isShowingOriginal = !tracked.isShowingOriginal
        updateChatDisplay(tracked)
        return true
    }

    fun clearCache() {
        trackedMessages.clear()
        com.stellar.lang.player.PlayerNameHelper.clearProviders()
    }
}
