package com.stellar.lang.error

import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.network.chat.Component
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException

class TranslationErrorNotifierSpec : FunSpec({
    beforeEach {
        TranslationErrorNotifier.reset()
        TranslationErrorNotifier.toastDispatcher = null
        val config = TranslationService.getConfig()
        config.showErrorToasts.setValue(true, false)
    }

    test("classifyHttpStatus identifies 429, 456, 401, 403, 400, 500, and unknown") {
        val err429 = TranslationErrorClassifier.classifyHttpStatus("deepl", 429)
        err429.kind shouldBe TranslationErrorKind.RATE_LIMITED
        err429.title shouldContain "Rate Limit"
        err429.resolution shouldContain "back off"

        val err456 = TranslationErrorClassifier.classifyHttpStatus("deepl", 456)
        err456.kind shouldBe TranslationErrorKind.QUOTA_EXCEEDED
        err456.title shouldContain "Quota Exceeded"
        err456.resolution shouldContain "account plan"

        val err401 = TranslationErrorClassifier.classifyHttpStatus("deepl", 401)
        err401.kind shouldBe TranslationErrorKind.AUTHENTICATION_FAILED
        err401.resolution shouldContain ":fx"

        val err403 = TranslationErrorClassifier.classifyHttpStatus("libretranslate", 403)
        err403.kind shouldBe TranslationErrorKind.AUTHENTICATION_FAILED
        err403.resolution shouldContain "API key"

        val err400 = TranslationErrorClassifier.classifyHttpStatus("deepl", 400)
        err400.kind shouldBe TranslationErrorKind.BAD_REQUEST
        err400.resolution shouldContain "Plain-text"

        val err503 = TranslationErrorClassifier.classifyHttpStatus("libretranslate", 503)
        err503.kind shouldBe TranslationErrorKind.SERVER_ERROR
        err503.resolution shouldContain "fallback"

        val err418 = TranslationErrorClassifier.classifyHttpStatus("deepl", 418)
        err418.kind shouldBe TranslationErrorKind.UNKNOWN
    }

    test("classifyException identifies ConnectException, UnknownHostException, Timeout, and generic") {
        val connectErr = TranslationErrorClassifier.classifyException(
            "libretranslate",
            ConnectException("Connection refused"),
            host = "http://localhost:5000",
        )
        connectErr.kind shouldBe TranslationErrorKind.CONNECTION_REFUSED
        connectErr.message shouldContain "localhost:5000"
        connectErr.resolution shouldContain "server is running"

        val hostErr = TranslationErrorClassifier.classifyException(
            "deepl",
            UnknownHostException("api.deepl.com"),
            host = "api.deepl.com",
        )
        hostErr.kind shouldBe TranslationErrorKind.NETWORK_UNAVAILABLE
        hostErr.resolution shouldContain "internet connection"

        val timeoutErr = TranslationErrorClassifier.classifyException(
            "deepl",
            SocketTimeoutException("Read timed out"),
        )
        timeoutErr.kind shouldBe TranslationErrorKind.TIMEOUT
        timeoutErr.resolution shouldContain "retried automatically"

        val httpTimeoutErr = TranslationErrorClassifier.classifyException(
            "libretranslate",
            HttpTimeoutException("request timed out"),
        )
        httpTimeoutErr.kind shouldBe TranslationErrorKind.TIMEOUT

        val genericErr = TranslationErrorClassifier.classifyException(
            "deepl",
            IllegalStateException("Unexpected state"),
        )
        genericErr.kind shouldBe TranslationErrorKind.UNKNOWN
    }

    test("classifyModelNotReady generates actionable model guidance") {
        val modelErr = TranslationErrorClassifier.classifyModelNotReady("onnx", "no")
        modelErr.kind shouldBe TranslationErrorKind.MODEL_NOT_READY
        modelErr.message shouldContain "'no'"
        modelErr.resolution shouldContain "ONNX Auto Download"
    }

    afterEach {
        TranslationErrorNotifier.toastDispatcher = null
    }

    test("notifyErrorOnce dispatches toast exactly once per error kind and provider") {
        var toastCount = 0
        var lastTitle: String? = null
        var lastDesc: String? = null

        TranslationErrorNotifier.toastDispatcher = { title: Component, desc: Component ->
            if (title.string.startsWith("MockDeepL") || title.string.startsWith("MockLibre")) {
                toastCount++
                lastTitle = title.string
                lastDesc = desc.string
            }
        }

        val deeplRateLimit = TranslationErrorInfo(
            providerId = "mock-deepl",
            kind = TranslationErrorKind.RATE_LIMITED,
            title = "MockDeepL Rate Limit Exceeded (429)",
            message = "DeepL is rate limiting requests.",
            resolution = "Backing off automatically.",
        )

        // First notification should trigger toast
        TranslationErrorNotifier.notifyErrorOnce(deeplRateLimit)
        toastCount shouldBe 1
        lastTitle shouldContain "MockDeepL Rate Limit"
        lastDesc shouldContain "Backing off"
        TranslationErrorNotifier.hasNotified("mock-deepl", TranslationErrorKind.RATE_LIMITED) shouldBe true

        // Second notification with SAME provider and error kind should be ignored
        TranslationErrorNotifier.notifyErrorOnce(deeplRateLimit)
        toastCount shouldBe 1

        // Different error kind on same provider should trigger toast once
        val deeplQuota = TranslationErrorInfo(
            providerId = "mock-deepl",
            kind = TranslationErrorKind.QUOTA_EXCEEDED,
            title = "MockDeepL Quota Exceeded (456)",
            message = "Monthly quota reached.",
            resolution = "Upgrade plan.",
        )
        TranslationErrorNotifier.notifyErrorOnce(deeplQuota)
        toastCount shouldBe 2

        // Different provider with same error kind should trigger toast once
        val libreRateLimit = TranslationErrorInfo(
            providerId = "mock-libre",
            kind = TranslationErrorKind.RATE_LIMITED,
            title = "MockLibre Rate Limit (429)",
            message = "Rate limit reached.",
            resolution = "Wait a bit.",
        )
        TranslationErrorNotifier.notifyErrorOnce(libreRateLimit)
        toastCount shouldBe 3

        // After reset, same error can be notified again
        TranslationErrorNotifier.reset()
        TranslationErrorNotifier.hasNotified("mock-deepl", TranslationErrorKind.RATE_LIMITED) shouldBe false
        TranslationErrorNotifier.notifyErrorOnce(deeplRateLimit)
        toastCount shouldBe 4
    }

    test("notifyErrorOnce respects showErrorToasts config") {
        var toastCount = 0
        TranslationErrorNotifier.toastDispatcher = { title, _ ->
            if (title.string.startsWith("MockConfigTest")) {
                toastCount++
            }
        }

        val config = TranslationService.getConfig()
        config.showErrorToasts.setValue(false, false)

        val err = TranslationErrorInfo(
            providerId = "mock-config-test",
            kind = TranslationErrorKind.RATE_LIMITED,
            title = "MockConfigTest Rate Limit",
            message = "Rate limited.",
            resolution = "Wait.",
        )

        TranslationErrorNotifier.notifyErrorOnce(err)
        toastCount shouldBe 0
        TranslationErrorNotifier.hasNotified("mock-config-test", TranslationErrorKind.RATE_LIMITED) shouldBe true
    }

    test("notifyErrorOnce handles dispatch without custom dispatcher and with technicalDetail") {
        TranslationErrorNotifier.toastDispatcher = null
        val config = TranslationService.getConfig()
        config.showErrorToasts.setValue(true, false)

        val errWithDetails = TranslationErrorInfo(
            providerId = "mock-dispatch-test",
            kind = TranslationErrorKind.SERVER_ERROR,
            title = "Mock Server Error (500)",
            message = "Internal Server Error",
            resolution = "Retry later.",
            statusCode = 500,
            technicalDetail = "Gateway timeout at upstream proxy",
        )

        TranslationErrorNotifier.notifyErrorOnce(errWithDetails)
        TranslationErrorNotifier.hasNotified("mock-dispatch-test", TranslationErrorKind.SERVER_ERROR) shouldBe true

        val copy = errWithDetails.copy(title = "Updated Title")
        copy.title shouldBe "Updated Title"
        copy.statusCode shouldBe 500
        copy.technicalDetail shouldBe "Gateway timeout at upstream proxy"
    }

    test("formatProviderName formats known and custom providers") {
        TranslationErrorClassifier.formatProviderName("deepl") shouldBe "DeepL"
        TranslationErrorClassifier.formatProviderName("libretranslate") shouldBe "LibreTranslate"
        TranslationErrorClassifier.formatProviderName("onnx") shouldBe "ONNX"
        TranslationErrorClassifier.formatProviderName("custom") shouldBe "Custom"
    }

    test("classifyException handles wrapped cause chain and blank host") {
        val wrapped = RuntimeException("Outer error", ConnectException("Inner refused"))
        val info = TranslationErrorClassifier.classifyException("custom", wrapped, "  ")
        info.kind shouldBe TranslationErrorKind.CONNECTION_REFUSED
        info.message shouldContain "configured host"
    }
})
