@file:Suppress(
    "TooGenericExceptionCaught",
    "NestedBlockDepth",
    "MagicNumber",
    "DataClassShouldBeImmutable",
    "LongMethod",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
    "StringLiteralDuplication",
    "LargeClass",
    "TooManyFunctions",
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
    const val RETRY_COOLDOWN_MS: Long = 30_000L
    const val MIN_MODEL_SIZE_BYTES: Long = 1L
    private const val MIN_VALIDATION_SIZE_BYTES: Long = 10_000L
    private const val DEFAULT_BUFFER_SIZE = 8192
    private const val HTML_PROBE_LENGTH = 128
    private const val HTTP_PARTIAL_CONTENT = 206
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    private val logger: Logger = LoggerFactory.getLogger("StellarLang-OnnxModelManager")
    internal var downloadExecutor: java.util.concurrent.ExecutorService = createDownloadExecutor()

    private val defaultHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    internal var httpClientOverride: HttpClient? = null

    val httpClient: HttpClient
        get() = httpClientOverride ?: defaultHttpClient

    // Model URLs
    const val DETECTION_MODEL_URL =
        "https://huggingface.co/onnx-community/language_detection-ONNX/resolve/main/onnx/model_quantized.onnx"
    const val DETECTION_VOCAB_URL =
        "https://huggingface.co/onnx-community/language_detection-ONNX/resolve/main/tokenizer.json"

    // Download state tracking
    private val activeDownloads = ConcurrentHashMap<String, DownloadState>()
    private val failureCooldowns = ConcurrentHashMap<String, Long>()

    fun reset() {
        activeDownloads.clear()
        failureCooldowns.clear()
        httpClientOverride = null
        downloadExecutor.shutdownNow()
        downloadExecutor = createDownloadExecutor()
    }

    private fun createDownloadExecutor(): java.util.concurrent.ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "StellarLang-ModelDownloader").apply { isDaemon = true }
        }

    fun isInCooldown(key: String): Boolean {
        val lastFail = failureCooldowns[key] ?: return false
        val elapsed = System.currentTimeMillis() - lastFail
        if (elapsed < RETRY_COOLDOWN_MS) {
            return true
        }
        failureCooldowns.remove(key)
        return false
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

    fun getTranslationDecoderFile(targetLang: String): File {
        val dir = File(getModelsDir(), "translation/$targetLang")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "decoder.onnx")
    }

    fun getTranslationVocabFile(targetLang: String): File {
        val dir = File(getModelsDir(), "translation/$targetLang")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "tokenizer.json")
    }

    fun isDetectionModelReady(): Boolean {
        val model = getDetectionModelFile()
        return model.exists() && model.length() >= MIN_MODEL_SIZE_BYTES
    }

    fun isDecoderReady(targetLang: String): Boolean {
        val decoder = getTranslationDecoderFile(targetLang)
        return decoder.exists() && decoder.length() >= MIN_MODEL_SIZE_BYTES
    }

    fun isTranslationModelReady(targetLang: String): Boolean {
        val model = getTranslationModelFile(targetLang)
        return model.exists() && model.length() >= MIN_MODEL_SIZE_BYTES
    }

    fun autoDownloadModelsInBackground() {
        val config = ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main") ?: return
        if (!config.onnxAutoDownload.value()) return

        if (!isDetectionModelReady()) {
            downloadDetectionModelAsync()
        }
        val target = config.targetLanguage.value().trim().lowercase()
        if (target.isEmpty() || target == "auto") return
        if (!isTranslationModelReady(target)) {
            downloadTranslationModelAsync(target)
        }
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
            if (isEncoderOnlyModel(targetLang)) {
                PluginStatus.NotConfigured("Translation model for '$targetLang' is encoder-only (decoder required)")
            } else {
                PluginStatus.Ready("Model for '$targetLang' ready")
            }
        } else {
            PluginStatus.NotConfigured("Translation model for '$targetLang' not downloaded")
        }
    }

    fun isEncoderOnlyModel(targetLang: String): Boolean {
        val modelFile = getTranslationModelFile(targetLang)
        return OnnxInferenceEngine.isEnvironmentAvailable() &&
            OnnxInferenceEngine.validateModel(modelFile) &&
            !OnnxInferenceEngine.isTranslationModelGenerative(targetLang) &&
            !isDecoderReady(targetLang)
    }

    fun downloadDetectionModelAsync(
        modelUrl: String? = null,
        vocabUrl: String? = null,
        forceRetry: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: ((Result<File>) -> Unit)? = null,
    ) {
        val key = "detection"
        synchronized(activeDownloads) {
            val existing = activeDownloads[key]
            if (existing != null && existing.isDownloading) {
                return
            }
            if (!forceRetry && isInCooldown(key)) {
                logger.debug("Download for '{}' skipped due to cooldown", key)
                return
            }
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
                    val vocabDest = getDetectionVocabFile()
                    if (!vocabDest.exists()) {
                        val vUrl = vocabUrl ?: DETECTION_VOCAB_URL
                        runCatching { downloadFileWithProgress(vUrl, vocabDest) {} }
                    }
                    state.isDownloading = false
                    state.progressPercent = 100
                    failureCooldowns.remove(key)
                    onDownloadCompleted()
                    destination
                }.onFailure { ex ->
                    state.isDownloading = false
                    state.errorMessage = ex.message ?: "Failed to download model"
                    failureCooldowns[key] = System.currentTimeMillis()
                    logger.error("Failed to download detection model: {}", ex.message)
                }
                onComplete?.invoke(result)
            }
        }
    }

    fun getTranslationModelUrl(targetLang: String): String {
        val lang = targetLang.lowercase().trim()
        val repo = if (lang == "en") "opus-mt-mul-en" else "opus-mt-en-$lang"
        return "https://huggingface.co/onnx-community/$repo/resolve/main/onnx/encoder_model_quantized.onnx"
    }

    fun getTranslationDecoderUrl(targetLang: String): String {
        val lang = targetLang.lowercase().trim()
        val repo = if (lang == "en") "opus-mt-mul-en" else "opus-mt-en-$lang"
        return "https://huggingface.co/onnx-community/$repo/resolve/main/onnx/decoder_model_quantized.onnx"
    }

    fun getTranslationVocabUrl(targetLang: String): String {
        val lang = targetLang.lowercase().trim()
        val repo = if (lang == "en") "opus-mt-mul-en" else "opus-mt-en-$lang"
        return "https://huggingface.co/onnx-community/$repo/resolve/main/tokenizer.json"
    }

    fun downloadTranslationModelAsync(
        targetLang: String,
        modelUrl: String? = null,
        decoderUrl: String? = null,
        vocabUrl: String? = null,
        forceRetry: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: ((Result<File>) -> Unit)? = null,
    ) {
        val key = "translation-$targetLang"
        synchronized(activeDownloads) {
            val existing = activeDownloads[key]
            if (existing != null && existing.isDownloading) {
                return
            }
            if (!forceRetry && isInCooldown(key)) {
                logger.debug("Download for '{}' skipped due to cooldown", key)
                return
            }
            val state = DownloadState(key)
            activeDownloads[key] = state

            downloadExecutor.execute {
                val destination = getTranslationModelFile(targetLang)
                val url = modelUrl ?: getTranslationModelUrl(targetLang)
                val result = runCatching {
                    downloadFileWithProgress(url, destination) { progress ->
                        state.progressPercent = progress
                        onProgress?.invoke(progress)
                    }
                    val dUrl = decoderUrl ?: if (modelUrl == null) getTranslationDecoderUrl(targetLang) else null
                    if (dUrl != null) {
                        val decoderDest = getTranslationDecoderFile(targetLang)
                        if (!decoderDest.exists()) {
                            runCatching { downloadFileWithProgress(dUrl, decoderDest) {} }
                        }
                    }
                    val vUrl = vocabUrl ?: if (modelUrl == null) getTranslationVocabUrl(targetLang) else null
                    if (vUrl != null) {
                        val vocabDest = getTranslationVocabFile(targetLang)
                        if (!vocabDest.exists()) {
                            runCatching { downloadFileWithProgress(vUrl, vocabDest) {} }
                        }
                    }
                    state.isDownloading = false
                    state.progressPercent = 100
                    failureCooldowns.remove(key)
                    onDownloadCompleted()
                    destination
                }.onFailure { ex ->
                    state.isDownloading = false
                    state.errorMessage = ex.message ?: "Failed to download translation model"
                    failureCooldowns[key] = System.currentTimeMillis()
                    logger.error("Failed to download translation model for {}: {}", targetLang, ex.message)
                }
                onComplete?.invoke(result)
            }
        }
    }

    private fun onDownloadCompleted() {
        OnnxInferenceEngine.resetSessions()
        runCatching {
            com.stellar.lang.service.TranslationCache.clear()
            com.stellar.lang.sign.SignTranslationManager.clearCache()
            com.stellar.lang.item.ItemTranslationManager.clearCache()
            com.stellar.lang.entity.EntityTranslationManager.clearCache()
            com.stellar.lang.book.BookTranslationManager.clearCache()
            com.stellar.lang.service.TranslationService.retryFailedTranslations()
        }
    }

    @Suppress("ThrowsCount")
    private fun downloadFileWithProgress(
        urlStr: String,
        targetFile: File,
        progressCallback: (Int) -> Unit,
    ) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            var response = executeDownloadRequest(urlStr, tempFile)
            if (response.statusCode() == HTTP_RANGE_NOT_SATISFIABLE) {
                if (tempFile.exists()) tempFile.delete()
                response = executeDownloadRequest(urlStr, tempFile)
            }

            val statusCode = response.statusCode()
            if (statusCode !in 200..299) {
                error("HTTP error $statusCode while downloading from $urlStr")
            }

            val isPartial = statusCode == HTTP_PARTIAL_CONTENT
            val initialBytes = if (isPartial && tempFile.exists()) tempFile.length() else 0L
            val serverContentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            val totalExpected = if (serverContentLength > 0) initialBytes + serverContentLength else -1L

            var totalBytesRead = initialBytes
            response.body().use { input ->
                FileOutputStream(tempFile, isPartial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (totalExpected > 0) {
                            val percent = (totalBytesRead * 100 / totalExpected).toInt().coerceIn(0, 99)
                            progressCallback(percent)
                        }
                        bytesRead = input.read(buffer)
                    }
                }
            }

            verifyAndPromoteFile(tempFile, targetFile)
            progressCallback(100)
        } catch (ex: Exception) {
            // Keep partial file for range resume unless it is an invalid/corrupted format
            if (tempFile.exists() && isHtmlOrInvalid(tempFile)) {
                tempFile.delete()
            }
            throw ex
        }
    }

    private fun executeDownloadRequest(urlStr: String, tempFile: File): HttpResponse<java.io.InputStream> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(urlStr))
            .timeout(Duration.ofSeconds(30))
            .GET()

        if (tempFile.exists() && tempFile.length() > 0) {
            requestBuilder.header("Range", "bytes=${tempFile.length()}-")
        }

        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun isHtmlOrInvalid(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return true
        return try {
            val probeBytes = ByteArray(HTML_PROBE_LENGTH)
            val read = file.inputStream().use { it.read(probeBytes) }
            if (read > 0) {
                val header = String(probeBytes, 0, read, Charsets.UTF_8).lowercase()
                header.contains("<!doctype") || header.contains("<html")
            } else {
                true
            }
        } catch (ex: Exception) {
            logger.trace("Failed to probe file for HTML headers", ex)
            false
        }
    }

    private fun isInvalidOnnxModel(file: File): Boolean {
        if (file.length() < MIN_VALIDATION_SIZE_BYTES) return false
        return OnnxInferenceEngine.isEnvironmentAvailable() && !OnnxInferenceEngine.validateModel(file)
    }

    private fun verifyAndPromoteFile(tempFile: File, targetFile: File) {
        if (!tempFile.exists() || tempFile.length() < MIN_MODEL_SIZE_BYTES) {
            if (tempFile.exists()) tempFile.delete()
            error("Downloaded file is empty")
        }
        if (isHtmlOrInvalid(tempFile)) {
            tempFile.delete()
            error("Downloaded payload is an HTML document or invalid error response")
        }
        if (targetFile.name.endsWith(".onnx") && isInvalidOnnxModel(tempFile)) {
            tempFile.delete()
            error("ONNX model validation failed for downloaded file")
        }
        java.nio.file.Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
