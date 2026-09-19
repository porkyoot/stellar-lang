@file:Suppress(
    "LargeClass",
    "LongMethod",
    "StringLiteralDuplication",
)

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
        buildProvidersCategory(builder, entryBuilder, config)
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

        val inferred = TranslationService.inferTargetLanguage().uppercase()
        val targetLang = entries
            .startStrField(Component.literal("Target Language"), config.targetLanguage.value())
            .setDefaultValue("auto")
            .setTooltip(
                Component.literal(
                    "Language code to translate into (e.g. auto, en, es, fr). 'auto' infers from game ($inferred).",
                ),
            )
            .setSaveConsumer { value -> config.targetLanguage.setValue(value.trim().lowercase(), true) }
            .build()

        category.addEntry(masterToggle)
        category.addEntry(targetLang)
    }

    @Suppress("LongMethod")
    private fun buildProvidersCategory(
        builder: ConfigBuilder,
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
    ) {
        val category = builder.getOrCreateCategory(Component.literal("Providers & Models"))

        var selectedTranslator = config.translationPlugin.value().trim().lowercase()
        var selectedDetector = config.detectionPlugin.value().trim().lowercase()

        val providerOptions = listOf("onnx", "libretranslate")
        val nameMap = mapOf(
            "onnx" to "ONNX Runtime (Local Offline)",
            "libretranslate" to "LibreTranslate (HTTP API)",
        )

        val onnxSubBuilder = entries.startSubCategory(Component.literal("ONNX Runtime (Local Offline) Settings"))
        val libreSubBuilder = entries.startSubCategory(Component.literal("LibreTranslate (HTTP API) Settings"))

        buildOnnxSubCategory(onnxSubBuilder, entries, config)
        buildLibreSubCategory(libreSubBuilder, entries, config)

        val onnxSubCategory = onnxSubBuilder.build()
        val libreSubCategory = libreSubBuilder.build()

        fun updateSubCategoryVisibility() {
            val needsOnnx = selectedTranslator == "onnx" || selectedDetector == "onnx"
            val needsLibre = selectedTranslator == "libretranslate" || selectedDetector == "libretranslate"
            onnxSubCategory.setExpanded(needsOnnx)
            libreSubCategory.setExpanded(needsLibre)
        }

        val transDropdown = entries
            .startStringDropdownMenu(
                Component.literal("Translation Provider"),
                selectedTranslator,
                { id -> Component.literal(nameMap[id] ?: id) },
            )
            .setSelections(providerOptions)
            .setDefaultValue("onnx")
            .setTooltip(Component.literal("Plugin used to translate text into target language"))
            .setErrorSupplier { typed ->
                val clean = typed.trim().lowercase()
                if (clean in providerOptions) {
                    selectedTranslator = clean
                    updateSubCategoryVisibility()
                }
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.translationPlugin.setValue(value.trim().lowercase(), true) }
            .build()

        val detectDropdown = entries
            .startStringDropdownMenu(
                Component.literal("Language Detection Provider"),
                selectedDetector,
                { id -> Component.literal(nameMap[id] ?: id) },
            )
            .setSelections(providerOptions)
            .setDefaultValue("onnx")
            .setTooltip(Component.literal("Plugin used to detect the source language of in-game text"))
            .setErrorSupplier { typed ->
                val clean = typed.trim().lowercase()
                if (clean in providerOptions) {
                    selectedDetector = clean
                    updateSubCategoryVisibility()
                }
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.detectionPlugin.setValue(value.trim().lowercase(), true) }
            .build()

        updateSubCategoryVisibility()

        category.addEntry(transDropdown)
        category.addEntry(detectDropdown)
        category.addEntry(onnxSubCategory)
        category.addEntry(libreSubCategory)
    }

    private fun buildOnnxSubCategory(
        subCategory: me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder,
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
    ) {
        val modelManager = com.stellar.lang.plugin.onnx.OnnxModelManager
        val statusText = modelManager.getDetectionStatus().displayText()

        val statusDescription = entries
            .startTextDescription(Component.literal("Status: $statusText"))
            .build()

        var downloadTriggered = false
        var downloadStatus = "Download Language Models (HuggingFace)"
        val downloadButton = entries
            .startBooleanToggle(Component.literal("Model Downloader"), false)
            .setYesNoTextSupplier { boolVal ->
                if (boolVal && !downloadTriggered) {
                    downloadTriggered = true
                    downloadStatus = "⏳ Downloading Detection Model..."
                    modelManager.downloadDetectionModelAsync(
                        onProgress = { pct -> downloadStatus = "⏳ Downloading ($pct%)..." },
                        onComplete = { result ->
                            result.fold(
                                onSuccess = {
                                    downloadStatus = "✅ Detection Model Downloaded!"
                                    showToast("Stellar Lang", "ONNX Detection model downloaded successfully!")
                                },
                                onFailure = { ex ->
                                    downloadStatus = "❌ Download Failed: ${ex.message}"
                                    showToast("Stellar Lang", "Model download failed: ${ex.message}")
                                },
                            )
                        },
                    )
                }
                Component.literal(downloadStatus)
            }
            .setTooltip(Component.literal("Click to trigger immediate download of the ONNX language detection model"))
            .build()

        val autoDownload = entries
            .startBooleanToggle(Component.literal("Auto-Download Missing Models"), config.onnxAutoDownload.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Automatically download required models on demand in the background"))
            .setSaveConsumer { value -> config.onnxAutoDownload.setValue(value, true) }
            .build()

        val modelDir = entries
            .startStrField(Component.literal("Model Storage Directory"), config.onnxModelDir.value())
            .setDefaultValue("config/stellar_lang/models")
            .setTooltip(Component.literal("Local path where ONNX neural models are stored"))
            .setSaveConsumer { value -> config.onnxModelDir.setValue(value.trim(), true) }
            .build()

        val threads = entries
            .startIntSlider(
                Component.literal("Inference CPU Threads"),
                config.onnxExecutionThreads.value(),
                StellarLangConfig.MIN_ONNX_THREADS,
                StellarLangConfig.MAX_ONNX_THREADS,
            )
            .setDefaultValue(StellarLangConfig.DEFAULT_ONNX_THREADS)
            .setTooltip(Component.literal("Number of threads allocated for ONNX Runtime CPU inference"))
            .setSaveConsumer { value -> config.onnxExecutionThreads.setValue(value, true) }
            .build()

        subCategory.add(statusDescription)
        subCategory.add(downloadButton)
        subCategory.add(autoDownload)
        subCategory.add(modelDir)
        subCategory.add(threads)
    }

    private fun buildLibreSubCategory(
        subCategory: me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder,
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
    ) {
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

        subCategory.add(apiHost)
        subCategory.add(apiKey)
        subCategory.add(testButton)
        subCategory.add(instructions)
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
            targetLang = TranslationService.getTargetLanguage(),
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
