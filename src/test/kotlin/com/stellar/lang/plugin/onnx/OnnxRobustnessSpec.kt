@file:Suppress("MagicNumber", "LargeClass", "LongMethod")

package com.stellar.lang.plugin.onnx

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginStatus
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OnnxRobustnessSpec : FunSpec({
    val testDir = File("build/robust_test_models_${System.nanoTime()}")

    beforeSpec {
        testDir.mkdirs()
    }

    afterSpec {
        testDir.deleteRecursively()
        OnnxModelManager.reset()
        OnnxInferenceEngine.resetSessions()
    }

    beforeEach {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
        config.onnxModelDir.setValue(testDir.path, false)
        config.targetLanguage.setValue("es", false)
        config.onnxAutoDownload.setValue(false, false)
        OnnxModelManager.reset()
    }

    test("connection failure sets error status and cooldown") {
        val latch = CountDownLatch(1)
        var failureOccurred = false

        OnnxModelManager.downloadDetectionModelAsync(
            modelUrl = "http://127.0.0.1:1/model.onnx",
            vocabUrl = "http://127.0.0.1:1/tokenizer.json",
            forceRetry = true,
            onComplete = { result ->
                failureOccurred = result.isFailure
                latch.countDown()
            },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        failureOccurred shouldBe true
        OnnxModelManager.isInCooldown("detection") shouldBe true
        val status = OnnxModelManager.getDetectionStatus()
        status.shouldBeInstanceOf<PluginStatus.Error>()
    }

    test("cooldown prevents duplicate downloads unless forced") {
        val latch = CountDownLatch(1)
        OnnxModelManager.downloadDetectionModelAsync(
            modelUrl = "http://127.0.0.1:1/model.onnx",
            forceRetry = true,
            onComplete = { latch.countDown() },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        OnnxModelManager.isInCooldown("detection") shouldBe true

        var called = false
        OnnxModelManager.downloadDetectionModelAsync(
            modelUrl = "http://127.0.0.1:1/model.onnx",
            forceRetry = false,
            onComplete = { called = true },
        )
        Thread.sleep(100)
        called shouldBe false
    }

    test("autoDownloadModelsInBackground triggers downloads when enabled") {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")!!
        config.onnxAutoDownload.setValue(false, false)
        OnnxModelManager.autoDownloadModelsInBackground()
        OnnxModelManager.getDetectionStatus().shouldBeInstanceOf<PluginStatus.NotConfigured>()

        config.onnxAutoDownload.setValue(true, false)
        OnnxModelManager.autoDownloadModelsInBackground()
        val detStatus = OnnxModelManager.getDetectionStatus()
        detStatus.shouldBeInstanceOf<PluginStatus.Downloading>()
        detStatus.message shouldContain "Language Detection"

        val transStatus = OnnxModelManager.getTranslationStatus("es")
        transStatus.shouldBeInstanceOf<PluginStatus.Downloading>()

        OnnxModelManager.reset()
    }

    test("server returning 500 error marks download failed") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.onnx") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.close()
        }
        server.start()

        val port = server.address.port
        val latch = CountDownLatch(1)
        var isFailure = false

        try {
            OnnxModelManager.downloadDetectionModelAsync(
                modelUrl = "http://127.0.0.1:$port/model.onnx",
                forceRetry = true,
                onComplete = { result ->
                    isFailure = result.isFailure
                    latch.countDown()
                },
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            isFailure shouldBe true
            OnnxModelManager.getDetectionStatus().shouldBeInstanceOf<PluginStatus.Error>()
        } finally {
            server.stop(0)
        }
    }

    test("server returning HTML error page is rejected as invalid model") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val htmlContent = "<!DOCTYPE html><html><body>Error 404 Not Found</body></html>".toByteArray()
        server.createContext("/html_model.onnx") { exchange ->
            exchange.sendResponseHeaders(200, htmlContent.size.toLong())
            exchange.responseBody.write(htmlContent)
            exchange.responseBody.close()
        }
        server.start()

        val port = server.address.port
        val latch = CountDownLatch(1)
        var isFailure = false

        try {
            OnnxModelManager.downloadDetectionModelAsync(
                modelUrl = "http://127.0.0.1:$port/html_model.onnx",
                forceRetry = true,
                onComplete = { result ->
                    isFailure = result.isFailure
                    latch.countDown()
                },
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            isFailure shouldBe true
            OnnxModelManager.isDetectionModelReady() shouldBe false
        } finally {
            server.stop(0)
        }
    }

    test("server with HTTP Range supports resuming partial download") {
        val sampleModelFile = File("src/test/resources/test_models/detection/model.onnx")
        val modelBytes = sampleModelFile.readBytes()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/resumable_model.onnx") { exchange ->
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val start = rangeHeader.removePrefix("bytes=").removeSuffix("-").toInt()
                val chunk = modelBytes.copyOfRange(start, modelBytes.size)
                exchange.sendResponseHeaders(206, chunk.size.toLong())
                exchange.responseBody.write(chunk)
                exchange.responseBody.close()
            } else {
                exchange.sendResponseHeaders(200, modelBytes.size.toLong())
                exchange.responseBody.write(modelBytes)
                exchange.responseBody.close()
            }
        }
        server.createContext("/tokenizer.json") { exchange ->
            val empty = ByteArray(0)
            exchange.sendResponseHeaders(200, empty.size.toLong())
            exchange.responseBody.close()
        }
        server.start()

        val port = server.address.port
        val targetModel = OnnxModelManager.getDetectionModelFile()
        val tempFile = File(targetModel.parentFile, "${targetModel.name}.tmp")
        tempFile.parentFile.mkdirs()
        // Simulate pre-existing partial file
        tempFile.writeBytes(modelBytes.copyOfRange(0, 200))

        val latch = CountDownLatch(1)
        var isSuccess = false

        try {
            OnnxModelManager.downloadDetectionModelAsync(
                modelUrl = "http://127.0.0.1:$port/resumable_model.onnx",
                vocabUrl = "http://127.0.0.1:$port/tokenizer.json",
                forceRetry = true,
                onComplete = { result ->
                    isSuccess = result.isSuccess
                    latch.countDown()
                },
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            isSuccess shouldBe true
            OnnxModelManager.isDetectionModelReady() shouldBe true
            targetModel.length() shouldBe modelBytes.size.toLong()
        } finally {
            server.stop(0)
        }
    }

    test("server returning 416 resets and completes fresh download") {
        val sampleModelFile = File("src/test/resources/test_models/detection/model.onnx")
        val modelBytes = sampleModelFile.readBytes()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/range_416_model.onnx") { exchange ->
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader != null) {
                exchange.sendResponseHeaders(416, 0)
                exchange.responseBody.close()
            } else {
                exchange.sendResponseHeaders(200, modelBytes.size.toLong())
                exchange.responseBody.write(modelBytes)
                exchange.responseBody.close()
            }
        }
        server.createContext("/tokenizer.json") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()

        val port = server.address.port
        val targetModel = OnnxModelManager.getDetectionModelFile()
        val tempFile = File(targetModel.parentFile, "${targetModel.name}.tmp")
        tempFile.parentFile.mkdirs()
        tempFile.writeBytes(ByteArray(10_000)) // invalid range bytes

        val latch = CountDownLatch(1)
        var isSuccess = false

        try {
            OnnxModelManager.downloadDetectionModelAsync(
                modelUrl = "http://127.0.0.1:$port/range_416_model.onnx",
                vocabUrl = "http://127.0.0.1:$port/tokenizer.json",
                forceRetry = true,
                onComplete = { result ->
                    isSuccess = result.isSuccess
                    latch.countDown()
                },
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            isSuccess shouldBe true
            OnnxModelManager.isDetectionModelReady() shouldBe true
            targetModel.length() shouldBe modelBytes.size.toLong()
        } finally {
            server.stop(0)
        }
    }

    test("downloadTranslationModelAsync completes successfully with valid server") {
        val sampleModelFile = File("src/test/resources/test_models/translation/es/model.onnx")
        val modelBytes = sampleModelFile.readBytes()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/trans_model.onnx") { exchange ->
            exchange.sendResponseHeaders(200, modelBytes.size.toLong())
            exchange.responseBody.write(modelBytes)
            exchange.responseBody.close()
        }
        server.start()

        val port = server.address.port
        val latch = CountDownLatch(1)
        var isSuccess = false

        try {
            OnnxModelManager.downloadTranslationModelAsync(
                targetLang = "es",
                modelUrl = "http://127.0.0.1:$port/trans_model.onnx",
                forceRetry = true,
                onComplete = { result ->
                    isSuccess = result.isSuccess
                    latch.countDown()
                },
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            isSuccess shouldBe true
            OnnxModelManager.isTranslationModelReady("es") shouldBe true
        } finally {
            server.stop(0)
        }
    }

    test("server returning corrupted ONNX model payload is rejected by validation") {
        val corruptedBytes = ByteArray(15_000) { it.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/corrupt_large.onnx") { exchange ->
            exchange.sendResponseHeaders(200, corruptedBytes.size.toLong())
            exchange.responseBody.write(corruptedBytes)
            exchange.close()
        }
        server.start()

        val port = server.address.port
        val latch = CountDownLatch(1)
        var isFailure = false

        try {
            OnnxModelManager.downloadTranslationModelAsync(
                targetLang = "it",
                modelUrl = "http://127.0.0.1:$port/corrupt_large.onnx",
                forceRetry = true,
                onComplete = { result ->
                    isFailure = result.isFailure
                    latch.countDown()
                },
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            isFailure shouldBe true
            OnnxModelManager.isTranslationModelReady("it") shouldBe false
        } finally {
            server.stop(0)
        }
    }
})
