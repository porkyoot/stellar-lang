package com.stellar.lang.config

import com.stellar.core.config.ConfigManager
import com.stellar.core.input.Key
import com.stellar.lang.StellarLangMod
import com.stellar.lang.service.TranslationService
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Factory for creating the Cloth Config GUI screen bound to StellarLangConfig.
 */
object LangClothConfigScreen {
    private val testToastId by lazy {
        runCatching { net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId() }.getOrNull()
    }
    private val isTesting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastTestTimeMs = 0L
    private const val TEST_DEBOUNCE_MS = 1000L

    fun create(parent: Screen?): Screen {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)

        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.literal("Stellar Lang Config"))

        builder.setSavingRunnable {
            config.save()
        }

        val entryBuilder = builder.entryBuilder()
        buildGeneralCategory(builder, entryBuilder, config)
        buildApiCategory(builder, entryBuilder, config)
        buildFeaturesCategory(builder, entryBuilder, config)
        buildControlsCategory(builder, entryBuilder, config)
        buildCacheCategory(builder, entryBuilder, config)

        return builder.build()
    }

    private fun buildGeneralCategory(builder: ConfigBuilder, entries: ConfigEntryBuilder, config: StellarLangConfig) {
        val category = builder.getOrCreateCategory(Component.literal("General"))

        val masterToggle = entries
            .startBooleanToggle(Component.literal("Master Switch (Enable All)"), config.enabled.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Globally enables or disables all translations"))
            .setSaveConsumer { value -> config.enabled.setValue(value, true) }
            .build()

        val targetLang = entries
            .startStrField(Component.literal("Target Language"), config.targetLanguage.value())
            .setDefaultValue("en")
            .setTooltip(Component.literal("Language code to translate into (e.g. en, es, fr, de, ja, zh)"))
            .setSaveConsumer { value -> config.targetLanguage.setValue(value.trim().lowercase(), true) }
            .build()

        category.addEntry(masterToggle)
        category.addEntry(targetLang)
    }

    private fun buildApiCategory(builder: ConfigBuilder, entries: ConfigEntryBuilder, config: StellarLangConfig) {
        val category = builder.getOrCreateCategory(Component.literal("API & Keys"))

        var currentHost = config.apiHost.value()
        var currentApiKey = config.apiKey.value()

        val apiHost = buildApiHostField(entries, config) { currentHost = it }
        val apiKey = buildApiKeyField(entries, config) { currentApiKey = it }
        val testButton = buildTestConnectionButton(
            entries = entries,
            config = config,
            getHost = { currentHost },
            getKey = { currentApiKey },
        )

        val instructions = entries
            .startTextDescription(
                Component.literal("Need an API key? Visit https://libretranslate.com or run a local instance."),
            )
            .build()

        category.addEntry(apiHost)
        category.addEntry(apiKey)
        category.addEntry(testButton)
        category.addEntry(instructions)
    }

    private fun buildApiHostField(
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
        onChanged: (String) -> Unit,
    ): me.shedaniel.clothconfig2.api.AbstractConfigListEntry<*> {
        return entries
            .startStrField(Component.literal("LibreTranslate API Host"), config.apiHost.value())
            .setDefaultValue("https://libretranslate.com")
            .setTooltip(
                Component.literal("Base URL of LibreTranslate (e.g. http://localhost:5000)"),
            )
            .setErrorSupplier { typed ->
                onChanged(typed.trim())
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.apiHost.setValue(value.trim(), true) }
            .build()
    }

    private fun buildApiKeyField(
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
        onChanged: (String) -> Unit,
    ): me.shedaniel.clothconfig2.api.AbstractConfigListEntry<*> {
        return entries
            .startStrField(Component.literal("API Key"), config.apiKey.value())
            .setDefaultValue("")
            .setTooltip(Component.literal("LibreTranslate API key. Get key at: https://libretranslate.com"))
            .setErrorSupplier { typed ->
                onChanged(typed.trim())
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.apiKey.setValue(value.trim(), true) }
            .build()
    }

    private fun buildTestConnectionButton(
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
        getHost: () -> String,
        getKey: () -> String,
    ): me.shedaniel.clothconfig2.api.AbstractConfigListEntry<*> {
        var lastToggleValue = false
        var testStatus = "Click to Test"
        var testError: String? = null

        return entries
            .startBooleanToggle(Component.literal("Test Translation Connection"), false)
            .setYesNoTextSupplier { boolValue ->
                if (boolValue != lastToggleValue) {
                    lastToggleValue = boolValue
                    val now = System.currentTimeMillis()
                    if (now - lastTestTimeMs >= TEST_DEBOUNCE_MS && isTesting.compareAndSet(false, true)) {
                        lastTestTimeMs = now
                        testStatus = "⌛ Testing..."
                        testError = null
                        triggerConnectionTest(config, getHost(), getKey()) { status, error ->
                            testStatus = status
                            testError = error
                            isTesting.set(false)
                        }
                    }
                }
                Component.literal(testStatus)
            }
            .setErrorSupplier { _ ->
                testError?.let {
                    java.util.Optional.of(Component.literal("Error: $it"))
                } ?: java.util.Optional.empty()
            }
            .setTooltip(
                Component.literal("Click to test connectivity and translation with the current API host and key"),
            )
            .build()
    }

    private fun triggerConnectionTest(
        config: StellarLangConfig,
        host: String,
        apiKey: String,
        onUpdate: (String, String?) -> Unit,
    ) {
        TranslationService.testConnection(
            host = host.ifBlank { config.apiHost.value() },
            apiKey = apiKey,
            targetLang = config.targetLanguage.value(),
        ) { result ->
            result.fold(
                onSuccess = { translated ->
                    onUpdate("✅ OK ('Hello' -> '$translated')", null)
                    showToast("Stellar Lang", "Connected! 'Hello' -> '$translated'")
                },
                onFailure = { err ->
                    val msg = err.message ?: err::class.simpleName ?: "Connection failed"
                    onUpdate("❌ Failed (Click to Retry)", msg)
                    showToast("Stellar Lang", "Test Failed: $msg")
                },
            )
        }
    }

    private fun showToast(title: String, message: String) {
        val toastId = testToastId ?: return
        runCatching {
            val mc = net.minecraft.client.Minecraft.getInstance()
            mc.execute {
                val toastManager = mc.gui.toastManager()
                net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(
                    toastManager,
                    toastId,
                    Component.literal(title),
                    Component.literal(message),
                )
            }
        }
    }

    private fun buildFeaturesCategory(builder: ConfigBuilder, entries: ConfigEntryBuilder, config: StellarLangConfig) {
        val category = builder.getOrCreateCategory(Component.literal("Categories"))

        val chatEntry = entries
            .startBooleanToggle(Component.literal("Translate Chat"), config.translateChat.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates incoming chat with clickable [T] toggle"))
            .setSaveConsumer { value -> config.translateChat.setValue(value, true) }
            .build()

        val signsEntry = entries
            .startBooleanToggle(Component.literal("Translate Signs"), config.translateSigns.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates signs using multi-line and area context"))
            .setSaveConsumer { value -> config.translateSigns.setValue(value, true) }
            .build()

        val booksEntry = entries
            .startBooleanToggle(Component.literal("Translate Books"), config.translateBooks.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates book contents together preserving context across pages"))
            .setSaveConsumer { value -> config.translateBooks.setValue(value, true) }
            .build()

        val entitiesEntry = entries
            .startBooleanToggle(Component.literal("Translate Entities"), config.translateEntities.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates entity custom nametags and display names"))
            .setSaveConsumer { value -> config.translateEntities.setValue(value, true) }
            .build()

        val itemsEntry = entries
            .startBooleanToggle(Component.literal("Translate Items"), config.translateItems.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates item names and tooltips"))
            .setSaveConsumer { value -> config.translateItems.setValue(value, true) }
            .build()

        category.addEntry(chatEntry)
        category.addEntry(signsEntry)
        category.addEntry(booksEntry)
        category.addEntry(entitiesEntry)
        category.addEntry(itemsEntry)
    }

    private fun buildControlsCategory(builder: ConfigBuilder, entries: ConfigEntryBuilder, config: StellarLangConfig) {
        val category = builder.getOrCreateCategory(Component.literal("Controls"))

        val showOriginalKeyEntry = entries
            .startIntField(
                Component.literal("Show Original Key (GLFW)"),
                config.showOriginalKey.value(),
            )
            .setDefaultValue(Key.KEY_COMMA)
            .setTooltip(Component.literal("Hold key to show original text on signs & entities (default: 44 for comma)"))
            .setSaveConsumer { value -> config.showOriginalKey.setValue(value, true) }
            .build()

        category.addEntry(showOriginalKeyEntry)
    }

    private fun buildCacheCategory(builder: ConfigBuilder, entries: ConfigEntryBuilder, config: StellarLangConfig) {
        val category = builder.getOrCreateCategory(Component.literal("Cache"))

        val cacheToDiskEntry = entries
            .startBooleanToggle(Component.literal("Cache to Disk"), config.cacheToDisk.value())
            .setDefaultValue(true)
            .setTooltip(
                Component.literal("Persists translations across game sessions in config/stellar_lang/cache.json"),
            )
            .setSaveConsumer { value -> config.cacheToDisk.setValue(value, true) }
            .build()

        val maxCacheEntriesEntry = entries
            .startIntField(Component.literal("Max Cache Entries"), config.maxCacheEntries.value())
            .setDefaultValue(StellarLangConfig.DEFAULT_MAX_CACHE_ENTRIES)
            .setTooltip(Component.literal("Maximum number of translation entries kept in memory and on disk"))
            .setSaveConsumer { value -> config.maxCacheEntries.setValue(value, true) }
            .build()

        category.addEntry(cacheToDiskEntry)
        category.addEntry(maxCacheEntriesEntry)
    }
}
