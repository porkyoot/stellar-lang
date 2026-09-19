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
            val vocabObj = modelObj?.getAsJsonObject("vocab")
            if (vocabObj != null) {
                val map = HashMap<String, Int>(vocabObj.size())
                for (entry in vocabObj.entrySet()) {
                    map[entry.key] = entry.value.asInt
                }
                map
            } else {
                null
            }
        }.onFailure { ex ->
            logger.warn("Failed to load tokenizer vocab from {}: {}", vocabFile.name, ex.message)
        }.getOrNull()
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
}
