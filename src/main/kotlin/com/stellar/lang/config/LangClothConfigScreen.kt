@file:Suppress(
    "LargeClass",
    "LongMethod",
    "StringLiteralDuplication",
    "TooManyFunctions",
)

package com.stellar.lang.config

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.badge.LanguageFlagHelper
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
    private var lastClearCacheTimeMs = 0L
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
        val currentTarget = config.targetLanguage.value().trim().lowercase()
        val effectiveLang = if (currentTarget == "auto" || currentTarget.isBlank()) {
            TranslationService.inferTargetLanguage()
        } else {
            currentTarget
        }
        val flagChar = LanguageFlagHelper.getFlagChar(effectiveLang)
        val langName = LanguageFlagHelper.getLanguageName(effectiveLang)

        val targetLang = entries
            .startStrField(Component.literal("$flagChar Target Language"), config.targetLanguage.value())
            .setDefaultValue("auto")
            .setTooltip(
                Component.literal(
                    "Target: $flagChar $langName (${effectiveLang.uppercase()})\n" +
                        "Language code to translate into (e.g. auto, en, es, fr). 'auto' infers from game ($inferred).",
                ),
            )
            .setSaveConsumer { value -> config.targetLanguage.setValue(value.trim().lowercase(), true) }
            .build()

        val hideIndicators = entries
            .startBooleanToggle(Component.literal("Hide Flag and [...] Indicators"), config.hideIndicators.value())
            .setDefaultValue(false)
            .setTooltip(Component.literal("Hides visual flag and [...] status badges on translated text"))
            .setSaveConsumer { value -> config.hideIndicators.setValue(value, true) }
            .build()

        val errorToastsToggle = entries
            .startBooleanToggle(Component.literal("Show Error Toasts"), config.showErrorToasts.value())
            .setDefaultValue(true)
            .setTooltip(
                Component.literal("Shows an in-game toast notification explaining the first occurrence of API errors"),
            )
            .setSaveConsumer { value -> config.showErrorToasts.setValue(value, true) }
            .build()

        category.addEntry(masterToggle)
        category.addEntry(targetLang)
        category.addEntry(hideIndicators)
        category.addEntry(errorToastsToggle)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun buildProvidersCategory(
        builder: ConfigBuilder,
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
    ) {
        val category = builder.getOrCreateCategory(Component.literal("Providers & Models"))

        var selectedTranslator = config.translationPlugin.value().trim().lowercase()
        var selectedDetector = config.detectionPlugin.value().trim().lowercase()

        val providerOptions = listOf("onnx", "libretranslate", "deepl", "google")
        val nameMap = mapOf(
            "onnx" to "ONNX Runtime (Local Offline)",
            "libretranslate" to "LibreTranslate (HTTP API)",
            "deepl" to "DeepL (Official API)",
            "google" to "Google Translate (Cloud API)",
        )

        val onnxSubBuilder = entries.startSubCategory(Component.literal("ONNX Runtime (Local Offline) Settings"))
        val libreSubBuilder = entries.startSubCategory(Component.literal("LibreTranslate (HTTP API) Settings"))
        val deeplSubBuilder = entries.startSubCategory(Component.literal("DeepL (Official API) Settings"))
        val googleSubBuilder = entries.startSubCategory(Component.literal("Google Translate (Cloud API) Settings"))

        buildOnnxSubCategory(onnxSubBuilder, entries, config)
        buildLibreSubCategory(libreSubBuilder, entries, config)
        buildDeeplSubCategory(deeplSubBuilder, entries, config)
        buildGoogleSubCategory(googleSubBuilder, entries, config)

        val onnxSubCategory = onnxSubBuilder.build()
        val libreSubCategory = libreSubBuilder.build()
        val deeplSubCategory = deeplSubBuilder.build()
        val googleSubCategory = googleSubBuilder.build()

        fun updateSubCategoryVisibility() {
            val needsOnnx = selectedTranslator == "onnx" || selectedDetector == "onnx"
            val needsLibre = selectedTranslator == "libretranslate" || selectedDetector == "libretranslate"
            val needsDeepl = selectedTranslator == "deepl" || selectedDetector == "deepl"
            val needsGoogle = selectedTranslator == "google" || selectedDetector == "google"
            onnxSubCategory.setExpanded(needsOnnx)
            libreSubCategory.setExpanded(needsLibre)
            deeplSubCategory.setExpanded(needsDeepl)
            googleSubCategory.setExpanded(needsGoogle)
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
        category.addEntry(deeplSubCategory)
        category.addEntry(googleSubCategory)
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

        val testOnnxButton = buildTestOnnxButton(entries)

        subCategory.add(statusDescription)
        subCategory.add(downloadButton)
        subCategory.add(testOnnxButton)
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

    private fun buildDeeplSubCategory(
        subCategory: me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder,
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
    ) {
        var currentApiKey = config.deeplApiKey.value()
        var currentHost = config.deeplApiHost.value()

        val apiKey = entries
            .startStrField(Component.literal("DeepL API Key"), config.deeplApiKey.value())
            .setDefaultValue("")
            .setTooltip(
                Component.literal(
                    "Authentication key for DeepL API. Free keys end in :fx. Visit: https://www.deepl.com/pro-api",
                ),
            )
            .setErrorSupplier { typed ->
                currentApiKey = typed.trim()
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.deeplApiKey.setValue(value.trim(), true) }
            .build()

        val apiHost = entries
            .startStrField(Component.literal("DeepL API Host"), config.deeplApiHost.value())
            .setDefaultValue("auto")
            .setTooltip(
                Component.literal("Base URL of DeepL API ('auto' detects Free/Pro based on key, or set custom proxy)"),
            )
            .setErrorSupplier { typed ->
                currentHost = typed.trim()
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.deeplApiHost.setValue(value.trim(), true) }
            .build()

        val formalityOptions = listOf("default", "more", "less", "prefer_more", "prefer_less")
        val formalityField = entries
            .startStringDropdownMenu(
                Component.literal("Formality"),
                config.deeplFormality.value(),
                { id -> Component.literal(id.replace('_', ' ').replaceFirstChar { it.uppercase() }) },
            )
            .setSelections(formalityOptions)
            .setDefaultValue("default")
            .setTooltip(
                Component.literal("Tone of translated text (formal or informal) for supported target languages"),
            )
            .setSaveConsumer { value -> config.deeplFormality.setValue(value.trim().lowercase(), true) }
            .build()

        val requestIntervalField = entries
            .startIntField(Component.literal("Request Interval (ms)"), config.deeplRequestIntervalMs.value())
            .setDefaultValue(StellarLangConfig.DEFAULT_DEEPL_REQUEST_INTERVAL_MS)
            .setMin(StellarLangConfig.MIN_DEEPL_REQUEST_INTERVAL_MS)
            .setMax(StellarLangConfig.MAX_DEEPL_REQUEST_INTERVAL_MS)
            .setTooltip(
                Component.literal("Delay in milliseconds between DeepL API requests to prevent rate limits (HTTP 429)"),
            )
            .setSaveConsumer { value -> config.deeplRequestIntervalMs.setValue(value, true) }
            .build()

        val testButton = buildTestDeeplConnectionButton(
            entries = entries,
            config = config,
            getKey = { currentApiKey },
            getHost = { currentHost },
        )

        val instructions = entries
            .startTextDescription(
                Component.literal(
                    "Need an API key? Visit https://www.deepl.com/pro-api to register for DeepL Free or Pro.",
                ),
            )
            .build()

        subCategory.add(apiKey)
        subCategory.add(apiHost)
        subCategory.add(formalityField)
        subCategory.add(requestIntervalField)
        subCategory.add(testButton)
        subCategory.add(instructions)
    }

    private fun buildTestDeeplConnectionButton(
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
        getKey: () -> String,
        getHost: () -> String,
    ): me.shedaniel.clothconfig2.api.AbstractConfigListEntry<*> {
        var lastToggleValue = false
        var testStatus = "Click to Test DeepL"
        var testError: String? = null

        return entries
            .startBooleanToggle(Component.literal("Test DeepL Connection"), false)
            .setYesNoTextSupplier { boolValue ->
                if (boolValue != lastToggleValue) {
                    lastToggleValue = boolValue
                    val now = System.currentTimeMillis()
                    if (now - lastTestTimeMs >= TEST_DEBOUNCE_MS && isTesting.compareAndSet(false, true)) {
                        lastTestTimeMs = now
                        testStatus = "⌛ Testing DeepL..."
                        testError = null
                        triggerDeeplConnectionTest(config, getKey(), getHost()) { status, error ->
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
                Component.literal("Click to test connectivity and translation with the current DeepL API key"),
            )
            .build()
    }

    private fun triggerDeeplConnectionTest(
        config: StellarLangConfig,
        apiKey: String,
        host: String,
        onUpdate: (String, String?) -> Unit,
    ) {
        TranslationService.testDeepl(
            apiKey = apiKey.ifBlank { config.deeplApiKey.value() },
            host = host.ifBlank { config.deeplApiHost.value() },
            targetLang = TranslationService.getTargetLanguage(),
        ) { result ->
            result.fold(
                onSuccess = { translated ->
                    onUpdate("✅ OK ('Hello' -> '$translated')", null)
                    showToast("Stellar Lang", "DeepL OK! 'Hello' -> '$translated'")
                },
                onFailure = { err ->
                    val msg = err.message ?: err::class.simpleName ?: "Connection failed"
                    onUpdate("❌ Failed (Click to Retry)", msg)
                    showToast("Stellar Lang", "DeepL Failed: $msg")
                },
            )
        }
    }

    private fun buildGoogleSubCategory(
        subCategory: me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder,
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
    ) {
        var currentApiKey = config.googleApiKey.value()
        var currentHost = config.googleApiHost.value()

        val apiKey = entries
            .startStrField(Component.literal("Google API Key"), config.googleApiKey.value())
            .setDefaultValue("")
            .setTooltip(
                Component.literal(
                    "API key for Google Cloud Translation API. Get one from Google Cloud Console.",
                ),
            )
            .setErrorSupplier { typed ->
                currentApiKey = typed.trim()
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.googleApiKey.setValue(value.trim(), true) }
            .build()

        val apiHost = entries
            .startStrField(Component.literal("Google API Host"), config.googleApiHost.value())
            .setDefaultValue("auto")
            .setTooltip(
                Component.literal(
                    "Base URL of Google Translation API ('auto' uses translation.googleapis.com, or custom proxy)",
                ),
            )
            .setErrorSupplier { typed ->
                currentHost = typed.trim()
                java.util.Optional.empty()
            }
            .setSaveConsumer { value -> config.googleApiHost.setValue(value.trim(), true) }
            .build()

        val requestIntervalField = entries
            .startIntField(Component.literal("Request Interval (ms)"), config.googleRequestIntervalMs.value())
            .setDefaultValue(StellarLangConfig.DEFAULT_GOOGLE_REQUEST_INTERVAL_MS)
            .setMin(StellarLangConfig.MIN_GOOGLE_REQUEST_INTERVAL_MS)
            .setMax(StellarLangConfig.MAX_GOOGLE_REQUEST_INTERVAL_MS)
            .setTooltip(
                Component.literal(
                    "Delay in milliseconds between Google API requests to prevent rate limits and quota spikes",
                ),
            )
            .setSaveConsumer { value -> config.googleRequestIntervalMs.setValue(value, true) }
            .build()

        val testButton = buildTestGoogleConnectionButton(
            entries = entries,
            config = config,
            getKey = { currentApiKey },
            getHost = { currentHost },
        )

        val instructions = entries
            .startTextDescription(
                Component.literal(
                    "Need an API key? Enable Cloud Translation API in Google Cloud Console & create an API key.",
                ),
            )
            .build()

        subCategory.add(apiKey)
        subCategory.add(apiHost)
        subCategory.add(requestIntervalField)
        subCategory.add(testButton)
        subCategory.add(instructions)
    }

    private fun buildTestGoogleConnectionButton(
        entries: ConfigEntryBuilder,
        config: StellarLangConfig,
        getKey: () -> String,
        getHost: () -> String,
    ): me.shedaniel.clothconfig2.api.AbstractConfigListEntry<*> {
        var lastToggleValue = false
        var testStatus = "Click to Test Google"
        var testError: String? = null

        return entries
            .startBooleanToggle(Component.literal("Test Google Connection"), false)
            .setYesNoTextSupplier { boolValue ->
                if (boolValue != lastToggleValue) {
                    lastToggleValue = boolValue
                    val now = System.currentTimeMillis()
                    if (now - lastTestTimeMs >= TEST_DEBOUNCE_MS && isTesting.compareAndSet(false, true)) {
                        lastTestTimeMs = now
                        testStatus = "⌛ Testing Google..."
                        testError = null
                        triggerGoogleConnectionTest(config, getKey(), getHost()) { status, error ->
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
                Component.literal("Click to test connectivity and translation with the current Google API key"),
            )
            .build()
    }

    private fun triggerGoogleConnectionTest(
        config: StellarLangConfig,
        apiKey: String,
        host: String,
        onUpdate: (String, String?) -> Unit,
    ) {
        TranslationService.testGoogle(
            apiKey = apiKey.ifBlank { config.googleApiKey.value() },
            host = host.ifBlank { config.googleApiHost.value() },
            targetLang = TranslationService.getTargetLanguage(),
        ) { result ->
            result.fold(
                onSuccess = { translated ->
                    onUpdate("✅ OK ('Hello' -> '$translated')", null)
                    showToast("Stellar Lang", "Google OK! 'Hello' -> '$translated'")
                },
                onFailure = { err ->
                    val msg = err.message ?: err::class.simpleName ?: "Connection failed"
                    onUpdate("❌ Failed (Click to Retry)", msg)
                    showToast("Stellar Lang", "Google Failed: $msg")
                },
            )
        }
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

    private fun buildTestOnnxButton(
        entries: ConfigEntryBuilder,
    ): me.shedaniel.clothconfig2.api.AbstractConfigListEntry<*> {
        var lastToggleValue = false
        var testStatus = "Click to Test ONNX"
        var testError: String? = null

        return entries
            .startBooleanToggle(Component.literal("Test ONNX Models"), false)
            .setYesNoTextSupplier { boolValue ->
                if (boolValue != lastToggleValue) {
                    lastToggleValue = boolValue
                    val now = System.currentTimeMillis()
                    if (now - lastTestTimeMs >= TEST_DEBOUNCE_MS && isTesting.compareAndSet(false, true)) {
                        lastTestTimeMs = now
                        testStatus = "⌛ Testing ONNX..."
                        testError = null
                        triggerOnnxTest { status, error ->
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
                Component.literal("Click to test local ONNX language detection and translation models"),
            )
            .build()
    }

    private fun triggerOnnxTest(
        onUpdate: (String, String?) -> Unit,
    ) {
        TranslationService.testOnnx(
            targetLang = TranslationService.getTargetLanguage(),
        ) { result ->
            result.fold(
                onSuccess = { info ->
                    onUpdate("✅ OK ($info)", null)
                    showToast("Stellar Lang", "ONNX OK: $info")
                },
                onFailure = { err ->
                    val msg = err.message ?: err::class.simpleName ?: "Test failed"
                    onUpdate("❌ Failed (Click to Retry)", msg)
                    showToast("Stellar Lang", "ONNX Test Failed: $msg")
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
            .setTooltip(Component.literal("Translates incoming chat with clickable flag toggle"))
            .setSaveConsumer { value -> config.translateChat.setValue(value, true) }
            .build()

        val signsEntry = entries
            .startBooleanToggle(Component.literal("Translate Signs"), config.translateSigns.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates signs using multi-line and area context"))
            .setSaveConsumer { value -> config.translateSigns.setValue(value, true) }
            .build()

        val signTooltipsEntry = entries
            .startBooleanToggle(Component.literal("Sign Tooltips & Indicators"), config.signTooltips.value())
            .setDefaultValue(true)
            .setTooltip(
                Component.literal("Renders floating flag indicators and overflow tooltips when aiming at signs"),
            )
            .setSaveConsumer { value -> config.signTooltips.setValue(value, true) }
            .build()

        val signRaycastIntervalEntry = entries
            .startIntSlider(
                Component.literal("Sign Raycast Interval (ms)"),
                config.signRaycastIntervalMs.value(),
                StellarLangConfig.MIN_SIGN_RAYCAST_INTERVAL_MS,
                StellarLangConfig.MAX_SIGN_RAYCAST_INTERVAL_MS,
            )
            .setDefaultValue(StellarLangConfig.DEFAULT_SIGN_RAYCAST_INTERVAL_MS)
            .setTooltip(
                Component.literal(
                    "Throttle interval in milliseconds for sign line-of-sight raycasting (0 = every frame)",
                ),
            )
            .setSaveConsumer { value -> config.signRaycastIntervalMs.setValue(value, true) }
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

        val playerNamesEntry = entries
            .startBooleanToggle(Component.literal("Translate Player Names"), config.translatePlayerNames.value())
            .setDefaultValue(false)
            .setTooltip(
                Component.literal(
                    "Globally enables or disables translation of player names in entity nametags and chat",
                ),
            )
            .setSaveConsumer { value -> config.translatePlayerNames.setValue(value, true) }
            .build()

        val itemsEntry = entries
            .startBooleanToggle(Component.literal("Translate Items"), config.translateItems.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates item names and tooltips"))
            .setSaveConsumer { value -> config.translateItems.setValue(value, true) }
            .build()

        val containersEntry = entries
            .startBooleanToggle(Component.literal("Translate Containers"), config.translateContainers.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates renamed container and inventory labels (chests, etc.)"))
            .setSaveConsumer { value -> config.translateContainers.setValue(value, true) }
            .build()

        val mapBannersEntry = entries
            .startBooleanToggle(Component.literal("Translate Map Banners"), config.translateMapBanners.value())
            .setDefaultValue(true)
            .setTooltip(Component.literal("Translates banner names and markers on maps"))
            .setSaveConsumer { value -> config.translateMapBanners.setValue(value, true) }
            .build()

        category.addEntry(chatEntry)
        category.addEntry(signsEntry)
        category.addEntry(signTooltipsEntry)
        category.addEntry(signRaycastIntervalEntry)
        category.addEntry(booksEntry)
        category.addEntry(entitiesEntry)
        category.addEntry(playerNamesEntry)
        category.addEntry(itemsEntry)
        category.addEntry(containersEntry)
        category.addEntry(mapBannersEntry)
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
        category.addEntry(buildClearCacheButton(entries))
    }

    internal fun buildClearCacheButton(
        entries: ConfigEntryBuilder,
    ): me.shedaniel.clothconfig2.api.AbstractConfigListEntry<*> {
        var lastToggleValue = false

        return entries
            .startBooleanToggle(Component.literal("Clear Translation Cache"), false)
            .setYesNoTextSupplier { boolValue ->
                if (boolValue != lastToggleValue) {
                    lastToggleValue = boolValue
                    lastClearCacheTimeMs = System.currentTimeMillis()
                    TranslationService.clearAllCaches()
                    showToast("Stellar Lang", "Translation cache cleared")
                }
                if (lastClearCacheTimeMs > 0L) {
                    Component.literal("✅ Cache Cleared!")
                } else {
                    Component.literal("Click to Clear")
                }
            }
            .setTooltip(
                Component.literal("Clears in-memory and on-disk translation caches across all features"),
            )
            .build()
    }
}
