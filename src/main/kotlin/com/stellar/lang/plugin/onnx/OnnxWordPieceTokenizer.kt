@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
)

package com.stellar.lang.plugin.onnx

import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.io.File
import java.text.Normalizer

/**
 * Handles BERT-compatible WordPiece tokenization and vocabulary loading for ONNX models.
 */
internal object OnnxWordPieceTokenizer {
    private val logger = LoggerFactory.getLogger("StellarLang-WordPieceTokenizer")

    fun loadVocab(vocabFile: File): Map<String, Int>? {
        if (!vocabFile.exists() || vocabFile.length() == 0L) return null
        return runCatching {
            val jsonText = vocabFile.readText(Charsets.UTF_8)
            val jsonObject = JsonParser.parseString(jsonText).asJsonObject
            val modelObj = jsonObject.getAsJsonObject("model")
            val vocabElem = modelObj?.get("vocab")
            if (vocabElem != null && vocabElem.isJsonObject) {
                val vocabObj = vocabElem.asJsonObject
                val map = HashMap<String, Int>(vocabObj.size())
                for (entry in vocabObj.entrySet()) {
                    map[entry.key] = entry.value.asInt
                }
                map
            } else if (vocabElem != null && vocabElem.isJsonArray) {
                val vocabArray = vocabElem.asJsonArray
                if (vocabArray.size() > 0) {
                    val map = HashMap<String, Int>(vocabArray.size())
                    for (i in 0 until vocabArray.size()) {
                        val item = vocabArray[i]
                        if (item.isJsonArray) {
                            map[item.asJsonArray[0].asString] = i
                        }
                    }
                    map
                } else {
                    null
                }
            } else {
                null
            }
        }.onFailure { ex ->
            logger.warn("Failed to load tokenizer vocab from {}: {}", vocabFile.name, ex.message)
        }.getOrNull()
    }

    fun isSentencePiece(vocab: Map<String, Int>): Boolean =
        !vocab.containsKey("[CLS]") && (vocab.containsKey("</s>") || vocab.keys.any { it.startsWith("\u2581") })

    fun tokenizeSentencePiece(text: String, maxLen: Int, vocab: Map<String, Int>): LongArray {
        val eosId = (vocab["</s>"] ?: 0).toLong()
        val unkId = (vocab["<unk>"] ?: 1).toLong()
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tokens = mutableListOf<Long>()

        for (word in words) {
            if (tokens.size >= maxLen - 1) break
            val target = "\u2581$word"
            var start = 0
            val len = target.length
            while (start < len) {
                var end = len
                var foundId: Int? = null
                while (start < end) {
                    val sub = target.substring(start, end)
                    val id = vocab[sub]
                    if (id != null) {
                        foundId = id
                        break
                    }
                    end--
                }
                if (foundId != null) {
                    tokens.add(foundId.toLong())
                    start = end
                } else {
                    tokens.add(unkId)
                    start++
                }
                if (tokens.size >= maxLen - 1) break
            }
        }
        tokens.add(eosId)
        return tokens.take(maxLen).toLongArray()
    }

    fun normalize(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (ch in nfd) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun splitWordsAndPunctuation(text: String): List<String> {
        val result = mutableListOf<String>()
        val currentWord = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                currentWord.append(ch)
            } else {
                if (currentWord.isNotEmpty()) {
                    result.add(currentWord.toString())
                    currentWord.setLength(0)
                }
                if (!ch.isWhitespace()) {
                    result.add(ch.toString())
                }
            }
        }
        if (currentWord.isNotEmpty()) {
            result.add(currentWord.toString())
        }
        return result
    }

    fun tokenize(text: String, maxLen: Int, vocab: Map<String, Int>): LongArray {
        val clsId = (vocab["[CLS]"] ?: 1).toLong()
        val sepId = (vocab["[SEP]"] ?: 2).toLong()
        val unkId = (vocab["[UNK]"] ?: 0).toLong()

        val normalized = normalize(text)
        val words = splitWordsAndPunctuation(normalized)
        val tokens = mutableListOf<Long>()
        tokens.add(clsId)

        for (word in words) {
            if (tokens.size >= maxLen - 1) break
            val subTokens = tokenizeWord(word, vocab, unkId)
            tokens.addAll(subTokens)
        }
        tokens.add(sepId)
        return tokens.take(maxLen).toLongArray()
    }

    private fun tokenizeWord(word: String, vocab: Map<String, Int>, unkId: Long): List<Long> {
        var start = 0
        val subTokens = mutableListOf<Long>()
        while (start < word.length) {
            var end = word.length
            var curTokenId: Long? = null
            while (start < end) {
                var substr = word.substring(start, end)
                if (start > 0) {
                    substr = "##$substr"
                }
                val id = vocab[substr]
                if (id != null) {
                    curTokenId = id.toLong()
                    break
                }
                end--
            }
            if (curTokenId == null) {
                return listOf(unkId)
            }
            subTokens.add(curTokenId)
            start = end
        }
        return subTokens
    }

    fun detokenize(tokens: LongArray, vocab: Map<String, Int>): String {
        val idToToken = HashMap<Int, String>(vocab.size)
        for ((k, v) in vocab) {
            idToToken[v] = k
        }
        val isSp = isSentencePiece(vocab)
        val sb = StringBuilder()
        for (token in tokens) {
            val piece = idToToken[token.toInt()]
            if (piece != null && !isSpecialToken(piece)) {
                when {
                    piece.startsWith("##") -> sb.append(piece.substring(2))
                    piece.startsWith("\u2581") -> {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(piece.substring(1))
                    }
                    else -> {
                        val isPunct = piece.length == 1 && !piece[0].isLetterOrDigit()
                        val shouldPrependSpace = !isSp && sb.isNotEmpty()
                        if (shouldPrependSpace && !isPunct) {
                            sb.append(' ')
                        }
                        sb.append(piece)
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    private fun isSpecialToken(piece: String): Boolean =
        piece.startsWith("[") && piece.endsWith("]") ||
            piece.startsWith("<") && piece.endsWith(">")
}
