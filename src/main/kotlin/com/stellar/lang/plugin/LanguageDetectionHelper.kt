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
        // Common pronouns, question words, verbs, and gamer terms
        "i", "you", "u", "he", "she", "it", "we", "they", "me", "my", "your", "ur",
        "what", "why", "who", "when", "where", "how", "which",
        "do", "dont", "don't", "did", "does",
        "go", "going", "gone", "went", "come", "coming", "came",
        "get", "got", "give", "take", "make", "need", "want", "like", "see", "look",
        "help", "kill", "die", "dead", "mine", "mining", "craft", "build", "base", "spawn",
        "here", "there", "now", "wait", "again", "too", "also", "so", "very",
        "some", "any", "no", "not", "all", "one", "two", "more", "much", "many",
        "just", "only", "back", "bro", "dude", "man", "guy", "guys",
    )

    val UNIVERSAL_SLANG: Set<String> = setOf(
        "ok", "okay", "k", "kk", "lol", "lmao", "lmfao", "rofl", "roflmao", "xd", "gg", "ggwp",
        "glhf", "gl", "hf", "wp", "afk", "brb", "omg", "omfg", "wtf", "wth", "idk", "idc",
        "tbh", "imo", "imho", "np", "ty", "thx", "pls", "plz", "yw", "gn", "gm", "o7",
        "bruh", "rip", "pog", "poggers", "f", "cap", "no cap", "fr", "frfr", "sus", "gtg", "g2g",
        "bbl", "smh", "fyi", "btw", "ffs", "oof", "ez", "ezpz", "mb", "nvm", "rn", "ikr",
        "sup", "cya", "l8r", "wb", "kek", "kekw", "lul", "lulz",
        "haha", "hahaha", "hahahaha", "hehe", "hehehe", "lolol", "lololol", "cool", "nice",
        "wow", "yay", "yup", "yep", "nope", "nah", "yes", "no", "hi", "bye", "hey",
    )

    val KNOWN_TEXTMOJIS: Set<String> = setOf(
        // Shrugs & variants
        "¯\\_(ツ)_/¯", "¯_(ツ)_/¯", "¯\\(ツ)/¯", "乁(ツ)ㄏ", "┐(￣ヘ￣)┌", "¯\\_(⊙_ʖ⊙)_/¯", "¯\\_( ͡° ͜ʖ ͡°)_/¯",
        // Lenny Face & variants
        "( ͡° ͜ʖ ͡°)", "( ͡ಥ ͜ʖ ͡ಥ)", "(ง ͠° ͟ل͜ ͡°)ง", "(͡° ͜ʖ ͡°)", "( ͡° ʖ̯ ͡°)", "( ͡~ ͜ʖ ͡°)", "( ͡o ͜ʖ ͡o)",
        // Look of Disapproval & variants
        "ಠ_ಠ", "ಠ╭╮ಠ", "ಠ益ಠ", "ಠ_ಥ", "ಠ~ಠ", "(ಠ_ಠ)", "(ಠ_ಥ)",
        // Table flips & un-flips
        "(╯°□°)╯︵ ┻━┻", "(╯°□°）╯︵ ┻━┻", "(ノಠ益ಠ)ノ彡┻━┻", "┬─┬ノ( º _ ºノ)", "┬─┬ノ(º_ºノ)",
        "┻━┻︵ヽ(`Д´)ﾉ︵ ┻━┻", "(╯°Д°）╯︵/(.□ . \\)", "(ノ°Д°）ノ︵ ┻━┻", "┻━┻ ︵ ＼( °□° )／ ︵ ┻━┻",
        // Cute / Happy / Hugs
        "(◕‿◕)", "(◕‿◕✿)", "(＾▽＾)", "(＾◡＾)", "(づ｡◕‿‿◕｡)づ", "(つ≧▽≦)つ", "(人◕‿◕)", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧",
        "(｡◕‿◕｡)", "(✿◠‿◠)", "(◡‿◡✿)", "(◠‿◠)", "(^▽^)", "(^◡^)",
        // Animals
        "ʕ•ᴥ•ʔ", "ʕ ᵔᴥᵔ ʔ", "(=^･^=)", "(=^･ｪ･^=)", "(=^‥^=)", "( =①ω①=)", "(ᵔᴥᵔ)", "(=^..^=)",
        // Crying / Sad / Tears
        "(T_T)", "(TдT)", "(╥﹏╥)", "(︶︹︶)", "(;_;)", "(ToT)", "(｡•́︿•̀｡)", "(ಥ﹏ಥ)", "(T-T)",
        // Flex / Fighting
        "୧( ˵ ° ~ ° ˵ )୨", "ᕦ(ò_óˇ)ᕤ", "ᕙ(⇀‸↼‶)ᕗ", "ᕙ(▀̿̿Ĺ̯̿̿▀̿ ̿)ᕗ", "ᕦ( ͡° ͜ʖ ͡°)ᕤ",
        // Sweat / Nervous / Surprised
        "(・_・;)", "(・_・;)ゞ", "(；・∀・)", "(°o°)", "(o_O)", "(O_o)",
        // Common horizontal text faces
        "^_^", "^.^", "^^", "^^'", "-_-", "-__-", "-.-", "._.", ">_<", ">.<", ">_>", "<_<",
        "o_o", "o_O", "O_o", "O_O", "o.o", "O.O", "0_0", "0.0", "x_x", "X_X", "x.x", "X.X",
        "T_T", "T.T", "Q_Q", "Q.Q", ";_;", ";-;", ";.;", "+_+", "*_*", "@_@", "u_u", "U_U",
        // Discord/Twitch style text emotes
        ":pepe:", ":kekw:", ":pog:", ":poggers:", ":monkas:", ":kappa:", ":lul:", ":residentleeper:",
        ":5head:", ":ez:", ":sadge:", ":ayaya:", ":copium:", ":hopium:", ":clueless:",
    )

    val WESTERN_EMOTICON_REGEX = Regex(
        """(?i)^[:;=8xX%][\-~o*']?[)\](\[dDpPoO/\\|}{]+$""",
    )

    val GESTURE_EMOTICON_REGEX = Regex(
        """^(?:<3|</3|\\o/|o/|\\o|o7|O7|v\.v|V\.V|d\[\-_-\]b)$""",
    )

    val HORIZONTAL_FACE_REGEX = Regex(
        """^(?:[\^~><\-][._\-oO0][\^~><\-]|(?:[oO0xX+*@uUTTQ;\-_][._\-][oO0xX+*@uUTTQ;\-_])|(?:\^[._\-]?\^'?))$""",
    )

    val DISCORD_EMOTE_REGEX = Regex(
        """^:[a-zA-Z0-9_+\-]+:$""",
    )

    val LAUGH_EXPRESSION_REGEX = Regex(
        """(?i)^(?:(?:ha|he|ja|lo|kek|lul|xd)+l*|x+d+|a*ha+h*|j+a+j+a*|k+e+k+w*)$""",
    )

    private val KAOMOJI_CHARS = setOf(
        '°', 'º', 'ಠ', 'ツ', '益', 'Д', 'д', 'ω', '◕', '◡', '‿', 'ᴥ', 'ʖ', 'ノ', '︵', '彡', '✧',
        '人', 'ヘ', '▽', 'ヮ', '✿', '◠', '◡', 'ಥ', '╥', '｡', '́', '̀', 'ʕ', 'ʔ', 'ᕦ', 'ᕤ', 'ᕙ', 'ᕗ',
        '乁', 'ㄏ', '┐', '┌', '┴', '┬', '━', '─', 'ヽ', 'ﾉ', '˵', 'Ĺ', '̯', '̿',
    )

    private const val MIN_SCORE_MARGIN = 1.8f
    private const val SHORT_TEXT_MIN_MARGIN = 2.2f
    private const val NON_ENGLISH_PLAIN_MARGIN = 1.5f

    fun normalizeRepeatedCharacters(raw: String): String {
        return raw.replace(Regex("(?i)(.)\\1{2,}")) { it.groupValues[1] }
    }

    fun isEmoticonOrKaomoji(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed in KNOWN_TEXTMOJIS) return true
        if (trimmed.lowercase(Locale.ROOT) in KNOWN_TEXTMOJIS) return true

        if (WESTERN_EMOTICON_REGEX.matches(trimmed)) return true
        if (GESTURE_EMOTICON_REGEX.matches(trimmed)) return true
        if (HORIZONTAL_FACE_REGEX.matches(trimmed)) return true
        if (DISCORD_EMOTE_REGEX.matches(trimmed)) return true
        if (LAUGH_EXPRESSION_REGEX.matches(trimmed)) return true

        val strippedSurround = trimmed.trim('~', ' ', '\t')
        val unwrapped = if ((strippedSurround.startsWith("(") && strippedSurround.endsWith(")")) ||
            (strippedSurround.startsWith("[") && strippedSurround.endsWith("]")) ||
            (strippedSurround.startsWith("（") && strippedSurround.endsWith("）"))
        ) {
            strippedSurround.substring(1, strippedSurround.length - 1).trim()
        } else {
            null
        }
        if (unwrapped != null && (HORIZONTAL_FACE_REGEX.matches(unwrapped) || WESTERN_EMOTICON_REGEX.matches(unwrapped))) {
            return true
        }

        val startsKaomoji = trimmed.startsWith("(") || trimmed.startsWith("[") || trimmed.startsWith("（") ||
            trimmed.startsWith("¯\\_") || trimmed.startsWith("乁") || trimmed.startsWith("┐") ||
            trimmed.startsWith("ʕ") || trimmed.startsWith("ᕦ") || trimmed.startsWith("ᕙ") ||
            trimmed.startsWith("(づ") || trimmed.startsWith("(つ") || trimmed.startsWith("┻━┻") ||
            trimmed.startsWith("┬─┬") || trimmed.startsWith("ノ")
        val endsKaomoji = trimmed.endsWith(")") || trimmed.endsWith("]") || trimmed.endsWith("）") ||
            trimmed.endsWith("_/¯") || trimmed.endsWith("/¯") || trimmed.endsWith("ㄏ") ||
            trimmed.endsWith("┌") || trimmed.endsWith("ʔ") || trimmed.endsWith("ᕤ") ||
            trimmed.endsWith("ᕗ") || trimmed.endsWith(")づ") || trimmed.endsWith(")つ") ||
            trimmed.endsWith("┻━┻") || trimmed.endsWith("ノ")

        if (startsKaomoji && endsKaomoji && trimmed.any { it in KAOMOJI_CHARS }) {
            return true
        }

        if (trimmed.any { it in KAOMOJI_CHARS } && trimmed.none { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
            return true
        }

        return false
    }

    fun stripKaomojisAndEmoticons(text: String): String {
        var result = text
        for (emoji in KNOWN_TEXTMOJIS) {
            result = result.replace(emoji, " ")
        }
        val words = result.split(Regex("\\s+"))
        return words.filterNot { isEmoticonOrKaomoji(it) }.joinToString(" ")
    }

    fun protectTextmojisAndKaomojis(text: String): String {
        var result = text
        for (emoji in KNOWN_TEXTMOJIS) {
            if (result.contains(emoji)) {
                result = result.replace(emoji, "<ut>$emoji</ut>")
            }
        }
        return result
    }

    fun isUniversalSlang(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (isEmoticonOrKaomoji(trimmed)) return true
        val clean = cleanForDetection(raw)
        if (clean.isEmpty()) return false
        if (isEmoticonOrKaomoji(clean)) return true
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.all { word ->
            val norm = normalizeRepeatedCharacters(word)
            norm in UNIVERSAL_SLANG ||
                isEmoticonOrKaomoji(word) ||
                isEmoticonOrKaomoji(norm) ||
                isEmoticonOrLaugh(word) ||
                isEmoticonOrLaugh(norm)
        }
    }

    private fun isEmoticonOrLaugh(word: String): Boolean {
        return isEmoticonOrKaomoji(word)
    }

    /**
     * Attempts fast dictionary / script-based language identification.
     * Returns a 2-letter language code if high-confidence match is found, or null otherwise.
     */
    fun detectQuick(text: String): String? {
        val clean = cleanForDetection(text)
        if (clean.isEmpty()) return null

        // 1. Universal chat slang & emoticons (ok, lol, lmao, xd, etc.) -> treat as English
        if (isUniversalSlang(clean)) {
            return "en"
        }

        // 2. Direct dictionary match (including after collapsing repeated letters)
        val dictMatch = QUICK_DICTIONARY[clean] ?: QUICK_DICTIONARY[normalizeRepeatedCharacters(clean)]
        if (dictMatch != null) return dictMatch

        // 3. Non-Latin script detection
        val scriptLang = detectScript(clean)
        if (scriptLang != null) return scriptLang

        // 4. Short phrase function word heuristics
        val normalized = normalizeRepeatedCharacters(clean)
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val normWords = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size in 1..15) {
            val frenchHits = words.count { it in FRENCH_SPECIFIC_WORDS } +
                normWords.count { it in FRENCH_SPECIFIC_WORDS && it !in words }
            if (frenchHits >= 2 || (frenchHits >= 1 && (clean.contains("è") || clean.contains("é") || clean.contains("ê")))) {
                return "fr"
            }
            val englishHits = words.count { it in ENGLISH_SPECIFIC_WORDS } +
                normWords.count { it in ENGLISH_SPECIFIC_WORDS && it !in words }
            val threshold = when {
                words.size <= 2 -> 1
                words.size <= 6 -> 2
                else -> 3
            }
            if (englishHits >= threshold) {
                return "en"
            }
        }

        return null
    }

    fun hasForeignMarkers(text: String): Boolean {
        if (isEmoticonOrKaomoji(text)) return false
        val stripped = stripKaomojisAndEmoticons(text)
        if (detectScript(stripped) != null) return true
        val clean = stripped.lowercase(Locale.ROOT)
        return clean.any { it in "éèêëàâùûôîïçœñ¿¡äöüßøåæ" }
    }

    private fun cleanForDetection(raw: String): String {
        val trimmed = raw.trim().trimEnd('_').trim()
        if (isEmoticonOrKaomoji(trimmed)) return trimmed.lowercase(Locale.ROOT)
        return trimmed
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
        if (isUniversalSlang(clean)) return "en"

        val hasFrenchAccents = clean.any { it in "éèêëàâùûôîïçœ" }
        val hasSpanishAccents = clean.any { it in "ñ¿¡áíóú" }
        val hasGermanUmlauts = clean.any { it in "äöüß" }

        var bestIdx = -1
        var bestScore = Float.NEGATIVE_INFINITY
        var secondScore = Float.NEGATIVE_INFINITY

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
                secondScore = bestScore
                bestScore = score
                bestIdx = i
            } else if (score > secondScore) {
                secondScore = score
            }
        }

        if (bestIdx !in idToLanguage.indices) return null
        val bestLang = idToLanguage[bestIdx]

        if (bestLang !in SUPPORTED_LANGUAGES) return null

        val isShort = clean.length <= 25 || clean.split(Regex("\\s+")).size <= 3
        val hasRepeating = clean.contains(Regex("(.)\\1{2,}"))
        var requiredMargin = if (isShort || hasRepeating) SHORT_TEXT_MIN_MARGIN else MIN_SCORE_MARGIN
        if (bestLang != "en" && !hasForeignMarkers(clean)) {
            requiredMargin += NON_ENGLISH_PLAIN_MARGIN
        }

        return if (bestScore - secondScore >= requiredMargin) bestLang else null
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
