package com.example.EdgeMemo.ai.embedding

import com.example.EdgeMemo.core.common.EdgeError
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deterministic, framework-free offline embedding. Sublinear-TF weighted word
 * unigrams, word bigrams and character n-grams are projected into a fixed
 * dimension via the hashing trick and L2-normalized. The output is a real
 * function of the input text (identical text -> identical vector) and compares
 * by cosine over genuine term overlap. This is a lexical baseline, not a
 * neural model; it can be swapped behind [EmbeddingService] later.
 */
class FeatureHashingEmbeddingService(
    override val dimension: Int = 512,
    private val charGramMin: Int = 3,
    private val charGramMax: Int = 4,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EmbeddingService {

    private val tokenRegex = Regex("[a-z0-9]+(?:[-_/][a-z0-9]+)*")
    private val whitespaceRegex = Regex("\\s+")

    override suspend fun embed(text: String): FloatArray {
        return withContext(dispatcher) {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            throw EdgeError.InvalidInput("cannot embed empty text")
        }
        val features = LinkedHashMap<String, Int>()
        countFeatures(normalized, features)
        val vector = FloatArray(dimension)
        for ((feature, frequency) in features) {
            val weight = 1.0 + ln(frequency.toDouble())
            val (bucket, sign) = FeatureHash.bucket(feature, dimension)
            vector[bucket] += (sign * weight).toFloat()
        }
        l2Normalize(vector)
        vector
    }
    }

    private fun normalize(text: String): String = text.trim().replace(whitespaceRegex, " ")

    private fun countFeatures(normalized: String, counts: MutableMap<String, Int>) {
        val tokens = buildList {
            for (match in tokenRegex.findAll(normalized)) add(match.value)
        }
        for (token in tokens) bump(counts, "w:$token")
        if (tokens.size > 1) {
            for (i in 0 until tokens.size - 1) {
                bump(counts, "w2:${tokens[i]}_${tokens[i + 1]}")
            }
        }
        val lettersAndDigits = buildString {
            for (character in normalized) if (character.isLetterOrDigit()) append(character)
        }
        if (lettersAndDigits.length >= charGramMin) {
            for (size in charGramMin..charGramMax) {
                for (i in 0..lettersAndDigits.length - size) {
                    bump(counts, "c$size:${lettersAndDigits.substring(i, i + size)}")
                }
            }
        }
    }

    private fun bump(counts: MutableMap<String, Int>, feature: String) {
        counts[feature] = (counts[feature] ?: 0) + 1
    }

    private fun l2Normalize(vector: FloatArray) {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value
        val norm = sqrt(sum)
        if (norm > 0.0) {
            for (i in vector.indices) vector[i] = (vector[i] / norm.toFloat())
        }
    }
}

internal object FeatureHash {
    private const val FNV_OFFSET_BASIS_64 = -3750763034362895579L
    private const val FNV_PRIME_64 = 1099511628211L

    fun hash(feature: String): Long {
        var hash = FNV_OFFSET_BASIS_64
        for (character in feature) {
            hash = hash xor character.code.toLong()
            hash *= FNV_PRIME_64
        }
        return hash
    }

    fun bucket(feature: String, dimension: Int): Pair<Int, Float> {
        val hash = hash(feature)
        val mixed = hash xor (hash ushr 33)
        val index = ((mixed and 0x7fffffffL) % dimension).toInt()
        val sign = if (hash < 0L) -1f else 1f
        return index to sign
    }
}