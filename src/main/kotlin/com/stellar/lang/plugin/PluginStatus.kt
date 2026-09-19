package com.stellar.lang.plugin

/**
 * Health and operational status of a translation or language detection plugin.
 */
sealed class PluginStatus {
    data class Ready(val message: String = "Ready") : PluginStatus()
    data class Downloading(val progressPercent: Int, val message: String) : PluginStatus()
    data class NotConfigured(val reason: String) : PluginStatus()
    data class Error(val error: String) : PluginStatus()
    data object Disabled : PluginStatus()

    fun displayText(): String = when (this) {
        is Ready -> "✅ $message"
        is Downloading -> "⏳ Downloading ($progressPercent%)... $message"
        is NotConfigured -> "⚠️ Not Configured: $reason"
        is Error -> "❌ Error: $error"
        is Disabled -> "⏸️ Disabled"
    }
}
