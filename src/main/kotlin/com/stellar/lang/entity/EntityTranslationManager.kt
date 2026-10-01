package com.stellar.lang.entity

import com.stellar.lang.input.StellarLangInputHandler
import com.stellar.lang.player.PlayerNameHelper
import com.stellar.lang.service.TranslationResult
import com.stellar.lang.service.TranslationService
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.Entity
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages translation of entity nametags and display names with text-based caching and lifecycle triggers.
 */
object EntityTranslationManager {
    private const val MIN_TRANSLATABLE_LENGTH = 2

    // Cache is strictly for TEXT: "$targetLang::$plainText" -> Component
    internal val textComponentCache = ConcurrentHashMap<String, Component>()
    internal val failedEntities = ConcurrentHashMap.newKeySet<String>()
    private const val ACCESS_RETRY_COOLDOWN_MS = 5_000L
    internal val lastEntityRetryTimes = ConcurrentHashMap<String, Long>()

    init {
        TranslationService.addSuccessListener { result ->
            onTranslationSuccess(result)
        }
    }

    internal fun onTranslationSuccess(result: TranslationResult) {
        val targetLang = result.targetLanguage
        val textKey = "$targetLang::${result.originalText}"
        failedEntities.remove(textKey)
        if (result.isSameLanguage) {
            textComponentCache.remove(textKey)
        } else {
            textComponentCache[textKey] = createFormattedName(result.translatedText)
        }
    }

    fun onEntityLoaded(entity: Entity) {
        val customName = entity.customName ?: return
        val config = TranslationService.getConfig()
        if (!config.translatePlayerNames.value() && PlayerNameHelper.isPlayer(entity, customName)) return
        onEntityNameChanged(customName)
    }

    private fun buildTextKey(targetLang: String, plainText: String): String = "$targetLang::$plainText"

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun refreshEntity(entity: Entity): Boolean {
        val config = TranslationService.getConfig()
        val nameComp = entity.customName ?: runCatching { entity.name }.getOrNull() ?: return false
        if (!config.translatePlayerNames.value() &&
            PlayerNameHelper.isPlayer(entity, nameComp)
        ) {
            return false
        }
        val plainText = getTranslatableText(nameComp, entity) ?: return false
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = buildTextKey(targetLang, plainText)
        textComponentCache.remove(textKey)
        failedEntities.remove(textKey)
        lastEntityRetryTimes.remove(textKey)
        TranslationService.evict(plainText, targetLang)
        TranslationService.translateAsync(plainText, forceRetry = true) { result ->
            if (result != null && !result.isSameLanguage) {
                failedEntities.remove(textKey)
                textComponentCache[textKey] = createFormattedName(result.translatedText)
            } else if (result != null && result.isSameLanguage) {
                failedEntities.remove(textKey)
                textComponentCache.remove(textKey)
            } else if (result == null) {
                failedEntities.add(textKey)
                textComponentCache[textKey] = createFailedName(plainText)
            }
        }
        return true
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
    fun onEntityNameChanged(name: Component) {
        val config = TranslationService.getConfig()
        if (!config.translatePlayerNames.value() && PlayerNameHelper.isPlayer(null, name)) {
            return
        }
        val plainText = getTranslatableText(name) ?: return
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = buildTextKey(targetLang, plainText)

        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (!cachedResult.isSameLanguage && !textComponentCache.containsKey(textKey)) {
                failedEntities.remove(textKey)
                textComponentCache[textKey] =
                    createFormattedName(cachedResult.translatedText, cachedResult.detectedLanguage)
            }
            return
        }

        TranslationService.translateAsync(plainText) { result ->
            if (result != null && !result.isSameLanguage) {
                failedEntities.remove(textKey)
                textComponentCache[textKey] =
                    createFormattedName(result.translatedText, result.detectedLanguage)
            } else if (result != null && result.isSameLanguage) {
                failedEntities.remove(textKey)
                textComponentCache.remove(textKey)
            } else if (result == null && TranslationService.isFailed(plainText, targetLang)) {
                failedEntities.add(textKey)
                textComponentCache[textKey] = createFailedName(plainText)
            }
        }
    }

    private fun getValidCachedEntity(textKey: String, plainText: String, targetLang: String): Component? {
        val cached = textComponentCache[textKey] ?: return null
        if (!failedEntities.contains(textKey)) return cached
        if (TranslationService.isFailed(plainText, targetLang)) return cached
        failedEntities.remove(textKey)
        textComponentCache.remove(textKey)
        return null
    }

    @Suppress("ReturnCount")
    fun translateEntityName(entity: Entity? = null, original: Component): Component {
        val config = TranslationService.getConfig()
        if (!config.translatePlayerNames.value() && PlayerNameHelper.isPlayer(entity, original)) {
            return original
        }
        val plainText = getTranslatableText(original, entity) ?: return original
        val targetLang = TranslationService.getTargetLanguage()
        val textKey = buildTextKey(targetLang, plainText)

        if (TranslationService.isInFlight(plainText, targetLang)) {
            return createTranslatingName(plainText)
        }

        val cached = getValidCachedEntity(textKey, plainText, targetLang)
        if (cached != null) return cached

        return resolveEntityTranslation(plainText, targetLang, textKey, original)
    }

    @Suppress("ReturnCount", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    private fun resolveEntityTranslation(
        plainText: String,
        targetLang: String,
        textKey: String,
        original: Component,
    ): Component {
        val cachedResult = TranslationService.getCached(plainText, targetLang)
        if (cachedResult != null) {
            if (cachedResult.isSameLanguage) return original
            val comp = createFormattedName(cachedResult.translatedText, cachedResult.detectedLanguage)
            failedEntities.remove(textKey)
            textComponentCache[textKey] = comp
            return comp
        }

        val isFailed = failedEntities.contains(textKey) || TranslationService.isFailed(plainText, targetLang)
        if (isFailed && !TranslationService.isInFlight(plainText, targetLang)) {
            val now = System.currentTimeMillis()
            val lastRetry = lastEntityRetryTimes[textKey] ?: 0L
            if (now - lastRetry >= ACCESS_RETRY_COOLDOWN_MS) {
                lastEntityRetryTimes[textKey] = now
                TranslationService.translateAsync(plainText, forceRetry = true) { result ->
                    if (result != null && !result.isSameLanguage) {
                        failedEntities.remove(textKey)
                        textComponentCache[textKey] =
                            createFormattedName(result.translatedText, result.detectedLanguage)
                    } else if (result != null && result.isSameLanguage) {
                        failedEntities.remove(textKey)
                        textComponentCache.remove(textKey)
                    } else if (result == null) {
                        failedEntities.add(textKey)
                        textComponentCache[textKey] = createFailedName(plainText)
                    }
                }
                return createTranslatingName(plainText)
            }
            return createFailedName(plainText)
        }

        onEntityNameChanged(original)
        return original
    }

    fun clearCache() {
        textComponentCache.clear()
        failedEntities.clear()
        lastEntityRetryTimes.clear()
        PlayerNameHelper.clearProviders()
    }

    private fun getTranslatableText(original: Component): String? = getTranslatableText(original, null)

    @Suppress("CyclomaticComplexMethod")
    private fun getTranslatableText(original: Component, entity: Entity?): String? {
        val config = TranslationService.getConfig()
        val disabled = !config.enabled.value() ||
            !config.translateEntities.value() ||
            !config.translatePlayerNames.value() && PlayerNameHelper.isPlayer(entity, original) ||
            StellarLangInputHandler.isShowingOriginal()
        if (disabled) return null

        val text = com.stellar.lang.format.FormattingTagHelper.componentToFormattedText(original).trim()
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text)
        val isBadgePrefix = clean.startsWith("[T]") || clean.startsWith("[...]") ||
            com.stellar.lang.badge.LanguageFlagHelper.isFlagPrefix(clean)
        return if (clean.length < MIN_TRANSLATABLE_LENGTH || isBadgePrefix) null else text
    }

    private fun createFormattedName(translatedText: String, lang: String? = null): MutableComponent {
        val badge = com.stellar.lang.badge.TranslationBadgeHelper.createBadge(
            lang = lang,
            failed = false,
            trailingSpace = true,
        )
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(translatedText)
        return Component.empty().append(badge).append(textComp)
    }

    private fun createTranslatingName(originalText: String): MutableComponent {
        val badge = com.stellar.lang.badge.TranslationBadgeHelper.createTranslatingBadge(trailingSpace = true)
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(originalText)
        return Component.empty().append(badge).append(textComp)
    }

    private fun createFailedName(originalText: String): MutableComponent {
        val badge = com.stellar.lang.badge.TranslationBadgeHelper.createBadge(failed = true, trailingSpace = true)
        val textComp = com.stellar.lang.format.FormattingTagHelper.formattedTextToComponent(originalText)
        return Component.empty().append(badge).append(textComp)
    }
}
