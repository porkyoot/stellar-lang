package com.stellar.lang.error

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles user-facing notification for translation API failures.
 * Dispatches an in-game toast exactly once per error kind and provider per session,
 * and logs clear diagnostic and recovery steps.
 */
object TranslationErrorNotifier {
    private val logger: Logger = LoggerFactory.getLogger("StellarLang-ErrorNotifier")
    private val notifiedKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    internal var toastDispatcher: ((title: Component, description: Component) -> Unit)? = null

    fun notifyErrorOnce(error: TranslationErrorInfo) {
        val key = "${error.providerId.lowercase()}:${error.kind.name}"
        if (!notifiedKeys.add(key)) {
            // Already reported once for this provider and error category
            return
        }

        // 1. Log actionable diagnostic instructions
        logger.error(
            "[StellarLang] {} encountered {}: {}. How to fix: {}{}",
            TranslationErrorClassifier.formatProviderName(error.providerId),
            error.kind,
            error.message,
            error.resolution,
            error.technicalDetail?.let { " (Details: $it)" } ?: "",
        )

        // 2. Check if toast notifications are enabled in config
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        if (config != null && !config.showErrorToasts.value()) {
            return
        }

        // 3. Format and dispatch toast
        val titleComp = Component.literal(error.title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
        val descComp = Component.literal("${error.message} ${error.resolution}").withStyle(ChatFormatting.WHITE)

        val custom = toastDispatcher
        if (custom != null) {
            custom(titleComp, descComp)
            return
        }

        StellarToastHelper.showToast(titleComp, descComp)
    }

    fun hasNotified(providerId: String, kind: TranslationErrorKind): Boolean {
        return notifiedKeys.contains("${providerId.lowercase()}:${kind.name}")
    }

    fun reset() {
        notifiedKeys.clear()
    }
}
