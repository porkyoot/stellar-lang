@file:Suppress(
    "MagicNumber",
    "ComplexCondition",
    "StringLiteralDuplication",
    "NestedBlockDepth",
    "ReturnCount",
    "MaxLineLength",
    "MaximumLineLength",
    "UnnecessaryParentheses",
    "LongMethod",
    "LargeClass",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
)

package com.stellar.lang.plugin

import java.util.Locale

/**
 * Provides robust language detection heuristics, supported language filtering,
 * and quick-lookup dictionaries for short texts and common greetings.
 */
object LanguageDetectionHelper {
    /**
     * Languages actively supported by Minecraft and mainstream translation providers.
     * Obscure dialects not in this set receive a logit penalty in neural language detection.
     */
    val SUPPORTED_LANGUAGES: Set<String> = setOf(
        "en", "fr", "es", "de", "it", "pt", "ru", "zh", "ja", "ko",
        "nl", "pl", "uk", "tr", "sv", "da", "fi", "no", "cs", "el",
        "hu", "ro", "ar", "id", "vi", "th", "hi", "he", "bg", "sk",
        "hr", "lt", "lv", "et", "sl", "ca",
    )

    private const val UNSUPPORTED_PENALTY = 6.0f
    private const val ACCENT_BONUS = 3.5f

    // Common greetings and short vocabulary mapped to ISO 639-1 language codes
    private val QUICK_DICTIONARY: Map<String, String> = mapOf(
        // French
        "bonjour" to "fr",
        "salut" to "fr",
        "merci" to "fr",
        "merci beaucoup" to "fr",
        "de rien" to "fr",
        "au revoir" to "fr",
        "bonne nuit" to "fr",
        "bonsoir" to "fr",
        "pantoufle" to "fr",
        "pantoufles" to "fr",
        "fantome" to "fr",
        "fantôme" to "fr",
        "fantomes" to "fr",
        "fantômes" to "fr",
        "s'il vous plait" to "fr",
        "s'il vous plaît" to "fr",
        "s'il te plait" to "fr",
        "s'il te plaît" to "fr",
        "a bientot" to "fr",
        "à bientôt" to "fr",
        "oui" to "fr",
        "non" to "fr",
        "pourquoi" to "fr",
        "comment" to "fr",

        // Spanish
        "hola" to "es",
        "buenos dias" to "es",
        "buenos días" to "es",
        "buenas tardes" to "es",
        "buenas noches" to "es",
        "gracias" to "es",
        "muchas gracias" to "es",
        "de nada" to "es",
        "por favor" to "es",
        "amigo" to "es",
        "amiga" to "es",
        "adios" to "es",
        "adiós" to "es",
        "hasta luego" to "es",
        "como estas" to "es",
        "cómo estás" to "es",
        "que tal" to "es",
        "qué tal" to "es",

        // German
        "hallo" to "de",
        "guten tag" to "de",
        "guten morgen" to "de",
        "guten abend" to "de",
        "gute nacht" to "de",
        "danke" to "de",
        "danke schon" to "de",
        "danke schön" to "de",
        "vielen dank" to "de",
        "bitte" to "de",
        "bitte schon" to "de",
        "bitte schön" to "de",
        "tschuss" to "de",
        "tschüss" to "de",
        "auf wiedersehen" to "de",
        "ja" to "de",
        "nein" to "de",

        // Italian
        "ciao" to "it",
        "buongiorno" to "it",
        "buonasera" to "it",
        "buonanotte" to "it",
        "grazie" to "it",
        "grazie mille" to "it",
        "prego" to "it",
        "arrivederci" to "it",

        // Portuguese
        "ola" to "pt",
        "olá" to "pt",
        "bom dia" to "pt",
        "boa tarde" to "pt",
        "boa noite" to "pt",
        "obrigado" to "pt",
        "obrigada" to "pt",
        "adeus" to "pt",

        // Russian
        "привет" to "ru",
        "здравствуйте" to "ru",
        "спасибо" to "ru",
        "пожалуйста" to "ru",
        "до свидания" to "ru",
        "пока" to "ru",
        "да" to "ru",
        "нет" to "ru",

        // English
        "hello" to "en",
        "hi" to "en",
        "hey" to "en",
        "good morning" to "en",
        "good afternoon" to "en",
        "good evening" to "en",
        "good night" to "en",
        "thank you" to "en",
        "thanks" to "en",
        "welcome" to "en",
        "goodbye" to "en",
        "bye" to "en",

        // Japanese
        "こんにちは" to "ja",
        "こんばんは" to "ja",
        "おはよう" to "ja",
        "ありがとう" to "ja",
        "さようなら" to "ja",

        // Chinese
        "你好" to "zh",
        "谢谢" to "zh",
        "再见" to "zh",
        "早上好" to "zh",
        "晚安" to "zh",

        // Korean
        "안녕하세요" to "ko",
        "감사합니다" to "ko",
        "안녕" to "ko",
        "고마워" to "ko",

        // Dutch
        "goedemorgen" to "nl",
        "goedemiddag" to "nl",
        "goedenavond" to "nl",
        "dank je" to "nl",
        "alsjeblieft" to "nl",

        // Polish
        "dzien dobry" to "pl",
        "dzień dobry" to "pl",
        "dziekuje" to "pl",
        "dziękuję" to "pl",
        "prosze" to "pl",
        "proszę" to "pl",
    )

    private val FRENCH_SPECIFIC_WORDS = setOf(
        "est", "pas", "une", "des", "les", "sur", "dans", "avec", "pour",
        "mère", "mere", "père", "pere", "paradis", "pluie", "tournepluie",
        "parasol", "maison", "chose", "arrière", "arriere", "panneau", "monde",
    )

    private val ENGLISH_SPECIFIC_WORDS = setOf(
        "the", "is", "are", "was", "were", "and", "or", "in", "on", "at", "to", "for",
        "with", "from", "by", "of", "about", "this", "that", "these", "those",
        "have", "has", "had", "will", "would", "can", "could", "should",
        "house", "home", "portal", "chest", "storage", "door", "bed", "box", "mail",
        "mailbox", "iron", "gold", "diamond", "sword", "axe", "pickaxe", "shovel",
        "stop", "fox", "sleepy", "castle", "outpost", "north", "south", "east", "west",
    )

    /**
     * Attempts fast dictionary / script-based language identification.
     * Returns a 2-letter language code if high-confidence match is found, or null otherwise.
     */
    fun detectQuick(text: String): String? {
        val clean = cleanForDetection(text)
        if (clean.isEmpty()) return null

        // 1. Direct dictionary match
        val dictMatch = QUICK_DICTIONARY[clean]
        if (dictMatch != null) return dictMatch

        // 2. Non-Latin script detection
        val scriptLang = detectScript(clean)
        if (scriptLang != null) return scriptLang

        // 3. Short phrase function word heuristics
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size in 1..10) {
            val englishHits = words.count { it in ENGLISH_SPECIFIC_WORDS }
            if (englishHits >= 2 || (englishHits >= 1 && words.size <= 2)) {
                return "en"
            }
            val frenchHits = words.count { it in FRENCH_SPECIFIC_WORDS }
            if (frenchHits >= 2 || (frenchHits >= 1 && (clean.contains("è") || clean.contains("é") || clean.contains("ê")))) {
                return "fr"
            }
        }

        return null
    }

    private fun cleanForDetection(raw: String): String {
        return raw.trim()
            .trimEnd('_') // Remove sign blinking cursor
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[!?,.:;\"'()\\[\\]{}]+$"), "")
            .trim()
    }

    private fun detectScript(text: String): String? {
        var hasHangul = false
        var hasKana = false
        var hasHanzi = false
        var hasCyrillic = false
        var hasArabic = false
        var hasHebrew = false
        var hasGreek = false
        var hasThai = false
        var hasDevanagari = false
        var hasUkrLetters = false

        for (ch in text) {
            val code = ch.code
            when {
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> hasHangul = true
                code in 0x3040..0x309F || code in 0x30A0..0x30FF -> hasKana = true
                code in 0x4E00..0x9FFF -> hasHanzi = true
                code in 0x0400..0x04FF -> {
                    hasCyrillic = true
                    if (ch == 'і' || ch == 'ї' || ch == 'є' || ch == 'ґ') hasUkrLetters = true
                }
                code in 0x0600..0x06FF -> hasArabic = true
                code in 0x0590..0x05FF -> hasHebrew = true
                code in 0x0370..0x03FF -> hasGreek = true
                code in 0x0E00..0x0E7F -> hasThai = true
                code in 0x0900..0x097F -> hasDevanagari = true
            }
        }

        return when {
            hasHangul -> "ko"
            hasKana -> "ja"
            hasHanzi -> "zh"
            hasCyrillic -> if (hasUkrLetters) "uk" else "ru"
            hasArabic -> "ar"
            hasHebrew -> "he"
            hasGreek -> "el"
            hasThai -> "th"
            hasDevanagari -> "hi"
            else -> null
        }
    }

    /**
     * Applies supported language priors and accent bonuses to raw neural network logits,
     * suppressing spurious activations for rare/obscure dialects.
     */
    fun selectBestLanguage(
        logits: FloatArray,
        idToLanguage: Array<String>,
        rawText: String,
    ): String? {
        if (logits.isEmpty() || idToLanguage.isEmpty()) return null

        val clean = rawText.lowercase(Locale.ROOT)
        val hasFrenchAccents = clean.any { it in "éèêëàâùûôîïçœ" }
        val hasSpanishAccents = clean.any { it in "ñ¿¡" }
        val hasGermanUmlauts = clean.any { it in "äöüß" }

        var bestIdx = -1
        var bestScore = Float.NEGATIVE_INFINITY

        for (i in logits.indices) {
            if (i !in idToLanguage.indices) continue
            val lang = idToLanguage[i]
            var score = logits[i]

            // Apply penalty if language is not a supported Minecraft/translation language
            if (lang !in SUPPORTED_LANGUAGES) {
                score -= UNSUPPORTED_PENALTY
            }

            // Accent-based boosts
            if (hasFrenchAccents && lang == "fr") score += ACCENT_BONUS
            if (hasSpanishAccents && lang == "es") score += ACCENT_BONUS
            if (hasGermanUmlauts && lang == "de") score += ACCENT_BONUS

            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }

        return if (bestIdx in idToLanguage.indices) idToLanguage[bestIdx] else null
    }

    /**
     * Translates common single words and greetings directly to English or other targets
     * when the target language is supported, avoiding neural model hallucinations.
     */
    fun getQuickTranslation(text: String, targetLang: String): String? {
        if (targetLang != "en") return null
        val clean = cleanForDetection(text)
        val isCapitalized = text.trim().firstOrNull()?.isUpperCase() == true

        val translated = when (clean) {
            "bonjour" -> "Hello"
            "salut" -> "Hi"
            "merci" -> "Thank you"
            "merci beaucoup" -> "Thank you very much"
            "de rien" -> "You're welcome"
            "au revoir" -> "Goodbye"
            "bonne nuit" -> "Good night"
            "bonsoir" -> "Good evening"
            "s'il vous plait", "s'il vous plaît", "s'il te plait", "s'il te plaît" -> "Please"
            "oui" -> "Yes"
            "non" -> "No"
            "hola" -> "Hello"
            "gracias" -> "Thank you"
            "muchas gracias" -> "Thank you very much"
            "de nada" -> "You're welcome"
            "por favor" -> "Please"
            "adios", "adiós" -> "Goodbye"
            "buenas noches" -> "Good night"
            "buenos dias", "buenos días" -> "Good morning"
            "hallo" -> "Hello"
            "danke" -> "Thank you"
            "vielen dank" -> "Thank you very much"
            "bitte" -> "Please"
            "tschuss", "tschüss" -> "Bye"
            "auf wiedersehen" -> "Goodbye"
            "ciao" -> "Hello"
            "grazie" -> "Thank you"
            "prego" -> "You're welcome"
            "buongiorno" -> "Good morning"
            "buonanotte" -> "Good night"
            "ola", "olá" -> "Hello"
            "obrigado", "obrigada" -> "Thank you"
            "привет" -> "Hello"
            "спасибо" -> "Thank you"
            "пожалуйста" -> "Please"
            else -> null
        } ?: return null

        return if (isCapitalized) translated else translated.lowercase(Locale.ROOT)
    }
}
