@file:Suppress("LargeClass")

package com.stellar.lang.plugin.onnx

import com.stellar.lang.plugin.PluginStatus
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OnnxNativeManagerSpec : FunSpec({
    val testDir = File("build/test_native_${System.nanoTime()}")
    lateinit var server: HttpServer
    var serverPort: Int = 0
    lateinit var sampleZipBytes: ByteArray
    lateinit var missingZipBytes: ByteArray

    afterSpec {
        server.stop(0)
        testDir.deleteRecursively()
        runCatching { OnnxNativeManager.getNativesDir().parentFile.deleteRecursively() }
        System.clearProperty("onnxruntime.native.path")
    }

    beforeSpec {
        testDir.mkdirs()

        // Create a mock jar zip with native entries for all platforms
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val platforms = listOf("linux-x64", "win-x64", "osx-aarch64")
            for (platform in platforms) {
                val files = OnnxNativeManager.getRequiredFiles(platform)
                for (fileName in files) {
                    val entry = ZipEntry("ai/onnxruntime/native/$platform/$fileName")
                    zos.putNextEntry(entry)
                    zos.write(ByteArray(2048) { 0x42 }) // dummy data > 1024 bytes
                    zos.closeEntry()
                }
            }
        }
        sampleZipBytes = baos.toByteArray()

        // Mock zip missing entries
        val missingBaos = ByteArrayOutputStream()
        ZipOutputStream(missingBaos).use { zos ->
            val entry = ZipEntry("ai/onnxruntime/dummy.txt")
            zos.putNextEntry(entry)
            zos.write(ByteArray(16))
            zos.closeEntry()
        }
        missingZipBytes = missingBaos.toByteArray()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/onnxruntime.jar") { exchange ->
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.set("Content-Length", sampleZipBytes.size.toString())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            } else if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeParts = rangeHeader.removePrefix("bytes=").split("-")
                val start = rangeParts[0].toInt()
                val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                    rangeParts[1].toInt()
                } else {
                    sampleZipBytes.size - 1
                }
                val clampedEnd = end.coerceAtMost(sampleZipBytes.size - 1)
                val length = (clampedEnd - start + 1).coerceAtLeast(0)

                exchange.responseHeaders.set(
                    "Content-Range",
                    "bytes $start-$clampedEnd/${sampleZipBytes.size}",
                )
                exchange.sendResponseHeaders(206, length.toLong())
                exchange.responseBody.write(sampleZipBytes, start, length)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, sampleZipBytes.size.toLong())
                exchange.responseBody.write(sampleZipBytes)
                exchange.close()
            }
        }

        // Context that always returns 200 (no range support) to test streaming zip fallback
        server.createContext("/streaming.jar") { exchange ->
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.responseHeaders.set("Content-Length", sampleZipBytes.size.toString())
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, sampleZipBytes.size.toLong())
                exchange.responseBody.write(sampleZipBytes)
                exchange.close()
            }
        }

        // Incomplete zip context
        server.createContext("/missing.jar") { exchange ->
            exchange.sendResponseHeaders(200, missingZipBytes.size.toLong())
            exchange.responseBody.write(missingZipBytes)
            exchange.close()
        }

        server.start()
    }

    afterSpec {
        System.clearProperty("onnxruntime.native.path")
        server.stop(0)
        testDir.deleteRecursively()
    }

    beforeEach {
        OnnxNativeManager.reset()
        System.clearProperty("onnxruntime.native.path")
        testDir.deleteRecursively()
        testDir.mkdirs()
        val config = com.stellar.core.config.ConfigManager.get<com.stellar.lang.config.StellarLangConfig>(
            com.stellar.lang.StellarLangMod.MOD_ID,
            "main",
        ) ?: com.stellar.core.config.ConfigManager.register(
            com.stellar.lang.StellarLangMod.MOD_ID,
            "main",
            com.stellar.lang.config.StellarLangConfig::class.java,
        )
        config.onnxModelDir.setValue(File(testDir, "models").path, false)
    }

    afterEach {
        System.clearProperty("onnxruntime.native.path")
    }

    test("Platform detection and required files") {
        val os = OnnxNativeManager.detectOs()
        os shouldNotBe ""

        val arch = OnnxNativeManager.detectArch()
        arch shouldNotBe ""

        OnnxNativeManager.currentPlatform shouldBe "$os-$arch"

        val winFiles = OnnxNativeManager.getRequiredFiles("win-x64")
        winFiles shouldBe listOf("onnxruntime.dll", "onnxruntime4j_jni.dll")

        val osxFiles = OnnxNativeManager.getRequiredFiles("osx-aarch64")
        osxFiles shouldBe listOf("libonnxruntime.dylib", "libonnxruntime4j_jni.dylib")

        val linuxFiles = OnnxNativeManager.getRequiredFiles("linux-x64")
        linuxFiles shouldBe listOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
    }

    test("OS and Arch branch detection") {
        val origOs = System.getProperty("os.name")
        val origArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.name", "Windows 11")
            OnnxNativeManager.detectOs() shouldBe "win"

            System.setProperty("os.name", "Mac OS X")
            OnnxNativeManager.detectOs() shouldBe "osx"

            System.setProperty("os.name", "Solaris")
            OnnxNativeManager.detectOs() shouldBe "linux"

            System.setProperty("os.arch", "x86_64")
            OnnxNativeManager.detectArch() shouldBe "x64"

            System.setProperty("os.arch", "arm64")
            OnnxNativeManager.detectArch() shouldBe "aarch64"

            System.setProperty("os.arch", "x86")
            OnnxNativeManager.detectArch() shouldBe "x86"

            System.setProperty("os.arch", "mips")
            OnnxNativeManager.detectArch() shouldBe "x64"
        } finally {
            if (origOs != null) System.setProperty("os.name", origOs)
            if (origArch != null) System.setProperty("os.arch", origArch)
        }
    }

    test("Readiness check and status") {
        val dir = OnnxNativeManager.getNativesDir()
        dir.exists() shouldBe true

        OnnxNativeManager.isNativeLibraryReady() shouldBe false
        OnnxNativeManager.ensureNativeLibrariesReady() shouldBe false
        OnnxNativeManager.getNativeStatus() shouldBe PluginStatus.NotConfigured("Native runtime not downloaded")

        // Populate required files
        val required = OnnxNativeManager.getRequiredFiles()
        for (name in required) {
            val entryFile = File(dir, name)
            entryFile.writeBytes(ByteArray(2048))
        }

        OnnxNativeManager.isNativeLibraryReady() shouldBe true
        OnnxNativeManager.ensureNativeLibrariesReady() shouldBe true
        System.getProperty("onnxruntime.native.path") shouldBe dir.absolutePath
        OnnxNativeManager.getNativeStatus() shouldBe PluginStatus.Ready("Native runtime ready")
        System.clearProperty("onnxruntime.native.path")
    }

    test("Download native libraries via HTTP Range") {
        val jarUrl = "http://127.0.0.1:$serverPort/onnxruntime.jar"
        var progressRecorded = 0

        val files = OnnxNativeManager.downloadNativeLibraries(jarUrl) { pct ->
            progressRecorded = pct
        }

        files.size shouldBe OnnxNativeManager.getRequiredFiles().size
        progressRecorded shouldBe 100
        OnnxNativeManager.isNativeLibraryReady() shouldBe true
        System.clearProperty("onnxruntime.native.path")
    }

    test("Download native libraries via Streaming Zip fallback") {
        val jarUrl = "http://127.0.0.1:$serverPort/streaming.jar"
        var progressRecorded = 0

        val files = OnnxNativeManager.downloadNativeLibraries(jarUrl) { pct ->
            progressRecorded = pct
        }

        files.size shouldBe OnnxNativeManager.getRequiredFiles().size
        progressRecorded shouldBe 100
        OnnxNativeManager.isNativeLibraryReady() shouldBe true
        System.clearProperty("onnxruntime.native.path")
    }

    test("Download native libraries async with completion callback") {
        val jarUrl = "http://127.0.0.1:$serverPort/onnxruntime.jar"
        val latch = CountDownLatch(1)
        var resultFiles: List<File>? = null

        OnnxNativeManager.downloadNativeLibrariesAsync(
            jarUrl = jarUrl,
            onProgress = {},
            onComplete = { res ->
                resultFiles = res.getOrNull()
                latch.countDown()
            },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        resultFiles shouldNotBe null
        resultFiles!!.size shouldBe OnnxNativeManager.getRequiredFiles().size
        OnnxNativeManager.isNativeLibraryReady() shouldBe true
        System.clearProperty("onnxruntime.native.path")
    }

    test("HttpClient override and reset") {
        val customClient = HttpClient.newHttpClient()
        OnnxNativeManager.httpClientOverride = customClient
        OnnxNativeManager.httpClient shouldBe customClient

        OnnxNativeManager.reset()
        OnnxNativeManager.httpClient shouldNotBe customClient
    }

    test("Download failure, cooldown expiration and debounce") {
        val invalidUrl = "http://127.0.0.1:$serverPort/nonexistent.jar"
        val latch = CountDownLatch(1)
        var failureOccurred = false

        OnnxNativeManager.downloadNativeLibrariesAsync(
            jarUrl = invalidUrl,
            onComplete = { res ->
                failureOccurred = res.isFailure
                latch.countDown()
            },
        )

        latch.await(5, TimeUnit.SECONDS) shouldBe true
        failureOccurred shouldBe true
        OnnxNativeManager.isInCooldown() shouldBe true

        val status = OnnxNativeManager.getNativeStatus()
        status shouldNotBe null
        (status is PluginStatus.Error) shouldBe true

        // Retry while in cooldown is ignored
        val secondLatch = CountDownLatch(1)
        OnnxNativeManager.downloadNativeLibrariesAsync(
            jarUrl = invalidUrl,
            forceRetry = false,
            onComplete = {
                secondLatch.countDown()
            },
        )
        secondLatch.await(500, TimeUnit.MILLISECONDS) shouldBe false

        // Expired cooldown clears cooldown
        OnnxNativeManager.failureCooldowns["natives"] = System.currentTimeMillis() - 35_000L
        OnnxNativeManager.isInCooldown("natives") shouldBe false
    }

    test("Concurrent download requests are debounced") {
        val jarUrl = "http://127.0.0.1:$serverPort/onnxruntime.jar"
        val latch = CountDownLatch(1)
        OnnxNativeManager.downloadNativeLibrariesAsync(
            jarUrl = jarUrl,
            onComplete = { latch.countDown() },
        )
        // Second call while active should return immediately
        OnnxNativeManager.downloadNativeLibrariesAsync(jarUrl = jarUrl)
        latch.await(5, TimeUnit.SECONDS) shouldBe true
        System.clearProperty("onnxruntime.native.path")
    }

    test("File promotion overwrites existing files") {
        val dir = OnnxNativeManager.getNativesDir()
        val required = OnnxNativeManager.getRequiredFiles()
        for (name in required) {
            val existing = File(dir, name)
            existing.writeText("old content")
        }
        val jarUrl = "http://127.0.0.1:$serverPort/onnxruntime.jar"
        val files = OnnxNativeManager.downloadNativeLibraries(jarUrl)
        files.size shouldBe required.size
        OnnxNativeManager.isNativeLibraryReady() shouldBe true
        System.clearProperty("onnxruntime.native.path")
    }

    test("Streaming zip fails when required libraries are missing") {
        val missingUrl = "http://127.0.0.1:$serverPort/missing.jar"
        shouldThrow<IllegalStateException> {
            OnnxNativeManager.downloadNativeLibraries(missingUrl)
        }
    }

    test("Status reporting while downloading") {
        OnnxNativeManager.activeDownloads["natives"] = OnnxModelManager.DownloadState(
            "natives",
            progressPercent = 42,
            isDownloading = true,
        )
        val status = OnnxNativeManager.getNativeStatus()
        status shouldBe PluginStatus.Downloading(42, "ONNX Native Runtime")
    }
})
