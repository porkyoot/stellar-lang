@file:Suppress(
    "TooGenericExceptionCaught",
    "NestedBlockDepth",
    "MagicNumber",
    "DataClassShouldBeImmutable",
    "LongMethod",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
    "StringLiteralDuplication",
)

package com.stellar.lang.plugin.onnx

import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginStatus
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Manages downloading, verification, and storage of ONNX models.
 */
object OnnxModelManager {
    private const val DEFAULT_BUFFER_SIZE = 8192

    private val logger: Logger = LoggerFactory.getLogger("StellarLang-OnnxModelManager")
    private val downloadExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "StellarLang-ModelDownloader").apply { isDaemon = true }
    }

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // Model URLs
    const val DETECTION_MODEL_URL =
        "https://huggingface.co/onnx-community/language_detection-ONNX/resolve/main/onnx/model_quantized.onnx"
    const val DETECTION_VOCAB_URL =
        "https://huggingface.co/onnx-community/language_detection-ONNX/resolve/main/tokenizer.json"

    // Download state tracking
    private val activeDownloads = ConcurrentHashMap<String, DownloadState>()

    fun reset() {
        activeDownloads.clear()
    }

    data class DownloadState(
        val key: String,
        var progressPercent: Int = 0,
        var isDownloading: Boolean = true,
        var errorMessage: String? = null,
    )

    fun getModelsDir(): File {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
        val dirPath = config?.onnxModelDir?.value() ?: "config/stellar_lang/models"
        val dir = File(dirPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getDetectionModelFile(): File {
        val dir = File(getModelsDir(), "detection")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "model.onnx")
    }

    fun getDetectionVocabFile(): File {
        val dir = File(getModelsDir(), "detection")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "tokenizer.json")
    }

    fun getTranslationModelFile(targetLang: String): File {
        val dir = File(getModelsDir(), "translation/$targetLang")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "model.onnx")
    }

    fun isDetectionModelReady(): Boolean {
        val model = getDetectionModelFile()
        return model.exists() && model.length() > 0
    }

    fun isTranslationModelReady(targetLang: String): Boolean {
        val model = getTranslationModelFile(targetLang)
        return model.exists() && model.length() > 0
    }

    fun getDetectionStatus(): PluginStatus {
        val state = activeDownloads["detection"]
        if (state != null && state.isDownloading) {
            return PluginStatus.Downloading(state.progressPercent, "Language Detection Model")
        }
        if (state?.errorMessage != null) {
            return PluginStatus.Error(state.errorMessage ?: "Download failed")
        }
        return if (isDetectionModelReady()) {
            PluginStatus.Ready("Detection model loaded (${getDetectionModelFile().length() / 1024} KB)")
        } else {
            PluginStatus.NotConfigured("Detection model not downloaded")
        }
    }

    fun getTranslationStatus(targetLang: String): PluginStatus {
        val key = "translation-$targetLang"
        val state = activeDownloads[key]
        if (state != null && state.isDownloading) {
            return PluginStatus.Downloading(state.progressPercent, "Translation model ($targetLang)")
        }
        if (state?.errorMessage != null) {
            return PluginStatus.Error(state.errorMessage ?: "Download failed")
        }
        return if (isTranslationModelReady(targetLang)) {
            PluginStatus.Ready("Model for '$targetLang' ready")
        } else {
            PluginStatus.NotConfigured("Translation model for '$targetLang' not downloaded")
        }
    }

    fun downloadDetectionModelAsync(
        modelUrl: String? = null,
        vocabUrl: String? = null,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: ((Result<File>) -> Unit)? = null,
    ) {
        val key = "detection"
        val state = DownloadState(key)
        activeDownloads[key] = state

        downloadExecutor.execute {
            val destination = getDetectionModelFile()
            val url = modelUrl ?: DETECTION_MODEL_URL
            val result = runCatching {
                downloadFileWithProgress(url, destination) { progress ->
                    state.progressPercent = progress
                    onProgress?.invoke(progress)
                }
                // Also fetch vocab if missing
                val vocabDest = getDetectionVocabFile()
                if (!vocabDest.exists()) {
                    val vUrl = vocabUrl ?: DETECTION_VOCAB_URL
                    runCatching { downloadFileWithProgress(vUrl, vocabDest) {} }
                }
                state.isDownloading = false
                state.progressPercent = 100
                destination
            }.onFailure { ex ->
                state.isDownloading = false
                state.errorMessage = ex.message ?: "Failed to download model"
                logger.error("Failed to download detection model: {}", ex.message)
            }
            onComplete?.invoke(result)
        }
    }

    fun downloadTranslationModelAsync(
        targetLang: String,
        modelUrl: String? = null,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: ((Result<File>) -> Unit)? = null,
    ) {
        val key = "translation-$targetLang"
        val state = DownloadState(key)
        activeDownloads[key] = state

        downloadExecutor.execute {
            val destination = getTranslationModelFile(targetLang)
            val url = modelUrl
                ?: "https://huggingface.co/onnx-community/opus-mt-mul-en/resolve/main/onnx/model_quantized.onnx"
            val result = runCatching {
                downloadFileWithProgress(url, destination) { progress ->
                    state.progressPercent = progress
                    onProgress?.invoke(progress)
                }
                state.isDownloading = false
                state.progressPercent = 100
                destination
            }.onFailure { ex ->
                state.isDownloading = false
                state.errorMessage = ex.message ?: "Failed to download translation model"
                logger.error("Failed to download translation model for {}: {}", targetLang, ex.message)
            }
            onComplete?.invoke(result)
        }
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        targetFile: File,
        progressCallback: (Int) -> Unit,
    ) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val statusCode = response.statusCode()
            if (statusCode !in 200..299) {
                error("HTTP error $statusCode while downloading from $urlStr")
            }

            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            var totalBytesRead = 0L

            response.body().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val percent = (totalBytesRead * 100 / contentLength).toInt().coerceIn(0, 99)
                            progressCallback(percent)
                        }
                        bytesRead = input.read(buffer)
                    }
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            progressCallback(100)
        } catch (ex: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw ex
        }
    }
}
