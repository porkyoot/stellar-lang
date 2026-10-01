package com.stellar.lang.error

/**
 * Enumeration of distinct translation and language detection error categories.
 */
enum class TranslationErrorKind {
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    AUTHENTICATION_FAILED,
    CONNECTION_REFUSED,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    BAD_REQUEST,
    SERVER_ERROR,
    MODEL_NOT_READY,
    UNKNOWN,
}

/**
 * Comprehensive diagnostic information for translation failures,
 * including user-friendly guidance on what happened and how to fix it.
 */
data class TranslationErrorInfo(
    val providerId: String,
    val kind: TranslationErrorKind,
    val title: String,
    val message: String,
    val resolution: String,
    val statusCode: Int? = null,
    val technicalDetail: String? = null,
)
