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

    @Comment("LibreTranslate API base URL (default: https://libretranslate.com)")
    val apiHost: TrackedValue<String> = value("https://libretranslate.com")

    @Comment("API key for LibreTranslate (optional for public/local instances without auth)")
    val apiKey: TrackedValue<String> = value("")

    @Comment("Target language code (e.g. auto, en, es, fr, de, ja, zh). 'auto' infers from game setting.")
    val targetLanguage: TrackedValue<String> = value("auto")

    @Comment("Enable translation of in-game chat messages")
    val translateChat: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of signs")
    val translateSigns: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of books with area and page context")
    val translateBooks: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of entity custom and display names")
    val translateEntities: TrackedValue<Boolean> = value(true)

    @Comment("Enable translation of item display and custom names")
    val translateItems: TrackedValue<Boolean> = value(true)

    @Comment("GLFW key code to show original text on signs and entities (default: COMMA = 44)")
    val showOriginalKey: TrackedValue<Int> = value(Key.KEY_COMMA)

    @Comment("Enable persistent disk caching of translations across game sessions")
    val cacheToDisk: TrackedValue<Boolean> = value(true)

    @Comment("Maximum number of translations to keep in memory/disk cache")
    val maxCacheEntries: TrackedValue<Int> = value(DEFAULT_MAX_CACHE_ENTRIES)

    companion object {
        const val DEFAULT_MAX_CACHE_ENTRIES: Int = 5000
    }
}
