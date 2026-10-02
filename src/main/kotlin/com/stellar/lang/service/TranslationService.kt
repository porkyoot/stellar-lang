@file:Suppress("LargeClass", "CognitiveComplexMethod")

package com.stellar.lang.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stellar.core.config.ConfigManager
import com.stellar.lang.StellarLangMod
import com.stellar.lang.config.StellarLangConfig
import com.stellar.lang.plugin.PluginRegistry
import com.stellar.lang.plugin.PluginStatus
import com.stellar.lang.plugin.libretranslate.LibreTranslatePlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service managing communication with LibreTranslate API, caching, and language detection.
 */
@Suppress(
    "TooManyFunctions",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
    "ComplexCondition",
)
object TranslationService {
    private val logger: Logger = LoggerFactory.getLogger(StellarLangMod.MOD_ID)
    private val gson = Gson()
    private const val CONNECT_TIMEOUT_SECONDS = 4L
    private const val TIMEOUT_SECONDS = 5L
    private const val THREAD_POOL_SIZE = 3
    private const val RETRY_INTERVAL_SECONDS = 15L
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val APPLICATION_JSON = "application/json"
    private const val HEADER_CONTENT_TYPE = "Content-Type"
    private const val HEADER_ACCEPT = "Accept"
    private const val ERROR_SNIPPET_LENGTH = 80
    private const val UNKNOWN_LANG = "unknown"
    private const val MAX_ATTEMPTS_PER_PROVIDER = 2
    private const val RETRY_DELAY_MS = 50L
    private const val LIBRETRANSLATE_PROVIDER_ID = "libretranslate"
    private const val MIN_LIBRE_CONFIDENCE = 80.0f
    private const val DEFAULT_CONFIDENCE = 100f
    private const val SHORT_TEXT_THRESHOLD = 25
    private const val SHORT_TEXT_WORD_COUNT = 3

    @Volatile
    private var lastNetworkException: Throwable? = null

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    private val executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE) { runnable ->
        Thread(runnable, "StellarLang-Worker").apply { isDaemon = true }
    }

    private val retryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "StellarLang-RetryWorker").apply { isDaemon = true }
    }

    data class FailedRequest(
        val text: String,
        val targetLang: String,
        val failedAt: Long,
        val attemptCount: Int = 1,
    )

    internal val failedRequests = ConcurrentHashMap<String, FailedRequest>()
    private val successListeners = CopyOnWriteArrayList<(TranslationResult) -> Unit>()

    internal val deepLQueue = DeepLTranslationQueue(
        pacingIntervalProvider = {
            val config = getConfig()
            val host = config.deeplApiHost.value()
            val plugin = PluginRegistry.getTranslator("deepl") as? com.stellar.lang.plugin.deepl.DeepLPlugin
            if (plugin != null && plugin.isLocalHost(host)) {
                0L
            } else {
                config.deeplRequestIntervalMs.value().toLong()
            }
        },
        batchExecutor = { texts, targetLang ->
            executeBatchTranslation(texts, targetLang)
        },
        onSuccess = { result ->
            val key = TranslationCache.cacheKey(result.originalText, result.targetLanguage)
            TranslationCache.put(result)
            failedRequests.remove(key)
            notifySuccess(result)
        },
        onFailure = { text, targetLang, key ->
            val transStatus = PluginRegistry.getActiveTranslator().getStatus()
            val detStatus = PluginRegistry.getActiveDetector().getStatus()
            val isDownloading = transStatus is PluginStatus.Downloading || detStatus is PluginStatus.Downloading
            if (!isDownloading) {
                TranslationCache.markFailed(key)
                val existing = failedRequests[key]
                val attempt = (existing?.attemptCount ?: 0) + 1
                failedRequests[key] = FailedRequest(text, targetLang, System.currentTimeMillis(), attempt)
            }
        },
        onComplete = { key, result ->
            TranslationCache.completeInFlight(key, result)
        },
    )

    internal val googleQueue = GoogleTranslationQueue(
        pacingIntervalProvider = {
            val config = getConfig()
            val host = config.googleApiHost.value()
            val plugin = PluginRegistry.getTranslator("google") as? com.stellar.lang.plugin.google.GooglePlugin
            if (plugin != null && plugin.isLocalHost(host)) {
                0L
            } else {
                config.googleRequestIntervalMs.value().toLong()
            }
        },
        batchExecutor = { texts, targetLang ->
            executeBatchTranslation(texts, targetLang)
        },
        onSuccess = { result ->
            val key = TranslationCache.cacheKey(result.originalText, result.targetLanguage)
            TranslationCache.put(result)
            failedRequests.remove(key)
            notifySuccess(result)
        },
        onFailure = { text, targetLang, key ->
            val transStatus = PluginRegistry.getActiveTranslator().getStatus()
            val detStatus = PluginRegistry.getActiveDetector().getStatus()
            val isDownloading = transStatus is PluginStatus.Downloading || detStatus is PluginStatus.Downloading
            if (!isDownloading) {
                TranslationCache.markFailed(key)
                val existing = failedRequests[key]
                val attempt = (existing?.attemptCount ?: 0) + 1
                failedRequests[key] = FailedRequest(text, targetLang, System.currentTimeMillis(), attempt)
            }
        },
        onComplete = { key, result ->
            TranslationCache.completeInFlight(key, result)
        },
    )

    init {
        retryExecutor.scheduleWithFixedDelay(
            { runCatching { retryFailedTranslations() } },
            RETRY_INTERVAL_SECONDS,
            RETRY_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private const val MIN_LANG_CODE_LENGTH = 2
    private const val MAX_LANG_CODE_LENGTH = 3
    private const val DEFAULT_FALLBACK_LANG = "en"
    private const val AUTO_LANG = "auto"

    internal var languageProvider: (() -> String?)? = null

    fun getTargetLanguage(): String {
        val configured = getConfig().targetLanguage.value().trim().lowercase()
        if (configured.isNotEmpty() && configured != AUTO_LANG) {
            return configured
        }
        return inferTargetLanguage()
    }

    fun inferTargetLanguage(): String {
        val gameCode = languageProvider?.invoke() ?: getGameLanguageCode()
        return normalizeLanguageCode(gameCode)
    }

    fun normalizeLanguageCode(code: String?): String {
        if (code.isNullOrBlank()) {
            val sysLang = runCatching { java.util.Locale.getDefault().language }.getOrNull()
            return if (!sysLang.isNullOrBlank() && sysLang.length in MIN_LANG_CODE_LENGTH..MAX_LANG_CODE_LENGTH) {
                sysLang.lowercase()
            } else {
                DEFAULT_FALLBACK_LANG
            }
        }
        val clean = code.trim().lowercase().replace('-', '_')
        if (clean == "lol_us") return DEFAULT_FALLBACK_LANG
        val primary = clean.substringBefore('_')
        return if (primary.length in MIN_LANG_CODE_LENGTH..MAX_LANG_CODE_LENGTH) primary else DEFAULT_FALLBACK_LANG
    }

    private fun getGameLanguageCode(): String? {
        return runCatching {
            net.minecraft.client.Minecraft.getInstance().options.languageCode
        }.getOrNull()
    }

    fun getConfig(): StellarLangConfig {
        return ConfigManager.get<StellarLangConfig>(StellarLangMod.MOD_ID, "main")
            ?: ConfigManager.register(StellarLangMod.MOD_ID, "main", StellarLangConfig::class.java)
    }

    fun getCached(text: String, targetLang: String): TranslationResult? {
        val exact = TranslationCache.get(text, targetLang)
        if (exact != null) return exact

        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text).trim()
        if (clean != text.trim()) {
            val fromClean = TranslationCache.get(clean, targetLang)
            if (fromClean != null) {
                val formattedTrans = if (!fromClean.translatedText.contains('§') && text.contains('§')) {
                    val leading = extractLeadingFormatting(text)
                    "$leading${fromClean.translatedText}"
                } else {
                    fromClean.translatedText
                }
                return fromClean.copy(originalText = text, translatedText = formattedTrans)
            }
        }
        return null
    }

    private fun extractLeadingFormatting(text: String): String {
        val matcher = com.stellar.lang.format.FormattingTagHelper.FORMATTING_CODE_PATTERN.matcher(text)
        return if (matcher.find() && matcher.start() == 0) matcher.group(1) else ""
    }

    fun putCache(result: TranslationResult) {
        TranslationCache.put(result)
    }

    fun evict(text: String, targetLang: String = getTargetLanguage()) {
        val trimmed = text.trim()
        val key = TranslationCache.cacheKey(trimmed, targetLang)
        TranslationCache.evict(trimmed, targetLang)
        failedRequests.remove(key)
        deepLQueue.remove(key)
        googleQueue.remove(key)
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(trimmed).trim()
        if (clean != trimmed) {
            val cleanKey = TranslationCache.cacheKey(clean, targetLang)
            TranslationCache.evict(clean, targetLang)
            failedRequests.remove(cleanKey)
            deepLQueue.remove(cleanKey)
            googleQueue.remove(cleanKey)
        }
    }

    fun isInFlight(text: String, targetLang: String = getTargetLanguage()): Boolean {
        val key = TranslationCache.cacheKey(text.trim(), targetLang)
        if (TranslationCache.isInFlight(key) || deepLQueue.isEnqueued(key) || googleQueue.isEnqueued(key)) {
            return true
        }
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text).trim()
        if (clean != text.trim()) {
            val cleanKey = TranslationCache.cacheKey(clean, targetLang)
            return TranslationCache.isInFlight(cleanKey) ||
                deepLQueue.isEnqueued(cleanKey) ||
                googleQueue.isEnqueued(cleanKey)
        }
        return false
    }

    fun isDeepLActiveFor(targetLang: String = getTargetLanguage()): Boolean {
        val candidates = getCandidateTranslators(targetLang)
        return candidates.firstOrNull() is com.stellar.lang.plugin.deepl.DeepLPlugin
    }

    fun isGoogleActiveFor(targetLang: String = getTargetLanguage()): Boolean {
        val candidates = getCandidateTranslators(targetLang)
        return candidates.firstOrNull() is com.stellar.lang.plugin.google.GooglePlugin
    }

    fun getDeepLQueueSize(): Int = deepLQueue.size()

    fun getGoogleQueueSize(): Int = googleQueue.size()

    @JvmOverloads
    @Suppress("ReturnCount")
    fun translateAsync(
        text: String,
        forceRetry: Boolean = false,
        callback: (TranslationResult?) -> Unit,
    ) {
        val trimmed = text.trim()
        val config = getConfig()
        if (trimmed.isEmpty() || !config.enabled.value()) {
            callback(null)
            return
        }

        val targetLang = getTargetLanguage()
        val key = TranslationCache.cacheKey(trimmed, targetLang)

        if (forceRetry) {
            evict(trimmed, targetLang)
        } else {
            val cached = TranslationCache.get(trimmed, targetLang)
            if (cached != null) {
                callback(cached)
                return
            }
            if (TranslationCache.isCircuitBreakerOpen() || TranslationCache.isThrottled(key)) {
                callback(null)
                return
            }
        }

        val shouldFetch = TranslationCache.queueInFlight(key, callback)
        if (!shouldFetch) {
            return
        }

        if (isDeepLActiveFor(targetLang)) {
            deepLQueue.enqueue(trimmed, key, targetLang)
        } else if (isGoogleActiveFor(targetLang)) {
            googleQueue.enqueue(trimmed, key, targetLang)
        } else {
            dispatchTranslationTask(trimmed, key)
        }
    }

    private fun dispatchTranslationTask(
        trimmed: String,
        key: String,
    ) {
        val targetLang = getTargetLanguage()
        executor.execute {
            val result = executeTranslation(trimmed, targetLang)
            if (result != null) {
                TranslationCache.put(result)
                failedRequests.remove(key)
                notifySuccess(result)
            } else {
                val transStatus = PluginRegistry.getActiveTranslator().getStatus()
                val detStatus = PluginRegistry.getActiveDetector().getStatus()
                val isDownloading = transStatus is PluginStatus.Downloading || detStatus is PluginStatus.Downloading
                if (!isDownloading) {
                    TranslationCache.markFailed(key)
                    val existing = failedRequests[key]
                    val attempt = (existing?.attemptCount ?: 0) + 1
                    failedRequests[key] = FailedRequest(trimmed, targetLang, System.currentTimeMillis(), attempt)
                }
            }
            TranslationCache.completeInFlight(key, result)
        }
    }

    fun translateBatchSync(texts: List<String>): List<TranslationResult>? {
        val nonBlank = texts.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()

        val config = getConfig()
        if (!config.enabled.value()) return null

        val targetLang = getTargetLanguage()
        return resolveBatchTranslations(nonBlank, targetLang)
    }

    @Suppress("ReturnCount")
    private fun resolveBatchTranslations(
        texts: List<String>,
        targetLang: String,
    ): List<TranslationResult>? {
        val cachedMap = mutableMapOf<String, TranslationResult>()
        val missing = mutableListOf<String>()

        for (item in texts) {
            val cached = TranslationCache.get(item, targetLang)
            if (cached != null) {
                cachedMap[item] = cached
            } else if (!missing.contains(item)) {
                missing.add(item)
            }
        }

        if (missing.isEmpty()) {
            return texts.mapNotNull { cachedMap[it] }
        }

        if (TranslationCache.isCircuitBreakerOpen()) {
            return null
        }

        val fetched = executeBatchTranslation(missing, targetLang)
            ?: return null

        fetched.forEach { res ->
            TranslationCache.put(res)
            cachedMap[res.originalText] = res
        }

        return texts.map { cachedMap[it] ?: TranslationResult(it, it, targetLang, targetLang, true) }
    }

    fun isSameLanguage(lang1: String?, lang2: String?): Boolean {
        if (lang1.isNullOrBlank() || lang2.isNullOrBlank()) return false
        val clean1 = lang1.trim().lowercase().substringBefore('_').substringBefore('-')
        val clean2 = lang2.trim().lowercase().substringBefore('_').substringBefore('-')
        val invalidLangs = setOf(UNKNOWN_LANG, AUTO_LANG)
        if (clean1 in invalidLangs || clean2 in invalidLangs) {
            return false
        }
        return clean1 == clean2
    }

    fun isUntranslatedFailure(result: TranslationResult): Boolean {
        return isUntranslatedFailure(
            result.originalText,
            result.translatedText,
            result.detectedLanguage,
            result.targetLanguage,
        )
    }

    fun isUntranslatedFailure(
        originalText: String,
        translatedText: String?,
        detectedLang: String?,
        targetLang: String,
    ): Boolean {
        if (translatedText == null) return true
        if (isSameLanguage(detectedLang, targetLang)) return false
        val origTrimmed = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(originalText).trim()
        val transTrimmed = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(translatedText).trim()
        if (origTrimmed.none { it.isLetter() }) return false
        return origTrimmed.equals(transTrimmed, ignoreCase = true)
    }

    fun isBatchUntranslatedFailure(
        originalTexts: List<String>,
        translatedTexts: List<String>?,
        detectedLang: String?,
        targetLang: String,
    ): Boolean {
        if (translatedTexts == null || translatedTexts.size != originalTexts.size) return true
        if (isSameLanguage(detectedLang, targetLang)) return false

        val cleanOriginals = originalTexts.map {
            com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(it).trim()
        }
        val cleanTranslated = translatedTexts.map {
            com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(it).trim()
        }
        val translatableIndices = cleanOriginals.indices.filter { cleanOriginals[it].any { ch -> ch.isLetter() } }
        if (translatableIndices.isEmpty()) return false

        val failedCount = translatableIndices.count { idx ->
            cleanOriginals[idx].equals(cleanTranslated[idx], ignoreCase = true)
        }
        return failedCount == translatableIndices.size
    }

    private fun isCircuitBrokenRemote(candidate: com.stellar.lang.plugin.TranslationPlugin): Boolean {
        val isRemote = candidate is LibreTranslatePlugin ||
            candidate is com.stellar.lang.plugin.deepl.DeepLPlugin ||
            candidate is com.stellar.lang.plugin.google.GooglePlugin
        return isRemote && TranslationCache.isCircuitBreakerOpen()
    }

    fun getCandidateTranslators(
        targetLang: String = getTargetLanguage(),
    ): List<com.stellar.lang.plugin.TranslationPlugin> {
        val active = PluginRegistry.getActiveTranslator()
        val fallback = PluginRegistry.getFallbackTranslator(active)
        return listOfNotNull(active, fallback).distinctBy { it.id }.filter { candidate ->
            if (isCircuitBrokenRemote(candidate)) {
                false
            } else if (candidate === active) {
                true
            } else if (candidate is com.stellar.lang.plugin.onnx.OnnxTranslationPlugin) {
                com.stellar.lang.plugin.onnx.OnnxModelManager.isTranslationModelReady(targetLang)
            } else if (candidate is LibreTranslatePlugin) {
                val host = getConfig().apiHost.value().trim()
                host.isNotBlank()
            } else if (candidate is com.stellar.lang.plugin.deepl.DeepLPlugin) {
                val key = getConfig().deeplApiKey.value().trim()
                key.isNotBlank()
            } else if (candidate is com.stellar.lang.plugin.google.GooglePlugin) {
                val key = getConfig().googleApiKey.value().trim()
                key.isNotBlank()
            } else {
                true
            }
        }
    }

    internal fun detectLanguage(text: String): String {
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text)
        if (com.stellar.lang.plugin.LanguageDetectionHelper.isUniversalSlang(clean)) {
            return getTargetLanguage()
        }
        val quick = com.stellar.lang.plugin.LanguageDetectionHelper.detectQuick(clean)
        if (quick != null) return quick

        val activeDetector = PluginRegistry.getActiveDetector()
        val isRemoteDetector = activeDetector is LibreTranslatePlugin ||
            activeDetector is com.stellar.lang.plugin.deepl.DeepLPlugin ||
            activeDetector is com.stellar.lang.plugin.google.GooglePlugin
        val detector = if (isRemoteDetector && TranslationCache.isCircuitBreakerOpen()) {
            PluginRegistry.getFallbackDetector(activeDetector) ?: activeDetector
        } else {
            activeDetector
        }
        return kotlinx.coroutines.runBlocking {
            val detected = detector.detectLanguage(clean)
            if (!detected.isNullOrBlank() && detected != UNKNOWN_LANG &&
                detected in com.stellar.lang.plugin.LanguageDetectionHelper.SUPPORTED_LANGUAGES
            ) {
                detected
            } else {
                runCatching {
                    val fallback = PluginRegistry.getFallbackDetector(detector)
                    if (fallback is com.stellar.lang.plugin.onnx.OnnxLanguageDetectorPlugin &&
                        !com.stellar.lang.plugin.onnx.OnnxModelManager.isDetectionModelReady()
                    ) {
                        null
                    } else {
                        val fbDetected = fallback?.detectLanguage(clean)
                        if (!fbDetected.isNullOrBlank() && fbDetected != UNKNOWN_LANG &&
                            fbDetected in com.stellar.lang.plugin.LanguageDetectionHelper.SUPPORTED_LANGUAGES
                        ) {
                            fbDetected
                        } else {
                            detected?.takeIf { it != UNKNOWN_LANG }
                        }
                    }
                }.getOrNull() ?: detected ?: UNKNOWN_LANG
            }
        }
    }

    @Suppress("ReturnCount", "NestedBlockDepth")
    internal fun executeTranslation(
        text: String,
        targetLang: String,
    ): TranslationResult? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val cleanForCheck = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(trimmed)
        if (cleanForCheck.none { it.isLetter() }) {
            return TranslationResult(
                originalText = text,
                translatedText = text,
                detectedLanguage = targetLang,
                targetLanguage = targetLang,
                isSameLanguage = true,
            )
        }
        if (com.stellar.lang.plugin.LanguageDetectionHelper.isUniversalSlang(cleanForCheck)) {
            return TranslationResult(
                originalText = text,
                translatedText = text,
                detectedLanguage = targetLang,
                targetLanguage = targetLang,
                isSameLanguage = true,
            )
        }
        val quickLang = detectLanguageQuick(cleanForCheck)
        if (quickLang != null && isSameLanguage(quickLang, targetLang)) {
            return TranslationResult(
                originalText = text,
                translatedText = text,
                detectedLanguage = quickLang,
                targetLanguage = targetLang,
                isSameLanguage = true,
            )
        }

        val textWithProtectedTextmojis =
            com.stellar.lang.plugin.LanguageDetectionHelper.protectTextmojisAndKaomojis(trimmed)
        val textToTranslate = com.stellar.lang.player.PlayerNameHelper.protectPlayerNames(
            com.stellar.lang.format.FormattingTagHelper.encodeToUntranslatableTags(textWithProtectedTextmojis),
        )

        return runCatching {
            lastNetworkException = null
            val candidates = getCandidateTranslators(targetLang)
            var finalResult: TranslationResult? = null
            var detected = UNKNOWN_LANG

            for (candidate in candidates) {
                var fatalException = false
                for (attempt in 1..MAX_ATTEMPTS_PER_PROVIDER) {
                    val outcome = runCatching {
                        if (candidate is LibreTranslatePlugin) {
                            executeTranslationWithProvider(candidate, textToTranslate, detected, targetLang)
                        } else if (candidate is com.stellar.lang.plugin.deepl.DeepLPlugin) {
                            executeTranslationWithProvider(candidate, textToTranslate, detected, targetLang)
                        } else if (candidate is com.stellar.lang.plugin.google.GooglePlugin) {
                            executeTranslationWithProvider(candidate, textToTranslate, detected, targetLang)
                        } else {
                            if (detected == UNKNOWN_LANG) {
                                detected = detectLanguage(textToTranslate)
                            }
                            if (isSameLanguage(detected, targetLang)) {
                                TranslationResult(
                                    originalText = text,
                                    translatedText = text,
                                    detectedLanguage = detected,
                                    targetLanguage = targetLang,
                                    isSameLanguage = true,
                                )
                            } else {
                                executeTranslationWithProvider(candidate, textToTranslate, detected, targetLang)
                            }
                        }
                    }.onFailure { ex ->
                        if (isNonRetryableException(ex)) {
                            fatalException = true
                        }
                        logger.warn(
                            "Provider '{}' threw exception for '{}' (attempt {}/{}): {}",
                            candidate.id,
                            trimmed,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                            ex.message,
                        )
                    }.getOrNull()

                    if (outcome != null) {
                        val decoded = com.stellar.lang.format.FormattingTagHelper.decodeFromUntranslatableTags(
                            outcome.translatedText,
                        )
                        finalResult = outcome.copy(originalText = text, translatedText = decoded)
                        break
                    }

                    if (fatalException || isNonRetryableException(lastNetworkException) ||
                        isCircuitBrokenRemote(candidate)
                    ) {
                        break
                    }

                    if (attempt < MAX_ATTEMPTS_PER_PROVIDER) {
                        logger.warn(
                            "Provider '{}' failed or returned unchanged text for '{}' (attempt {}/{}). Retrying...",
                            candidate.id,
                            trimmed,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                        )
                        sleepQuietly(RETRY_DELAY_MS)
                    }
                }

                if (finalResult != null) {
                    if (candidate !== candidates.first()) {
                        logger.info("Successfully translated '{}' using fallback provider '{}'", trimmed, candidate.id)
                    }
                    break
                }

                if (fatalException || isNonRetryableException(lastNetworkException)) {
                    break
                }

                logger.warn(
                    "Provider '{}' exhausted all retries for '{}'. Attempting fallback provider if available...",
                    candidate.id,
                    trimmed,
                )
            }

            finalResult
        }.onFailure { ex ->
            logger.warn("Translation failed for '{}': {}", text, ex.message)
        }.getOrNull()
    }

    @Suppress("ReturnCount")
    private fun executeTranslationWithProvider(
        provider: com.stellar.lang.plugin.TranslationPlugin,
        text: String,
        detectedLang: String,
        targetLang: String,
    ): TranslationResult? {
        val config = getConfig()
        if (provider is LibreTranslatePlugin) {
            val res = executeTranslation(text, config.apiHost.value(), config.apiKey.value(), targetLang)
            return if (res != null && !isUntranslatedFailure(res)) res else null
        }
        if (provider is com.stellar.lang.plugin.deepl.DeepLPlugin) {
            val host = config.deeplApiHost.value()
            val apiKey = config.deeplApiKey.value().trim()
            val formality = config.deeplFormality.value()
            val detailed = provider.executeBatchTranslateDetailed(
                listOf(text),
                detectedLang.takeIf { it != UNKNOWN_LANG },
                targetLang,
                host,
                apiKey,
                formality,
            )?.firstOrNull() ?: return null

            val rawDetected = detailed.detectedSourceLanguage ?: detectedLang.takeIf { it != UNKNOWN_LANG }
            val effectiveDetected = resolveDeepLEffectiveLanguage(rawDetected, text, targetLang)
            val isSame = isSameLanguage(effectiveDetected, targetLang)
            val effectiveTrans = if (isSame) text else detailed.translatedText
            return if (!isUntranslatedFailure(text, effectiveTrans, effectiveDetected, targetLang)) {
                TranslationResult(
                    originalText = text,
                    translatedText = effectiveTrans,
                    detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                    targetLanguage = targetLang,
                    isSameLanguage = isSame,
                )
            } else {
                null
            }
        }
        if (provider is com.stellar.lang.plugin.google.GooglePlugin) {
            val host = config.googleApiHost.value()
            val apiKey = config.googleApiKey.value().trim()
            val detailed = provider.executeBatchTranslateDetailed(
                listOf(text),
                detectedLang.takeIf { it != UNKNOWN_LANG },
                targetLang,
                host,
                apiKey,
            )?.firstOrNull() ?: return null

            val rawDetected = detailed.detectedSourceLanguage ?: detectedLang.takeIf { it != UNKNOWN_LANG }
            val effectiveDetected = resolveDeepLEffectiveLanguage(rawDetected, text, targetLang)
            val isSame = isSameLanguage(effectiveDetected, targetLang)
            val effectiveTrans = if (isSame) text else detailed.translatedText
            return if (!isUntranslatedFailure(text, effectiveTrans, effectiveDetected, targetLang)) {
                TranslationResult(
                    originalText = text,
                    translatedText = effectiveTrans,
                    detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                    targetLanguage = targetLang,
                    isSameLanguage = isSame,
                )
            } else {
                null
            }
        }

        return kotlinx.coroutines.runBlocking {
            val effectiveDetected = if (detectedLang == UNKNOWN_LANG) {
                detectLanguage(text).takeIf { it != UNKNOWN_LANG }
            } else {
                detectedLang
            }
            val translated = provider.translate(
                text,
                effectiveDetected,
                targetLang,
            )
            if (translated != null && !isUntranslatedFailure(text, translated, effectiveDetected, targetLang)) {
                val isSame = isSameLanguage(effectiveDetected, targetLang)
                TranslationResult(
                    originalText = text,
                    translatedText = translated,
                    detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                    targetLanguage = targetLang,
                    isSameLanguage = isSame,
                )
            } else {
                null
            }
        }
    }

    @Suppress("ReturnCount", "NestedBlockDepth")
    internal fun executeBatchTranslation(
        texts: List<String>,
        targetLang: String,
    ): List<TranslationResult>? {
        if (texts.isEmpty()) return emptyList()

        val cleanList = texts.map { com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(it) }
        if (cleanList.all { it.none { ch -> ch.isLetter() } }) {
            return texts.map {
                TranslationResult(
                    originalText = it,
                    translatedText = it,
                    detectedLanguage = targetLang,
                    targetLanguage = targetLang,
                    isSameLanguage = true,
                )
            }
        }

        val encodedTexts = texts.map {
            val protected = com.stellar.lang.plugin.LanguageDetectionHelper.protectTextmojisAndKaomojis(it)
            com.stellar.lang.player.PlayerNameHelper.protectPlayerNames(
                com.stellar.lang.format.FormattingTagHelper.encodeToUntranslatableTags(protected),
            )
        }

        return runCatching {
            lastNetworkException = null
            val candidates = getCandidateTranslators(targetLang)
            var finalResults: List<TranslationResult>? = null
            var detected = UNKNOWN_LANG

            for (candidate in candidates) {
                var fatalException = false
                for (attempt in 1..MAX_ATTEMPTS_PER_PROVIDER) {
                    val results = runCatching {
                        if (candidate is LibreTranslatePlugin) {
                            executeBatchTranslationWithProvider(candidate, encodedTexts, detected, targetLang)
                        } else if (candidate is com.stellar.lang.plugin.deepl.DeepLPlugin) {
                            executeBatchTranslationWithProvider(candidate, encodedTexts, detected, targetLang)
                        } else if (candidate is com.stellar.lang.plugin.google.GooglePlugin) {
                            executeBatchTranslationWithProvider(candidate, encodedTexts, detected, targetLang)
                        } else {
                            if (detected == UNKNOWN_LANG) {
                                val sampleText = encodedTexts.firstOrNull { it.any { ch -> ch.isLetter() } }
                                    ?: encodedTexts.first()
                                detected = detectLanguage(sampleText)
                            }
                            if (isSameLanguage(detected, targetLang)) {
                                texts.map {
                                    TranslationResult(
                                        originalText = it,
                                        translatedText = it,
                                        detectedLanguage = detected,
                                        targetLanguage = targetLang,
                                        isSameLanguage = true,
                                    )
                                }
                            } else {
                                executeBatchTranslationWithProvider(candidate, encodedTexts, detected, targetLang)
                            }
                        }
                    }.onFailure { ex ->
                        if (isNonRetryableException(ex)) {
                            fatalException = true
                        }
                        logger.warn(
                            "Batch provider '{}' threw on attempt {}/{}: {}",
                            candidate.id,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                            ex.message,
                        )
                    }.getOrNull()

                    if (results != null) {
                        finalResults = results.mapIndexed { idx, res ->
                            val decoded = com.stellar.lang.format.FormattingTagHelper.decodeFromUntranslatableTags(
                                res.translatedText,
                            )
                            res.copy(
                                originalText = texts.getOrElse(idx) { res.originalText },
                                translatedText = decoded,
                            )
                        }
                        break
                    }

                    if (fatalException || isNonRetryableException(lastNetworkException) ||
                        isCircuitBrokenRemote(candidate)
                    ) {
                        break
                    }

                    if (attempt < MAX_ATTEMPTS_PER_PROVIDER) {
                        logger.warn(
                            "Batch provider '{}' failed on attempt {}/{}. Retrying...",
                            candidate.id,
                            attempt,
                            MAX_ATTEMPTS_PER_PROVIDER,
                        )
                        sleepQuietly(RETRY_DELAY_MS)
                    }
                }

                if (finalResults != null) {
                    if (candidate !== candidates.first()) {
                        logger.info("Batch translation succeeded using fallback provider '{}'", candidate.id)
                    }
                    break
                }

                if (fatalException || isNonRetryableException(lastNetworkException)) {
                    break
                }

                logger.warn(
                    "Batch provider '{}' exhausted all retries. Attempting fallback provider if available...",
                    candidate.id,
                )
            }

            if (finalResults != null) {
                finalResults = finalResults.map { res ->
                    if (isUntranslatedFailure(res)) {
                        val singleFallback = executeTranslation(res.originalText, targetLang)
                        if (singleFallback != null && !isUntranslatedFailure(singleFallback)) {
                            singleFallback
                        } else {
                            res
                        }
                    } else {
                        res
                    }
                }
            }

            finalResults
        }.onFailure { ex ->
            logger.warn("Batch translation failed: {}", ex.message)
        }.getOrNull()
    }

    @Suppress("ReturnCount")
    private fun executeBatchTranslationWithProvider(
        provider: com.stellar.lang.plugin.TranslationPlugin,
        texts: List<String>,
        detectedLang: String,
        targetLang: String,
    ): List<TranslationResult>? {
        val config = getConfig()
        if (provider is LibreTranslatePlugin) {
            val results = executeBatchTranslation(texts, config.apiHost.value(), config.apiKey.value(), targetLang)
            val effectiveDetected = results?.firstOrNull()?.detectedLanguage ?: detectedLang
            val isFailure = results == null ||
                isBatchUntranslatedFailure(texts, results.map { it.translatedText }, effectiveDetected, targetLang)
            return if (isFailure) null else results
        }
        if (provider is com.stellar.lang.plugin.deepl.DeepLPlugin) {
            val host = config.deeplApiHost.value()
            val apiKey = config.deeplApiKey.value().trim()
            val formality = config.deeplFormality.value()
            val detailedList = provider.executeBatchTranslateDetailed(
                texts,
                detectedLang.takeIf { it != UNKNOWN_LANG },
                targetLang,
                host,
                apiKey,
                formality,
            ) ?: return null

            return texts.mapIndexed { index, original ->
                val detailed = detailedList.getOrElse(index) {
                    com.stellar.lang.plugin.deepl.DeepLPlugin.DeepLTranslationResult(original, null)
                }
                val rawDetected = detailed.detectedSourceLanguage ?: detectedLang.takeIf { it != UNKNOWN_LANG }
                val effectiveDetected = resolveDeepLEffectiveLanguage(rawDetected, original, targetLang)
                val isSame = isSameLanguage(effectiveDetected, targetLang)
                val effectiveTrans = if (isSame) original else detailed.translatedText
                TranslationResult(
                    originalText = original,
                    translatedText = effectiveTrans,
                    detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                    targetLanguage = targetLang,
                    isSameLanguage = isSame,
                )
            }
        }
        if (provider is com.stellar.lang.plugin.google.GooglePlugin) {
            val host = config.googleApiHost.value()
            val apiKey = config.googleApiKey.value().trim()
            val detailedList = provider.executeBatchTranslateDetailed(
                texts,
                detectedLang.takeIf { it != UNKNOWN_LANG },
                targetLang,
                host,
                apiKey,
            ) ?: return null

            return texts.mapIndexed { index, original ->
                val detailed = detailedList.getOrElse(index) {
                    com.stellar.lang.plugin.google.GooglePlugin.GoogleTranslationResult(original, null)
                }
                val rawDetected = detailed.detectedSourceLanguage ?: detectedLang.takeIf { it != UNKNOWN_LANG }
                val effectiveDetected = resolveDeepLEffectiveLanguage(rawDetected, original, targetLang)
                val isSame = isSameLanguage(effectiveDetected, targetLang)
                val effectiveTrans = if (isSame) original else detailed.translatedText
                TranslationResult(
                    originalText = original,
                    translatedText = effectiveTrans,
                    detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                    targetLanguage = targetLang,
                    isSameLanguage = isSame,
                )
            }
        }

        return kotlinx.coroutines.runBlocking {
            val effectiveDetected = if (detectedLang == UNKNOWN_LANG) {
                val sample = texts.firstOrNull { it.any { ch -> ch.isLetter() } } ?: texts.first()
                detectLanguage(sample).takeIf { it != UNKNOWN_LANG }
            } else {
                detectedLang
            }
            val translatedList = provider.translateBatch(
                texts,
                effectiveDetected,
                targetLang,
            )
            val isFailure = translatedList == null ||
                isBatchUntranslatedFailure(texts, translatedList, effectiveDetected, targetLang)
            if (isFailure) {
                null
            } else {
                texts.mapIndexed { index, original ->
                    val trans = translatedList.getOrElse(index) { original }
                    val isSame = isSameLanguage(effectiveDetected, targetLang)
                    TranslationResult(
                        originalText = original,
                        translatedText = trans,
                        detectedLanguage = effectiveDetected ?: UNKNOWN_LANG,
                        targetLanguage = targetLang,
                        isSameLanguage = isSame,
                    )
                }
            }
        }
    }

    @Suppress(
        "ComplexCondition",
        "CyclomaticComplexMethod",
        "ReturnCount",
        "MagicNumber",
        "UnnecessaryParentheses",
    )
    private fun resolveDeepLEffectiveLanguage(
        rawDetected: String?,
        text: String,
        targetLang: String,
    ): String? {
        val cleanDetected = rawDetected?.trim()?.lowercase()?.substringBefore('-')
        val isTargetEn = isSameLanguage(targetLang, "en")
        val hasMarkers = com.stellar.lang.plugin.LanguageDetectionHelper.hasForeignMarkers(text)
        val isSlang = com.stellar.lang.plugin.LanguageDetectionHelper.isUniversalSlang(text)
        val quickLang = com.stellar.lang.plugin.LanguageDetectionHelper.detectQuick(text)

        if (isTargetEn && !hasMarkers) {
            if (cleanDetected == null || cleanDetected == UNKNOWN_LANG) {
                return "en"
            }
            if (cleanDetected != "en") {
                val isShort = text.length <= SHORT_TEXT_THRESHOLD ||
                    text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size <= SHORT_TEXT_WORD_COUNT
                val hasRepeating = text.contains(Regex("(.)\\1{2,}"))
                val isQuickEn = quickLang == "en"
                val isQuickMatch = quickLang != null && quickLang == cleanDetected
                val isCommonEuro = cleanDetected in setOf("fr", "de", "es")

                if (isSlang || isQuickEn || hasRepeating || isShort && !isQuickMatch && !isCommonEuro) {
                    return "en"
                }
            }
        }

        if (cleanDetected != null && cleanDetected != "en" && !hasMarkers) {
            val isQuickEn = quickLang == "en"
            if (isSlang || isQuickEn) {
                return "en"
            }
        }

        return cleanDetected ?: rawDetected
    }

    private fun isNonRetryableException(ex: Throwable?): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            val isFatal = when (current) {
                is java.net.ConnectException,
                is java.net.UnknownHostException,
                is java.net.PortUnreachableException,
                -> true
                else -> false
            }
            if (isFatal) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun translateBatchAsync(texts: List<String>, callback: (List<TranslationResult>?) -> Unit) {
        executor.execute {
            val results = translateBatchSync(texts)
            callback(results)
        }
    }

    private fun executeTranslation(
        text: String,
        host: String,
        apiKey: String,
        targetLang: String,
    ): TranslationResult? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host))
            val jsonPayload = buildPayload(text, apiKey, targetLang)

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                lastNetworkException = null
                val parsed = parseSingleResponse(response.body(), text, targetLang)
                if (parsed != null && isUntranslatedFailure(parsed)) {
                    logger.warn(
                        "LibreTranslate returned untranslated text for '{}' despite detected language '{}'",
                        text,
                        parsed.detectedLanguage,
                    )
                    null
                } else {
                    parsed
                }
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                logger.warn("LibreTranslate responded with HTTP {}: {}", response.statusCode(), response.body())
                val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyHttpStatus(
                    providerId = LIBRETRANSLATE_PROVIDER_ID,
                    statusCode = response.statusCode(),
                    responseBody = response.body(),
                )
                com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
                null
            }
        }.getOrElse { ex ->
            lastNetworkException = ex
            val errorDetail = ex.message ?: ex::class.simpleName ?: ex.toString()
            logger.warn("LibreTranslate request failed for '{}': {}", text, errorDetail)
            val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyException(
                providerId = LIBRETRANSLATE_PROVIDER_ID,
                throwable = ex,
                host = host,
            )
            com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
            null
        }
    }

    private fun executeBatchTranslation(
        texts: List<String>,
        host: String,
        apiKey: String,
        targetLang: String,
    ): List<TranslationResult>? {
        return runCatching {
            val endpoint = URI.create(normalizeEndpoint(host))
            val jsonPayload = buildBatchPayload(texts, apiKey, targetLang)

            val request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                .header(HEADER_ACCEPT, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                lastNetworkException = null
                parseBatchResponse(response.body(), texts, targetLang)
            } else {
                if (response.statusCode() == HTTP_TOO_MANY_REQUESTS) {
                    TranslationCache.tripCircuitBreaker()
                }
                val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyHttpStatus(
                    providerId = LIBRETRANSLATE_PROVIDER_ID,
                    statusCode = response.statusCode(),
                    responseBody = response.body(),
                )
                com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
                null
            }
        }.onFailure { ex ->
            lastNetworkException = ex
            val errorDetail = ex.message ?: ex::class.simpleName ?: ex.toString()
            logger.warn("LibreTranslate batch request failed for {} items: {}", texts.size, errorDetail)
            val errorInfo = com.stellar.lang.error.TranslationErrorClassifier.classifyException(
                providerId = LIBRETRANSLATE_PROVIDER_ID,
                throwable = ex,
                host = host,
            )
            com.stellar.lang.error.TranslationErrorNotifier.notifyErrorOnce(errorInfo)
        }.getOrNull()
    }

    private fun normalizeEndpoint(host: String): String {
        var trimmed = host.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return if (trimmed.endsWith("/translate")) trimmed else "$trimmed/translate"
    }

    private fun buildPayload(text: String, apiKey: String, targetLang: String): String {
        val root = JsonObject()
        root.addProperty("q", text)
        root.addProperty("source", "auto")
        root.addProperty("target", targetLang)
        root.addProperty("format", "text")
        if (apiKey.isNotBlank()) {
            root.addProperty("api_key", apiKey)
        }
        return gson.toJson(root)
    }

    private fun buildBatchPayload(texts: List<String>, apiKey: String, targetLang: String): String {
        val root = JsonObject()
        val array = JsonArray()
        texts.forEach { array.add(it) }
        root.add("q", array)
        root.addProperty("source", "auto")
        root.addProperty("target", targetLang)
        root.addProperty("format", "text")
        if (apiKey.isNotBlank()) {
            root.addProperty("api_key", apiKey)
        }
        return gson.toJson(root)
    }

    fun parseSingleResponse(body: String, originalText: String, targetLang: String): TranslationResult? {
        return runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            val transElem = json.get("translatedText") ?: return null
            val translated = if (transElem.isJsonArray) {
                transElem.asJsonArray.firstOrNull()?.asString ?: return null
            } else {
                transElem.asString
            }
            val rawDetected = parseDetectedLanguage(json)
            val effectiveDetected = if (rawDetected == UNKNOWN_LANG && isSameLanguage(targetLang, "en") &&
                !com.stellar.lang.plugin.LanguageDetectionHelper.hasForeignMarkers(originalText)
            ) {
                "en"
            } else {
                rawDetected
            }
            val isSame = isSameLanguage(effectiveDetected, targetLang)
            val effectiveTranslated = if (isSame) originalText else translated
            TranslationResult(
                originalText = originalText,
                translatedText = effectiveTranslated,
                detectedLanguage = effectiveDetected,
                targetLanguage = targetLang,
                isSameLanguage = isSame,
            )
        }.getOrNull()
    }

    private fun parseBatchResponse(
        body: String,
        originalTexts: List<String>,
        targetLang: String,
    ): List<TranslationResult>? {
        return runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            val translatedArray = json.get("translatedText")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val detectedArray = json.get("detectedLanguage")?.takeIf { it.isJsonArray }?.asJsonArray
            val defaultDetected = parseDetectedLanguage(json)

            originalTexts.mapIndexed { index, orig ->
                val trans = translatedArray.get(index)?.asString ?: orig
                val rawDetected = if (detectedArray != null && index < detectedArray.size()) {
                    extractLanguageFromElement(detectedArray.get(index))
                } else {
                    defaultDetected
                }
                val effectiveDetected = if (rawDetected == UNKNOWN_LANG && isSameLanguage(targetLang, "en") &&
                    !com.stellar.lang.plugin.LanguageDetectionHelper.hasForeignMarkers(orig)
                ) {
                    "en"
                } else {
                    rawDetected
                }
                val isSame = isSameLanguage(effectiveDetected, targetLang)
                val effectiveTrans = if (isSame) orig else trans
                val result = TranslationResult(orig, effectiveTrans, effectiveDetected, targetLang, isSame)
                TranslationCache.put(result)
                result
            }
        }.getOrNull()
    }

    private fun extractLanguageFromElement(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return UNKNOWN_LANG
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val lang = obj.get("language")?.asString ?: return UNKNOWN_LANG
            val confidence = obj.get("confidence")?.asFloat ?: DEFAULT_CONFIDENCE
            if (confidence >= MIN_LIBRE_CONFIDENCE &&
                lang in com.stellar.lang.plugin.LanguageDetectionHelper.SUPPORTED_LANGUAGES
            ) {
                return lang
            }
            return UNKNOWN_LANG
        }
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val lang = element.asString
            return if (lang in com.stellar.lang.plugin.LanguageDetectionHelper.SUPPORTED_LANGUAGES) {
                lang
            } else {
                UNKNOWN_LANG
            }
        }
        return UNKNOWN_LANG
    }

    private fun parseDetectedLanguage(obj: JsonObject): String {
        val element: JsonElement = obj.get("detectedLanguage") ?: return UNKNOWN_LANG
        if (element.isJsonArray) {
            val array = element.asJsonArray
            if (!array.isEmpty) {
                return extractLanguageFromElement(array.get(0))
            }
        }
        return extractLanguageFromElement(element)
    }

    fun isThrottled(key: String): Boolean = TranslationCache.isThrottled(key)

    fun isFailed(text: String): Boolean = isFailed(text, getTargetLanguage())

    fun isFailed(text: String, targetLang: String): Boolean {
        val key = cacheKey(text, targetLang)
        if (TranslationCache.isFailed(key) || TranslationCache.isCircuitBreakerOpen()) return true
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text).trim()
        if (clean != text.trim()) {
            val cleanKey = cacheKey(clean, targetLang)
            return TranslationCache.isFailed(cleanKey)
        }
        return false
    }

    fun markFailed(text: String, targetLang: String = getTargetLanguage()) {
        val key = cacheKey(text, targetLang)
        TranslationCache.markFailed(key)
        val existing = failedRequests[key]
        val attempt = (existing?.attemptCount ?: 0) + 1
        failedRequests[key] = FailedRequest(text, targetLang, System.currentTimeMillis(), attempt)
    }

    fun cacheKey(text: String, targetLang: String): String = TranslationCache.cacheKey(text, targetLang)

    fun addSuccessListener(listener: (TranslationResult) -> Unit) {
        successListeners.add(listener)
    }

    fun removeSuccessListener(listener: (TranslationResult) -> Unit) {
        successListeners.remove(listener)
    }

    internal fun notifySuccess(result: TranslationResult) {
        for (listener in successListeners) {
            runCatching { listener(result) }
        }
    }

    fun retryFailedTranslations() {
        if (TranslationCache.isCircuitBreakerOpen()) return
        val transStatus = PluginRegistry.getActiveTranslator().getStatus()
        val detStatus = PluginRegistry.getActiveDetector().getStatus()
        if (transStatus is PluginStatus.Downloading || detStatus is PluginStatus.Downloading) return

        val currentTargetLang = getTargetLanguage()
        val now = System.currentTimeMillis()
        val entriesToRetry = failedRequests.entries.filter { (key, req) ->
            val cooldown = TranslationCache.getCooldownMs(key)
            req.targetLang == currentTargetLang && now - req.failedAt >= cooldown
        }

        for ((key, req) in entriesToRetry) {
            failedRequests.remove(key)
            translateAsync(req.text) { /* completion triggers notifySuccess or re-registers failedRequest */ }
        }
    }

    @Suppress("ReturnCount")
    fun detectLanguageQuick(text: String): String? {
        val clean = com.stellar.lang.format.FormattingTagHelper.stripFormattingAndTags(text).trim()
        if (clean.isEmpty()) return null
        if (com.stellar.lang.plugin.LanguageDetectionHelper.isUniversalSlang(clean)) {
            return getTargetLanguage()
        }
        val cached = TranslationCache.get(clean, getTargetLanguage())
        if (cached != null) return cached.detectedLanguage
        val quick = com.stellar.lang.plugin.LanguageDetectionHelper.detectQuick(clean)
        if (quick != null) return quick
        val detector = PluginRegistry.getActiveDetector()
        return (detector as? com.stellar.lang.plugin.onnx.OnnxLanguageDetectorPlugin)
            ?.let { com.stellar.lang.plugin.onnx.OnnxInferenceEngine.detectLanguage(clean) }
    }

    fun clearCache() {
        TranslationCache.clear()
        failedRequests.clear()
        deepLQueue.clear()
        googleQueue.clear()
    }

    fun clearAllCaches() {
        clearCache()
        TranslationCache.clearDiskCache()
        com.stellar.lang.chat.ChatTranslationManager.clearCache()
        com.stellar.lang.sign.SignTranslationManager.clearCache()
        com.stellar.lang.item.ItemTranslationManager.clearCache()
        com.stellar.lang.entity.EntityTranslationManager.clearCache()
        com.stellar.lang.container.ContainerTranslationManager.clearCache()
        com.stellar.lang.book.BookTranslationManager.clearCache()
        com.stellar.lang.map.MapBannerTranslationManager.clearCache()
        com.stellar.lang.motd.ServerMotdTranslationManager.clearCache()
    }

    fun testConnection(
        host: String,
        apiKey: String,
        targetLang: String = getTargetLanguage(),
        callback: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val outcome = runCatching {
                val endpoint = URI.create(normalizeEndpoint(host))
                val jsonPayload = buildPayload("Hello", apiKey, targetLang)

                val request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .header(HEADER_ACCEPT, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in HTTP_OK_MIN..HTTP_OK_MAX) {
                    val parsed = parseSingleResponse(response.body(), "Hello", targetLang)
                    parsed?.translatedText ?: "OK (HTTP ${response.statusCode()})"
                } else {
                    val msg = runCatching {
                        JsonParser.parseString(response.body()).asJsonObject.get("error")?.asString
                    }.getOrNull() ?: response.body().take(ERROR_SNIPPET_LENGTH)
                    error("HTTP ${response.statusCode()}: $msg")
                }
            }
            callback(outcome)
        }
    }

    fun testOnnx(
        targetLang: String = getTargetLanguage(),
        callback: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val outcome = runCatching {
                if (!com.stellar.lang.plugin.onnx.OnnxInferenceEngine.isEnvironmentAvailable()) {
                    error("ONNX Runtime native environment unavailable")
                }
                val hasDetection = com.stellar.lang.plugin.onnx.OnnxModelManager.isDetectionModelReady()
                val hasTranslation = com.stellar.lang.plugin.onnx.OnnxModelManager.isTranslationModelReady(targetLang)
                if (!hasDetection && !hasTranslation) {
                    error("No ONNX models found in storage directory")
                }
                val results = mutableListOf<String>()
                if (hasDetection) {
                    val detected = com.stellar.lang.plugin.onnx.OnnxInferenceEngine.detectLanguage(
                        "Bonjour tout le monde",
                    )
                    results.add("Det: 'Bonjour' -> ${detected ?: "unknown"}")
                }
                if (hasTranslation) {
                    if (com.stellar.lang.plugin.onnx.OnnxInferenceEngine.isTranslationModelGenerative(targetLang)) {
                        val translated = com.stellar.lang.plugin.onnx.OnnxInferenceEngine.translate(
                            "Hello world",
                            "en",
                            targetLang,
                        )
                        results.add("Trans: 'Hello' -> ${translated ?: "null"}")
                    } else {
                        results.add("Trans: encoder-only ($targetLang)")
                    }
                }
                results.joinToString("; ")
            }
            callback(outcome)
        }
    }

    fun testDeepl(
        apiKey: String,
        host: String = "auto",
        targetLang: String = getTargetLanguage(),
        callback: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val plugin = com.stellar.lang.plugin.deepl.DeepLPlugin(httpClient)
            val result = plugin.testConnection(apiKey, host, targetLang)
            callback(result)
        }
    }

    fun testGoogle(
        apiKey: String,
        host: String = "auto",
        targetLang: String = getTargetLanguage(),
        callback: (Result<String>) -> Unit,
    ) {
        executor.execute {
            val plugin = com.stellar.lang.plugin.google.GooglePlugin(httpClient)
            val result = plugin.testConnection(apiKey, host, targetLang)
            callback(result)
        }
    }
}
