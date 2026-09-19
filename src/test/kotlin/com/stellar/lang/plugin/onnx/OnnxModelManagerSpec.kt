package com.stellar.lang.plugin.onnx

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginStatus
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OnnxModelManagerSpec : FunSpec({
    lateinit var server: HttpServer
    var serverPort: Int = 0
    val responseCode = AtomicInteger(200)
    val testModelsDir = File("build/test_models_${System.nanoTime()}")

    beforeSpec {
        testModelsDir.mkdirs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/dummy_model.onnx") { exchange ->
            val bytes = "dummy_onnx_content".toByteArray()
            exchange.sendResponseHeaders(responseCode.get(), bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        server.start()
    }

    afterSpec {
        server.stop(0)
        testModelsDir.deleteRecursively()
    }

    beforeEach {
        OnnxModelManager.reset()
        testModelsDir.deleteRecursively()
        testModelsDir.mkdirs()
        responseCode.set(200)
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
        config.onnxModelDir.setValue(testModelsDir.path, false)
    }

    test("OnnxModelManager paths and readiness") {
        OnnxModelManager.getModelsDir().absolutePath shouldBe testModelsDir.absolutePath
        val detModel = OnnxModelManager.getDetectionModelFile()
        detModel.name shouldBe "model.onnx"
        OnnxModelManager.getDetectionVocabFile().name shouldBe "tokenizer.json"
        OnnxModelManager.getTranslationModelFile("fr").name shouldBe "model.onnx"

        OnnxModelManager.isDetectionModelReady() shouldBe false
        OnnxModelManager.isTranslationModelReady("fr") shouldBe false

        OnnxModelManager.getDetectionStatus() shouldBe PluginStatus.NotConfigured("Detection model not downloaded")
        OnnxModelManager.getTranslationStatus("fr") shouldBe
            PluginStatus.NotConfigured("Translation model for 'fr' not downloaded")

        // Create dummy files to test ready state
        detModel.parentFile.mkdirs()
        detModel.writeText("fake onnx")
        OnnxModelManager.isDetectionModelReady() shouldBe true
        OnnxModelManager.getDetectionStatus() shouldNotBe null

        val transModel = OnnxModelManager.getTranslationModelFile("fr")
        transModel.parentFile.mkdirs()
        transModel.writeText("fake onnx")
        OnnxModelManager.isTranslationModelReady("fr") shouldBe true
        OnnxModelManager.getTranslationStatus("fr") shouldBe PluginStatus.Ready("Model for 'fr' ready")

        // Clean up dummy files
        detModel.delete()
        transModel.delete()
    }

    test("downloadTranslationModelAsync downloads file and triggers callbacks") {
        val latch = CountDownLatch(1)
        var downloadedResult: Result<File>? = null
        var lastProgress = 0

        OnnxModelManager.downloadTranslationModelAsync(
            targetLang = "es",
            modelUrl = "http://127.0.0.1:$serverPort/dummy_model.onnx",
            onProgress = { pct -> lastProgress = pct },
            onComplete = { res ->
                downloadedResult = res
                latch.countDown()
            },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        downloadedResult shouldNotBe null
        downloadedResult?.isSuccess shouldBe true
        downloadedResult?.getOrNull()?.exists() shouldBe true
        lastProgress shouldBe 100
        OnnxModelManager.isTranslationModelReady("es") shouldBe true
    }

    test("downloadTranslationModelAsync handles HTTP error properly") {
        responseCode.set(500)
        val latch = CountDownLatch(1)
        var downloadedResult: Result<File>? = null

        OnnxModelManager.downloadTranslationModelAsync(
            targetLang = "de",
            modelUrl = "http://127.0.0.1:$serverPort/dummy_model.onnx",
            onComplete = { res ->
                downloadedResult = res
                latch.countDown()
            },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        downloadedResult shouldNotBe null
        downloadedResult?.isFailure shouldBe true
        OnnxModelManager.isTranslationModelReady("de") shouldBe false
        OnnxModelManager.getTranslationStatus("de") shouldNotBe null
    }

    test("DownloadState properties and transitions") {
        val state = OnnxModelManager.DownloadState("test", 10, true, null)
        state.key shouldBe "test"
        state.progressPercent shouldBe 10
        state.isDownloading shouldBe true
        state.errorMessage shouldBe null

        state.progressPercent = 50
        state.progressPercent shouldBe 50
        state.errorMessage = "Failed"
        state.errorMessage shouldBe "Failed"

        // Overwrite existing file test
        val existingModel = OnnxModelManager.getTranslationModelFile("overwrite")
        existingModel.parentFile.mkdirs()
        existingModel.writeText("pre-existing content")
        val latch = CountDownLatch(1)
        OnnxModelManager.downloadTranslationModelAsync(
            targetLang = "overwrite",
            modelUrl = "http://127.0.0.1:$serverPort/dummy_model.onnx",
            onComplete = { latch.countDown() },
        )
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        existingModel.readText() shouldBe "dummy_onnx_content"
    }

    test("getDetectionStatus and getTranslationStatus handle active and failed downloads") {
        val field = OnnxModelManager::class.java.getDeclaredField("activeDownloads")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(OnnxModelManager) as MutableMap<String, OnnxModelManager.DownloadState>

        map["detection"] = OnnxModelManager.DownloadState("detection", 40, true, null)
        OnnxModelManager.getDetectionStatus() shouldBe PluginStatus.Downloading(40, "Language Detection Model")

        map["detection"] = OnnxModelManager.DownloadState("detection", 0, false, "Model corrupt")
        OnnxModelManager.getDetectionStatus() shouldBe PluginStatus.Error("Model corrupt")

        map["translation-fr"] = OnnxModelManager.DownloadState("translation-fr", 75, true, null)
        OnnxModelManager.getTranslationStatus("fr") shouldBe PluginStatus.Downloading(75, "Translation model (fr)")

        map["translation-fr"] = OnnxModelManager.DownloadState("translation-fr", 0, false, "Connection reset")
        OnnxModelManager.getTranslationStatus("fr") shouldBe PluginStatus.Error("Connection reset")

        map.clear()
    }

    test("downloadDetectionModelAsync success downloads model and vocab") {
        val latch = CountDownLatch(1)
        var downloadSuccess = false
        var progressCalled = false

        OnnxModelManager.downloadDetectionModelAsync(
            modelUrl = "http://127.0.0.1:$serverPort/dummy_model.onnx",
            vocabUrl = "http://127.0.0.1:$serverPort/dummy_model.onnx",
            onProgress = { progressCalled = true },
            onComplete = { res ->
                downloadSuccess = res.isSuccess
                latch.countDown()
            },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        downloadSuccess shouldBe true
        progressCalled shouldBe true
        OnnxModelManager.isDetectionModelReady() shouldBe true
    }

    test("downloadDetectionModelAsync failure completes with failure result") {
        responseCode.set(500)
        val latch = CountDownLatch(1)
        var failedResult: Result<File>? = null

        OnnxModelManager.downloadDetectionModelAsync(
            modelUrl = "http://127.0.0.1:$serverPort/dummy_model.onnx",
            onComplete = { res ->
                failedResult = res
                latch.countDown()
            },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        failedResult?.isFailure shouldBe true
        OnnxModelManager.getDetectionStatus() shouldNotBe null
    }
})
