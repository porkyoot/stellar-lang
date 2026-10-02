package com.stellar.lang.config

import com.stellar.core.input.Key
import org.quiltmc.config.api.ReflectiveConfig
import org.quiltmc.config.api.annotations.Comment
import org.quiltmc.config.api.values.TrackedValue

/**
 * Quilt ReflectiveConfig schema for Stellar Lang.
 */
class StellarLangConfig : ReflectiveConfig() {
    @Comment("Master switch to enable or disable all Stellar Lang translations")
    val enabled: TrackedValue<Boolean> = value(true)

    @Comment("Active translation provider plugin (e.g. onnx, libretranslate, deepl)")
    val translationPlugin: TrackedValue<String> = value("onnx")

    @Comment("Active language detection provider plugin (e.g. onnx, libretranslate, deepl)")
    val detectionPlugin: TrackedValue<String> = value("onnx")

    @Comment("Local directory for storing downloaded ONNX models")
    val onnxModelDir: TrackedValue<String> = value("config/stellar_lang/models")

    @Comment("Automatically download missing ONNX models when needed")
    val onnxAutoDownload: TrackedValue<Boolean> = value(true)

    @Comment("Number of CPU execution threads for ONNX Runtime inference")
    val onnxExecutionThreads: TrackedValue<Int> = value(DEFAULT_ONNX_THREADS)

    @Comment("LibreTranslate API base URL (default: https://libretranslate.com)")
    val apiHost: TrackedValue<String> = value("https://libretranslate.com")

    @Comment("API key for LibreTranslate (optional for public/local instances without auth)")
    val apiKey: TrackedValue<String> = value("")

    @Comment("DeepL API authentication key (Free or Pro)")
    val deeplApiKey: TrackedValue<String> = value("")

    @Comment("DeepL API endpoint host (default: 'auto' which resolves based on API key tier, or custom URL)")
    val deeplApiHost: TrackedValue<String> = value("auto")

    @Comment("DeepL translation formality preference (default, more, less, prefer_more, prefer_less)")
    val deeplFormality: TrackedValue<String> = value("default")

    @Comment("Minimum interval in milliseconds between DeepL API requests to avoid rate limits (default: 250ms)")
    val deeplRequestIntervalMs: TrackedValue<Int> = value(DEFAULT_DEEPL_REQUEST_INTERVAL_MS)

    @Comment("Target language code (e.g. auto, en, es, fr, de, ja, zh). 'auto' infers from game setting.")
    val targetLanguage: TrackedValue<String> = value("auto")

    @Comment("Enable translation of in-game chat messages")
    val translateChat: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of player names across chat and entity nametags")
    val translatePlayerNames: TrackedValue<Boolean> = value(false)

    @Comment("Enable translation of signs")
    val translateSigns: TrackedValue<Boolean> = value(true)

    @Comment("Enable floating tooltips and indicators for translated signs")
    val signTooltips: TrackedValue<Boolean> = value(true)

    @Comment("Interval in milliseconds between raycast line-of-sight checks for sign tooltips (0 = every frame)")
    val signRaycastIntervalMs: TrackedValue<Int> = value(DEFAULT_SIGN_RAYCAST_INTERVAL_MS)

    @Comment("Enable translation of books with area and page context")
    val translateBooks: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of entity custom and display names")
    val translateEntities: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of item display and custom names")
    val translateItems: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of renamed container and inventory labels")
    val translateContainers: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of banners and markers on maps")
    val translateMapBanners: TrackedValue<Boolean> = value(true)

    @Comment("Hide the flag and [...] translation and in-flight status indicators")
    val hideIndicators: TrackedValue<Boolean> = value(false)

    @Comment("GLFW key code to show original text on signs and entities (default: COMMA = 44)")
    val showOriginalKey: TrackedValue<Int> = value(Key.KEY_COMMA)

    @Comment("GLFW key code to retry/refresh translation for targeted object (default: PERIOD = 46)")
    val retryTargetKey: TrackedValue<Int> = value(Key.KEY_PERIOD)

    @Comment("Enable persistent disk caching of translations across game sessions")
    val cacheToDisk: TrackedValue<Boolean> = value(true)

    @Comment("Maximum number of translations to keep in memory/disk cache")
    val maxCacheEntries: TrackedValue<Int> = value(DEFAULT_MAX_CACHE_ENTRIES)

    @Comment("Show in-game toast notifications for the first occurrence of API errors")
    val showErrorToasts: TrackedValue<Boolean> = value(true)

    companion object {
        const val DEFAULT_MAX_CACHE_ENTRIES: Int = 5000
        const val DEFAULT_ONNX_THREADS: Int = 2
        const val MIN_ONNX_THREADS: Int = 1
        const val MAX_ONNX_THREADS: Int = 8
        const val DEFAULT_SIGN_RAYCAST_INTERVAL_MS: Int = 100
        const val MIN_SIGN_RAYCAST_INTERVAL_MS: Int = 0
        const val MAX_SIGN_RAYCAST_INTERVAL_MS: Int = 1000
        const val DEFAULT_DEEPL_REQUEST_INTERVAL_MS: Int = 250
        const val MIN_DEEPL_REQUEST_INTERVAL_MS: Int = 0
        const val MAX_DEEPL_REQUEST_INTERVAL_MS: Int = 5000
    }
}
