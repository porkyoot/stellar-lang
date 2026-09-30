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
    "ReturnCount",
)

package com.stellar.lang.plugin.onnx

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/**
 * Manages detection, downloading, extraction, and verification of ONNX Runtime native binaries.
 */
object OnnxNativeManager {
    const val DEFAULT_JAR_URL =
        "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.20.0/onnxruntime-1.20.0.jar"
    const val RETRY_COOLDOWN_MS: Long = 30_000L
    private const val DEFAULT_BUFFER_SIZE = 8192
    private const val TAIL_SCAN_BYTES = 65_536
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CD_ENTRY_SIGNATURE = 0x02014b50
    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
    private const val HTTP_PARTIAL_CONTENT = 206
    private const val COMPRESSION_DEFLATE = 8
    private const val COMPRESSION_STORE = 0

    private val logger: Logger = LoggerFactory.getLogger("StellarLang-OnnxNativeManager")
    internal var downloadExecutor: java.util.concurrent.ExecutorService = createDownloadExecutor()

    private val defaultHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    internal var httpClientOverride: HttpClient? = null

    val httpClient: HttpClient
        get() = httpClientOverride ?: defaultHttpClient

    // Download state tracking
    internal val activeDownloads = ConcurrentHashMap<String, OnnxModelManager.DownloadState>()
    internal val failureCooldowns = ConcurrentHashMap<String, Long>()

    val currentPlatform: String
        get() = "${detectOs()}-${detectArch()}"

    fun reset() {
        activeDownloads.clear()
        failureCooldowns.clear()
        httpClientOverride = null
        downloadExecutor.shutdownNow()
        downloadExecutor = createDownloadExecutor()
    }

    private fun createDownloadExecutor(): java.util.concurrent.ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "StellarLang-NativeDownloader").apply { isDaemon = true }
        }

    fun detectOs(): String {
        val os = System.getProperty("os.name", "generic").lowercase(Locale.ENGLISH)
        return when {
            os.contains("mac") || os.contains("darwin") -> "osx"
            os.contains("win") -> "win"
            os.contains("nux") -> "linux"
            else -> "linux"
        }
    }

    fun detectArch(): String {
        val arch = System.getProperty("os.arch", "generic").lowercase(Locale.ENGLISH)
        return when {
            arch.startsWith("amd64") || arch.startsWith("x86_64") -> "x64"
            arch.startsWith("aarch64") || arch.startsWith("arm64") -> "aarch64"
            arch.startsWith("x86") -> "x86"
            else -> "x64"
        }
    }

    fun getRequiredFiles(platform: String = currentPlatform): List<String> = when {
        platform.startsWith("win") -> listOf("onnxruntime.dll", "onnxruntime4j_jni.dll")
        platform.startsWith("osx") -> listOf("libonnxruntime.dylib", "libonnxruntime4j_jni.dylib")
        else -> listOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
    }

    fun getNativesDir(platform: String = currentPlatform): File {
        val dir = File(OnnxModelManager.getModelsDir().parentFile, "natives/$platform")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun isNativeLibraryReady(platform: String = currentPlatform): Boolean {
        val dir = getNativesDir(platform)
        val required = getRequiredFiles(platform)
        return required.all { name ->
            val file = File(dir, name)
            file.exists() && file.length() > 1024L
        }
    }

    fun ensureNativeLibrariesReady(platform: String = currentPlatform): Boolean {
        if (isNativeLibraryReady(platform)) {
            val dir = getNativesDir(platform)
            System.setProperty("onnxruntime.native.path", dir.absolutePath)
            return true
        }
        return false
    }

    fun isInCooldown(key: String = "natives"): Boolean {
        val lastFail = failureCooldowns[key] ?: return false
        val elapsed = System.currentTimeMillis() - lastFail
        if (elapsed < RETRY_COOLDOWN_MS) {
            return true
        }
        failureCooldowns.remove(key)
        return false
    }

    fun getDownloadState(): OnnxModelManager.DownloadState? = activeDownloads["natives"]

    fun getNativeStatus(): com.stellar.lang.plugin.PluginStatus {
        val state = getDownloadState()
        if (state != null && state.isDownloading) {
            return com.stellar.lang.plugin.PluginStatus.Downloading(state.progressPercent, "ONNX Native Runtime")
        }
        if (state?.errorMessage != null) {
            return com.stellar.lang.plugin.PluginStatus.Error(state.errorMessage ?: "Native download failed")
        }
        return if (isNativeLibraryReady()) {
            com.stellar.lang.plugin.PluginStatus.Ready("Native runtime ready")
        } else {
            com.stellar.lang.plugin.PluginStatus.NotConfigured("Native runtime not downloaded")
        }
    }

    fun downloadNativeLibrariesAsync(
        jarUrl: String? = null,
        forceRetry: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
        onComplete: ((Result<List<File>>) -> Unit)? = null,
    ) {
        val key = "natives"
        synchronized(activeDownloads) {
            val existing = activeDownloads[key]
            if (existing != null && existing.isDownloading) {
                return
            }
            if (!forceRetry && isInCooldown(key)) {
                logger.debug("Native library download skipped due to cooldown")
                return
            }
            val state = OnnxModelManager.DownloadState(key)
            activeDownloads[key] = state

            downloadExecutor.execute {
                val url = jarUrl ?: DEFAULT_JAR_URL
                val result = runCatching {
                    val extracted = downloadNativeLibraries(url) { progress ->
                        state.progressPercent = progress
                        onProgress?.invoke(progress)
                    }
                    state.isDownloading = false
                    state.progressPercent = 100
                    failureCooldowns.remove(key)
                    ensureNativeLibrariesReady()
                    OnnxInferenceEngine.resetEnvironment()
                    extracted
                }.onFailure { ex ->
                    state.isDownloading = false
                    state.errorMessage = ex.message ?: "Failed to download native runtime libraries"
                    failureCooldowns[key] = System.currentTimeMillis()
                    logger.error("Failed to download ONNX native runtime libraries: {}", ex.message)
                }
                onComplete?.invoke(result)
            }
        }
    }

    fun downloadNativeLibraries(
        jarUrl: String = DEFAULT_JAR_URL,
        progressCallback: (Int) -> Unit = {},
    ): List<File> {
        val platform = currentPlatform
        logger.info("Downloading ONNX Runtime native libraries for platform '{}' from {}", platform, jarUrl)

        // Try selective range-based extraction first
        return runCatching {
            extractViaHttpRange(jarUrl, progressCallback)
        }.getOrElse { rangeEx ->
            logger.warn("Range-based download failed ({}), falling back to streaming zip extraction", rangeEx.message)
            extractViaStreamingZip(jarUrl, progressCallback)
        }
    }

    private data class ZipEntryInfo(
        val name: String,
        val method: Int,
        val compSize: Long,
        val uncompSize: Long,
        val localHeaderOffset: Long,
    )

    private fun extractViaHttpRange(
        jarUrl: String,
        progressCallback: (Int) -> Unit,
    ): List<File> {
        val targetDir = getNativesDir()
        val platform = currentPlatform
        val requiredNames = getRequiredFiles(platform)

        // 1. Get jar content length
        val headReq = HttpRequest.newBuilder()
            .uri(URI.create(jarUrl))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val headResp = httpClient.send(headReq, HttpResponse.BodyHandlers.discarding())
        val fileLength = headResp.headers().firstValueAsLong("Content-Length").orElse(-1L)
        if (fileLength <= 0) {
            error("Cannot determine Content-Length for $jarUrl")
        }

        // 2. Fetch tail to locate EOCD
        val tailLen = TAIL_SCAN_BYTES.toLong().coerceAtMost(fileLength)
        val tailBytes = fetchRange(jarUrl, fileLength - tailLen, fileLength - 1)
        val tailBuffer = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)

        var eocdPos = -1
        for (i in tailBytes.size - 22 downTo 0) {
            if (tailBuffer.getInt(i) == EOCD_SIGNATURE) {
                eocdPos = i
                break
            }
        }
        if (eocdPos == -1) {
            error("Could not find ZIP EOCD signature")
        }

        val cdSize = tailBuffer.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = tailBuffer.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL

        // 3. Fetch Central Directory
        val cdBytes = fetchRange(jarUrl, cdOffset, cdOffset + cdSize - 1)
        val cdBuffer = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)

        val entries = mutableMapOf<String, ZipEntryInfo>()
        var pos = 0
        while (pos < cdBytes.size) {
            if (cdBuffer.getInt(pos) != CD_ENTRY_SIGNATURE) break
            val method = cdBuffer.getShort(pos + 10).toInt() and 0xFFFF
            val compSize = cdBuffer.getInt(pos + 20).toLong() and 0xFFFFFFFFL
            val uncompSize = cdBuffer.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val fnLen = cdBuffer.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = cdBuffer.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = cdBuffer.getShort(pos + 32).toInt() and 0xFFFF
            val localOffset = cdBuffer.getInt(pos + 42).toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(fnLen)
            System.arraycopy(cdBytes, pos + 46, nameBytes, 0, fnLen)
            val name = String(nameBytes, Charsets.UTF_8)
            entries[name] = ZipEntryInfo(name, method, compSize, uncompSize, localOffset)
            pos += 46 + fnLen + extraLen + commentLen
        }

        progressCallback(10)

        // 4. Download and extract each required file
        val prefix = "ai/onnxruntime/native/$platform/"
        val resultFiles = mutableListOf<File>()
        val totalExpected = requiredNames.size

        requiredNames.forEachIndexed { index, fileName ->
            val entryKey = "$prefix$fileName"
            val entry = entries[entryKey] ?: error("Missing native entry $entryKey in $jarUrl")
            val targetFile = File(targetDir, fileName)
            val tempFile = File(targetDir, "$fileName.tmp")

            // Local header is 30 bytes + name + extra. Fetch header + compressed data in one range request
            val headerMaxLen = 30L + 512L
            val fetchLength = headerMaxLen + entry.compSize
            val rawBytes = fetchRange(jarUrl, entry.localHeaderOffset, entry.localHeaderOffset + fetchLength - 1)
            val rawBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)

            if (rawBuffer.getInt(0) != LOCAL_HEADER_SIGNATURE) {
                error("Corrupted local header signature for $fileName")
            }
            val locFnLen = rawBuffer.getShort(26).toInt() and 0xFFFF
            val locExtraLen = rawBuffer.getShort(28).toInt() and 0xFFFF
            val dataOffset = 30 + locFnLen + locExtraLen

            val dataStream: InputStream = when (entry.method) {
                COMPRESSION_DEFLATE -> {
                    val compBytes = ByteArray(entry.compSize.toInt())
                    System.arraycopy(rawBytes, dataOffset, compBytes, 0, entry.compSize.toInt())
                    InflaterInputStream(ByteArrayInputStream(compBytes), Inflater(true))
                }
                COMPRESSION_STORE -> {
                    ByteArrayInputStream(rawBytes, dataOffset, entry.compSize.toInt())
                }
                else -> error("Unsupported compression method ${entry.method}")
            }

            dataStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }

            promoteFile(tempFile, targetFile)
            resultFiles.add(targetFile)

            val currentProgress = 10 + (index + 1) * 90 / totalExpected
            progressCallback(currentProgress)
        }

        return resultFiles
    }

    private fun fetchRange(url: String, start: Long, end: Long): ByteArray {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Range", "bytes=$start-$end")
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != HTTP_PARTIAL_CONTENT) {
            error("Expected HTTP 206 Partial Content but got ${resp.statusCode()}")
        }
        return resp.body()
    }

    private fun extractViaStreamingZip(
        jarUrl: String,
        progressCallback: (Int) -> Unit,
    ): List<File> {
        val targetDir = getNativesDir()
        val platform = currentPlatform
        val requiredNames = getRequiredFiles(platform)

        val req = HttpRequest.newBuilder()
            .uri(URI.create(jarUrl))
            .build()
        val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in 200..299) {
            error("HTTP error ${resp.statusCode()} fetching $jarUrl")
        }

        val prefix = "ai/onnxruntime/native/$platform/"
        val foundFiles = mutableListOf<File>()
        val needed = requiredNames.toMutableSet()

        ZipInputStream(resp.body()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && needed.isNotEmpty()) {
                val name = entry.name
                if (name.startsWith(prefix)) {
                    val simpleName = name.removePrefix(prefix)
                    if (simpleName in needed) {
                        val targetFile = File(targetDir, simpleName)
                        val tempFile = File(targetDir, "$simpleName.tmp")
                        FileOutputStream(tempFile).use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read = zip.read(buffer)
                            while (read != -1) {
                                out.write(buffer, 0, read)
                                read = zip.read(buffer)
                            }
                        }
                        promoteFile(tempFile, targetFile)
                        foundFiles.add(targetFile)
                        needed.remove(simpleName)
                        val progress = (requiredNames.size - needed.size) * 100 / requiredNames.size
                        progressCallback(progress)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (needed.isNotEmpty()) {
            error("Could not find native libraries: $needed in $jarUrl")
        }
        return foundFiles
    }

    private fun promoteFile(tempFile: File, targetFile: File) {
        if (targetFile.exists()) {
            targetFile.delete()
        }
        tempFile.copyTo(targetFile, overwrite = true)
        tempFile.delete()
    }
}
