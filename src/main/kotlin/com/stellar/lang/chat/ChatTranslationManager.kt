@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongParameterList",
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LabeledExpression",
    "StringLiteralDuplication",
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
 * providing clickable flag toggle functionality.
 */
@Suppress("LargeClass")
object ChatTranslationManager {
    const val COMMAND_PREFIX: String = "/stellar_lang_chat_toggle"
    const val TRANSLATION_TIMEOUT_SECONDS: Long = 5L
    private const val MIN_TRANSLATABLE_LENGTH = 2
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
        """^(\s*(?:[\uFFFC\s]|\[[^\]]*\])*<[^>]+>\s*|^\s*(?:[\uFFFC\s]|\[[^\]]*\])*[\w\u00A7]{2,20}\s*[:»>]\s*)""",
    )

    private val TRANSLATABLE_FORMAT_PATTERN = Pattern.compile("""%(?:(\d+)\$)?([A-Za-z%]|$)""")

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
                    tracked.translationResult = result
                    tracked.translatedComponent = null
                    scheduleChatRefresh(tracked)
                }
            }
            return
        }
        for (tracked in trackedMessages.values) {
            if (tracked.messageText == result.originalText) {
                tracked.isPending = false
                tracked.translationResult = result
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
        var translationResult: TranslationResult? = null,
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

    internal fun flattenComponent(component: Component, parentStyle: Style = Style.EMPTY): List<Component> {
        val list = mutableListOf<Component>()
        fun walk(comp: Component, inheritedStyle: Style) {
            val currentStyle = comp.style.applyTo(inheritedStyle)
            val contents = comp.contents
            if (contents is TranslatableContents) {
                decomposeTranslatable(contents, currentStyle) { child, s ->
                    walk(child, s)
                }
            } else if (contents !is PlainTextContents || contents.text().isNotEmpty()) {
                val leaf = comp.plainCopy().setStyle(currentStyle)
                list.add(leaf)
            }
            for (sibling in comp.siblings) {
                walk(sibling, currentStyle)
            }
        }
        walk(component, parentStyle)
        return list
    }

    private fun getTranslatableFormat(contents: TranslatableContents): String {
        val language = net.minecraft.locale.Language.getInstance()
        val format = language.getOrDefault(contents.key)
        val fallback = contents.fallback
        return if (format == contents.key && fallback != null) fallback else format
    }

    private fun handleTranslatableMatch(
        matcher: java.util.regex.Matcher,
        args: Array<out Any?>,
        argIndex: Int,
        style: Style,
        walker: (Component, Style) -> Unit,
    ): Int {
        var nextArgIndex = argIndex
        when (matcher.group()) {
            "%%" -> walker(Component.literal("%").setStyle(style), style)
            "%n" -> walker(Component.literal("\n").setStyle(style), style)
            else -> {
                val pos = matcher.group(1)
                val index = pos?.let { it.toInt() - 1 } ?: nextArgIndex++
                if (index in args.indices) {
                    val arg = args[index]
                    if (arg is Component) {
                        walker(arg, style)
                    } else if (arg != null) {
                        walker(Component.literal(arg.toString()).setStyle(style), style)
                    }
                }
            }
        }
        return nextArgIndex
    }

    private fun decomposeTranslatable(
        contents: TranslatableContents,
        style: Style,
        walker: (Component, Style) -> Unit,
    ) {
        val format = getTranslatableFormat(contents)
        val matcher = TRANSLATABLE_FORMAT_PATTERN.matcher(format)
        var cursor = 0
        var argIndex = 0
        val args = contents.args

        while (matcher.find()) {
            if (matcher.start() > cursor) {
                walker(Component.literal(format.substring(cursor, matcher.start())).setStyle(style), style)
            }
            cursor = matcher.end()
            argIndex = handleTranslatableMatch(matcher, args, argIndex, style, walker)
        }
        if (cursor < format.length) {
            walker(Component.literal(format.substring(cursor)).setStyle(style), style)
        }
    }

    fun extractChatPayload(component: Component): ParsedChatPayload {
        val flatList = flattenComponent(component)
        val fullText = flatList.joinToString("") { it.string }
        val matcher = CHAT_PREFIX_REGEX.matcher(fullText)
        val hasPrefix = matcher.find() && matcher.start() == 0
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
                if (comp.contents is PlainTextContents) {
                    result.append(Component.literal(remainingText).setStyle(comp.style))
                } else {
                    result.append(comp)
                }
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
                    prefixComponents.add(comp)
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
        return if (comp.contents is PlainTextContents) {
            Component.literal(slicedText).setStyle(comp.style)
        } else {
            comp
        }
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

    @Suppress("ReturnCount", "LongMethod")
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
            tracked.translationResult = cached
            val translated = createTranslatedComponent(id, cached, payload.prefixComponent)
            tracked.translatedComponent = translated
            return translated
        }

        val quickLang = TranslationService.detectLanguageQuick(payload.messageText)
        if (quickLang != null && TranslationService.isSameLanguage(quickLang, targetLang)) {
            val sameResult = TranslationResult(
                originalText = payload.messageText,
                translatedText = payload.messageText,
                detectedLanguage = quickLang,
                targetLanguage = targetLang,
                isSameLanguage = true,
            )
            TranslationService.putCache(sameResult)
            return component
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
        val isBadgePrefix = text.startsWith("[T]") || text.startsWith("[...]") ||
            com.stellar.lang.badge.LanguageFlagHelper.isFlagPrefix(text)
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text).trim()
        return text.length < MIN_TRANSLATABLE_LENGTH ||
            text.startsWith("/") ||
            isBadgePrefix ||
            com.stellar.lang.plugin.LanguageDetectionHelper.isUniversalSlang(clean)
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
        hoverComponent: Component,
        badgeStyle: ChatBadgeStyle,
    ): MutableComponent {
        val root = Component.empty()
        if (prefixComponent != null) {
            root.append(prefixComponent)
        }
        val isHidden = TranslationBadgeHelper.isHidden()
        if (!isHidden && badgeStyle.badgeText.isNotEmpty()) {
            val badge = Component.literal(badgeStyle.badgeText).withStyle { style ->
                var s = style.withColor(badgeStyle.color)
                    .withHoverEvent(HoverEvent.ShowText(hoverComponent))
                    .withClickEvent(ClickEvent.RunCommand(buildToggleCommand(id)))
                if (badgeStyle.isBold) s = s.withBold(true)
                if (badgeStyle.isStrikethrough) s = s.withStrikethrough(true)
                s
            }
            root.append(badge)
        }
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(content)
        if (isHidden) {
            textComp.withStyle { style ->
                style.withHoverEvent(HoverEvent.ShowText(hoverComponent))
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
        val flagChar = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar(result.detectedLanguage)
        val flagEmoji = com.stellar.lang.badge.LanguageFlagHelper.getFlagEmoji(result.detectedLanguage)
        val langName = com.stellar.lang.badge.LanguageFlagHelper.getLanguageName(result.detectedLanguage)
        val hoverComponent = Component.empty()
            .append(Component.literal("$flagEmoji "))
            .append(Component.translatable("stellar_lang.chat.translated_from", langName))
            .append(Component.literal("\n"))
            .append(Component.translatable("stellar_lang.chat.original", result.originalText))
            .append(Component.literal("\n"))
            .append(Component.translatable("stellar_lang.chat.toggle_hover"))
        return buildChatComponent(
            id = id,
            content = result.translatedText,
            prefixComponent = prefixComponent,
            hoverComponent = hoverComponent,
            badgeStyle = ChatBadgeStyle("$flagChar ", ChatFormatting.WHITE),
        )
    }

    fun createFailedComponent(
        @Suppress("UnusedParameter") id: Long,
        originalText: String,
        prefixComponent: Component? = null,
    ): MutableComponent {
        val root = Component.empty()
        if (prefixComponent != null) {
            root.append(prefixComponent)
        }
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(originalText)
        root.append(textComp)
        return root
    }

    fun createTranslatingComponent(
        id: Long,
        originalText: String,
        prefixComponent: Component? = null,
    ): MutableComponent {
        val hoverComponent = Component.empty()
            .append(Component.translatable("stellar_lang.chat.translating"))
            .append(Component.literal("\n"))
            .append(Component.translatable("stellar_lang.chat.original", originalText))
            .append(Component.literal("\n"))
            .append(Component.translatable("stellar_lang.chat.toggle_hover"))
        return buildChatComponent(
            id = id,
            content = originalText,
            prefixComponent = prefixComponent,
            hoverComponent = hoverComponent,
            badgeStyle = ChatBadgeStyle("[...] ", ChatFormatting.GRAY),
        )
    }

    fun createOriginalComponent(
        id: Long,
        result: TranslationResult,
        prefixComponent: Component? = null,
    ): MutableComponent {
        val flagChar = com.stellar.lang.badge.LanguageFlagHelper.getFlagChar(result.detectedLanguage)
        val flagEmoji = com.stellar.lang.badge.LanguageFlagHelper.getFlagEmoji(result.detectedLanguage)
        val langName = com.stellar.lang.badge.LanguageFlagHelper.getLanguageName(result.detectedLanguage)
        val hoverComponent = Component.empty()
            .append(Component.literal("$flagEmoji "))
            .append(Component.translatable("stellar_lang.chat.translated_from", langName))
            .append(Component.literal("\n"))
            .append(Component.translatable("stellar_lang.chat.original", result.originalText))
            .append(Component.literal("\n"))
            .append(Component.translatable("stellar_lang.chat.toggle_hover"))
        return buildChatComponent(
            id = id,
            content = result.originalText,
            prefixComponent = prefixComponent,
            hoverComponent = hoverComponent,
            badgeStyle = ChatBadgeStyle("$flagChar ", ChatFormatting.GRAY, isStrikethrough = true),
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

        var activeContent = if (tracked.isShowingOriginal) {
            tracked.translationResult?.let { result ->
                createOriginalComponent(tracked.id, result, tracked.prefixComponent)
            } ?: tracked.originalComponent
        } else {
            tracked.translatedComponent ?: tracked.originalComponent
        }

        val index = messages.indexOfFirst { msg -> isMatchingMessage(msg, tracked) }

        if (index != -1) {
            val oldMsg = messages[index]
            val oldLeaves = flattenComponent(oldMsg.content())
            val firstOldLeaf = oldLeaves.firstOrNull()
            if (firstOldLeaf != null && firstOldLeaf.contents !is PlainTextContents) {
                val activeLeaves = flattenComponent(activeContent)
                val firstActiveLeaf = activeLeaves.firstOrNull()
                if (firstActiveLeaf == null || firstActiveLeaf.contents is PlainTextContents) {
                    activeContent = Component.empty().append(firstOldLeaf).append(activeContent)
                }
            }

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

        val cleanText = text.replaceFirst(Regex("""^\s*(?:\uFFFC|\[[^\]]*\])\s*"""), "")
        val matchesStrippedText = cleanText == tracked.plainText ||
            tracked.translatedComponent != null && cleanText == tracked.translatedComponent?.string

        return matchesCommand || matchesText || matchesStrippedText
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
                    return
                }
            }
            val sourceField = (source.javaClass.fields + source.javaClass.declaredFields)
                .firstOrNull { it.name.contains("chatheads") && it.name.contains("headData") }
            val targetField = (target.javaClass.fields + target.javaClass.declaredFields)
                .firstOrNull { it.name.contains("chatheads") && it.name.contains("headData") }
            if (sourceField != null && targetField != null) {
                sourceField.isAccessible = true
                targetField.isAccessible = true
                val data = sourceField.get(source)
                if (data != null) {
                    targetField.set(target, data)
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
