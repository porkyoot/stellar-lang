@file:Suppress(
    "ReturnCount",
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
)

package com.stellar.lang.player

import com.stellar.lang.format.FormattingTagHelper
import com.stellar.lang.service.TranslationService
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Helper to identify player entities, check player names, and protect player names from translation.
 */
object PlayerNameHelper {
    private const val MIN_NAME_LENGTH = 2

    @Volatile
    var customPlayerNamesProvider: (() -> Set<String>)? = null

    @Volatile
    var playerNamePredicate: ((String) -> Boolean)? = null

    @Volatile
    var playerEntityPredicate: ((Entity) -> Boolean)? = null

    @Volatile
    var localPlayerNameProvider: (() -> String?)? = null

    @Volatile
    var onlinePlayersProvider: (() -> Collection<net.minecraft.client.multiplayer.PlayerInfo>?)? = null

    @Volatile
    var playerProvider: (() -> Player?)? = null

    @Volatile
    var connectionProvider: (() -> net.minecraft.client.multiplayer.ClientPacketListener?)? = null

    fun shouldTranslatePlayerNames(): Boolean =
        TranslationService.getConfig().translatePlayerNames.value()

    fun isPlayer(entity: Entity?, component: Component? = null): Boolean {
        if (entity != null) {
            playerEntityPredicate?.invoke(entity)?.let { return it }
            if (entity is Player) return true
        }
        if (component != null) {
            val text = FormattingTagHelper.stripFormattingAndTags(component.string).trim()
            if (text.isNotEmpty() && isPlayerName(text)) {
                return true
            }
        }
        return false
    }

    fun getLocalPlayerName(): String? {
        localPlayerNameProvider?.invoke()?.let { return it }
        val player = playerProvider?.invoke() ?: runCatching { Minecraft.getInstance().player }.getOrNull()
        return player?.gameProfile?.name
    }

    fun getOnlinePlayers(): Collection<net.minecraft.client.multiplayer.PlayerInfo>? {
        onlinePlayersProvider?.invoke()?.let { return it }
        val connection = connectionProvider?.invoke()
            ?: runCatching { Minecraft.getInstance().connection }.getOrNull()
        return runCatching { connection?.onlinePlayers }.getOrNull()
    }

    fun isPlayerName(name: String): Boolean {
        val clean = name.trim().removePrefix("<").removeSuffix(">").trim()
        if (clean.length < MIN_NAME_LENGTH) return false

        playerNamePredicate?.invoke(clean)?.let { return it }

        val custom = customPlayerNamesProvider?.invoke()
        if (custom != null && custom.any { it.equals(clean, ignoreCase = true) }) {
            return true
        }

        val localName = getLocalPlayerName()
        if (localName != null && localName.equals(clean, ignoreCase = true)) {
            return true
        }

        val online = getOnlinePlayers()
        if (online != null) {
            for (playerInfo in online) {
                if (playerInfo.profile.name.equals(clean, ignoreCase = true)) return true
                val display = playerInfo.tabListDisplayName?.string?.trim()
                if (display != null && display.equals(clean, ignoreCase = true)) return true
            }
        }

        return false
    }

    fun getKnownPlayerNames(): Set<String> {
        val names = mutableSetOf<String>()
        customPlayerNamesProvider?.invoke()?.let { names.addAll(it) }
        getLocalPlayerName()?.let { if (it.isNotBlank()) names.add(it) }
        getOnlinePlayers()?.forEach { info ->
            if (info.profile.name.isNotBlank()) names.add(info.profile.name)
            info.tabListDisplayName?.string?.trim()?.let {
                if (it.isNotBlank()) names.add(it)
            }
        }
        return names
    }

    fun protectPlayerNames(text: String): String {
        if (shouldTranslatePlayerNames()) return text
        val knownNames = getKnownPlayerNames().filter { it.length >= MIN_NAME_LENGTH }
        if (knownNames.isEmpty()) return text

        var result = text
        for (name in knownNames) {
            val pattern = Pattern.compile(
                "(?<!<ut>)\\b(" + Pattern.quote(name) + ")\\b(?!</ut>)",
                Pattern.CASE_INSENSITIVE,
            )
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val matched = matcher.group(1)
                matcher.appendReplacement(buffer, Matcher.quoteReplacement("<ut>$matched</ut>"))
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }
        return result
    }

    fun clearProviders() {
        customPlayerNamesProvider = null
        playerNamePredicate = null
        playerEntityPredicate = null
        localPlayerNameProvider = null
        onlinePlayersProvider = null
        playerProvider = null
        connectionProvider = null
    }
}
