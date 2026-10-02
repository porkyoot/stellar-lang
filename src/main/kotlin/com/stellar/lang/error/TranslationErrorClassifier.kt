package com.stellar.lang.error

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException

/**
 * Classifies HTTP status codes, exceptions, and local provider issues into structured TranslationErrorInfo.
 */
object TranslationErrorClassifier {
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_QUOTA_EXCEEDED = 456
    private const val HTTP_SERVER_ERROR_MIN = 500
    private const val HTTP_SERVER_ERROR_MAX = 599

    private data class HttpErrorMeta(
        val kind: TranslationErrorKind,
        val title: String,
        val message: String,
        val resolution: String,
    )

    fun classifyHttpStatus(
        providerId: String,
        statusCode: Int,
        responseBody: String? = null,
    ): TranslationErrorInfo {
        val providerName = formatProviderName(providerId)
        val meta = getHttpErrorMeta(providerName, statusCode, providerId, responseBody)
        return TranslationErrorInfo(
            providerId = providerId,
            kind = meta.kind,
            title = meta.title,
            message = meta.message,
            resolution = meta.resolution,
            statusCode = statusCode,
            technicalDetail = responseBody,
        )
    }

    private fun getAuthResolution(providerId: String): String = when (providerId.lowercase()) {
        "google" ->
            "Verify your API key in StellarLang settings and ensure " +
                "Cloud Translation API is enabled in Google Cloud Console."
        "deepl" ->
            "Verify your API key in StellarLang settings (Free keys end with ':fx')."
        else ->
            "Verify your API key in StellarLang settings."
    }

    private fun isQuotaError(statusCode: Int, responseBody: String?): Boolean {
        if (statusCode == HTTP_QUOTA_EXCEEDED) return true
        if (statusCode != HTTP_FORBIDDEN || responseBody == null) return false
        return responseBody.contains("dailyLimit", ignoreCase = true) ||
            responseBody.contains("quota", ignoreCase = true)
    }

    private fun getHttpErrorMeta(
        providerName: String,
        statusCode: Int,
        providerId: String,
        responseBody: String?,
    ): HttpErrorMeta = when {
        statusCode == HTTP_TOO_MANY_REQUESTS -> HttpErrorMeta(
            kind = TranslationErrorKind.RATE_LIMITED,
            title = "$providerName Rate Limit Exceeded (429)",
            message = "$providerName is temporarily rate-limiting requests.",
            resolution = "StellarLang will back off automatically. Avoid rapid messaging or wait a moment.",
        )
        isQuotaError(statusCode, responseBody) -> HttpErrorMeta(
            kind = TranslationErrorKind.QUOTA_EXCEEDED,
            title = "$providerName Quota Exceeded ($statusCode)",
            message = "Monthly or daily character translation quota has been reached.",
            resolution = "Check your $providerName account plan or switch " +
                "translation provider in StellarLang settings.",
        )
        statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN -> HttpErrorMeta(
            kind = TranslationErrorKind.AUTHENTICATION_FAILED,
            title = "$providerName Auth Failed ($statusCode)",
            message = "API key was rejected or unauthorized.",
            resolution = getAuthResolution(providerId),
        )
        statusCode == HTTP_BAD_REQUEST -> HttpErrorMeta(
            kind = TranslationErrorKind.BAD_REQUEST,
            title = "$providerName Bad Request (400)",
            message = "Provider rejected request syntax or formatting.",
            resolution = "Tag handling or syntax error. Plain-text fallback will be attempted.",
        )
        statusCode in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> HttpErrorMeta(
            kind = TranslationErrorKind.SERVER_ERROR,
            title = "$providerName Server Error ($statusCode)",
            message = "Remote translation service is temporarily unavailable.",
            resolution = "Remote server issue. StellarLang will retry using fallback providers.",
        )
        else -> HttpErrorMeta(
            kind = TranslationErrorKind.UNKNOWN,
            title = "$providerName Error ($statusCode)",
            message = "Unexpected HTTP response from translation provider.",
            resolution = "Check logs for details or verify provider configuration.",
        )
    }

    fun classifyException(
        providerId: String,
        throwable: Throwable,
        host: String? = null,
    ): TranslationErrorInfo {
        val providerName = formatProviderName(providerId)
        val cause = generateSequence(throwable) { it.cause }.lastOrNull() ?: throwable
        val effectiveHost = host?.takeIf { it.isNotBlank() } ?: "configured host"

        return when (cause) {
            is ConnectException -> TranslationErrorInfo(
                providerId = providerId,
                kind = TranslationErrorKind.CONNECTION_REFUSED,
                title = "$providerName Connection Refused",
                message = "Could not connect to $providerName at $effectiveHost.",
                resolution = "Ensure your translation server is running or update the API Host in settings.",
                technicalDetail = cause.message,
            )
            is UnknownHostException -> TranslationErrorInfo(
                providerId = providerId,
                kind = TranslationErrorKind.NETWORK_UNAVAILABLE,
                title = "$providerName Host Unreachable",
                message = "Cannot resolve hostname for $providerName ($effectiveHost).",
                resolution = "Check your internet connection or verify the configured API Host URL.",
                technicalDetail = cause.message,
            )
            is SocketTimeoutException, is HttpTimeoutException -> TranslationErrorInfo(
                providerId = providerId,
                kind = TranslationErrorKind.TIMEOUT,
                title = "$providerName Request Timed Out",
                message = "The server took too long to respond.",
                resolution = "The server may be overloaded. The request will be retried automatically.",
                technicalDetail = cause.message,
            )
            else -> TranslationErrorInfo(
                providerId = providerId,
                kind = TranslationErrorKind.UNKNOWN,
                title = "$providerName Request Failed",
                message = "Failed to communicate with $providerName.",
                resolution = "Check StellarLang logs for diagnostic details.",
                technicalDetail = cause.message,
            )
        }
    }

    fun classifyModelNotReady(providerId: String, targetLang: String): TranslationErrorInfo {
        val providerName = formatProviderName(providerId)
        return TranslationErrorInfo(
            providerId = providerId,
            kind = TranslationErrorKind.MODEL_NOT_READY,
            title = "$providerName Model Missing ($targetLang)",
            message = "Local translation model for '$targetLang' is not downloaded.",
            resolution = "Enable 'ONNX Auto Download' or download the model in StellarLang settings.",
        )
    }

    fun formatProviderName(providerId: String): String {
        return when (providerId.lowercase()) {
            "deepl" -> "DeepL"
            "libretranslate" -> "LibreTranslate"
            "onnx" -> "ONNX"
            "google" -> "Google Translate"
            else -> providerId.replaceFirstChar { it.uppercase() }
        }
    }
}
