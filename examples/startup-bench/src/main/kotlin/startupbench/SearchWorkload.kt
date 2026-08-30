package startupbench

import kotlin.math.ln

/**
 * Deterministic in-process search after first frame.
 *
 * Not Lucene — a Lucene dependency would move the Hello World RSS baseline
 * and needs native-image metadata. The contract is: tokenize, invert, score.
 * A production app writes the same `workload.json` schema after its own
 * Lucene (or other) queries; the harness does not care which engine produced it.
 */
internal object SearchWorkload {
    const val DOC_COUNT: Int = 20_000
    const val TOKENS_PER_DOC: Int = 64
    const val QUERY_COUNT: Int = 100
    const val WARMUP_QUERIES: Int = 10
    private const val VOCAB = 4_096
    private const val SEED = 0x9E3779B97F4A7C15uL

    fun run(): Result {
        val index = buildIndex()
        val rng = Lcg(SEED xor 0xD1B54A32D192ED03uL)
        val queries = List(QUERY_COUNT + WARMUP_QUERIES) { randomQuery(rng) }
        val warmup = queries.subList(0, WARMUP_QUERIES)
        val measured = queries.subList(WARMUP_QUERIES, queries.size)

        var checksum = 0L
        for (query in warmup) {
            checksum += score(index, query)
        }

        val latenciesNs = LongArray(measured.size)
        for (i in measured.indices) {
            val start = System.nanoTime()
            checksum += score(index, measured[i])
            latenciesNs[i] = System.nanoTime() - start
        }

        latenciesNs.sort()
        return Result(
            documents = DOC_COUNT,
            queries = measured.size,
            warmupQueries = WARMUP_QUERIES,
            checksum = checksum,
            latencyNs = latenciesNs,
        )
    }

    private fun buildIndex(): Index {
        val rng = Lcg(SEED)
        val df = IntArray(VOCAB)
        val postings = Array(VOCAB) { ArrayList<Int>() }
        val tf = Array(VOCAB) { ArrayList<Int>() }
        val docLen = IntArray(DOC_COUNT)
        for (doc in 0 until DOC_COUNT) {
            val seen = IntArray(VOCAB)
            var length = 0
            repeat(TOKENS_PER_DOC) {
                val term = rng.nextInt(VOCAB)
                seen[term] += 1
                length += 1
            }
            docLen[doc] = length
            for (term in seen.indices) {
                val freq = seen[term]
                if (freq == 0) continue
                df[term] += 1
                postings[term].add(doc)
                tf[term].add(freq)
            }
        }
        return Index(
            df = df,
            postings = postings.map { it.toIntArray() }.toTypedArray(),
            tf = tf.map { it.toIntArray() }.toTypedArray(),
            docLen = docLen,
            avgDocLen = docLen.average(),
        )
    }

    private fun randomQuery(rng: Lcg): IntArray = IntArray(3) { rng.nextInt(VOCAB) }

    private fun score(
        index: Index,
        query: IntArray,
    ): Long {
        val scores = HashMap<Int, Double>(64)
        val k1 = 1.2
        val b = 0.75
        val n = DOC_COUNT.toDouble()
        for (term in query) {
            val df = index.df[term]
            if (df == 0) continue
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            val docs = index.postings[term]
            val freqs = index.tf[term]
            for (i in docs.indices) {
                val doc = docs[i]
                val freq = freqs[i].toDouble()
                val norm =
                    freq * (k1 + 1.0) /
                        (freq + k1 * (1.0 - b + b * index.docLen[doc] / index.avgDocLen))
                scores[doc] = (scores[doc] ?: 0.0) + idf * norm
            }
        }
        var bestDoc = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for ((doc, value) in scores) {
            if (value > bestScore) {
                bestScore = value
                bestDoc = doc
            }
        }
        return bestDoc.toLong() xor bestScore.toBits()
    }

    internal data class Result(
        val documents: Int,
        val queries: Int,
        val warmupQueries: Int,
        val checksum: Long,
        val latencyNs: LongArray,
    ) {
        fun toJson(): String {
            fun nsToMs(ns: Long) = ns / 1_000_000.0
            val p50 = percentile(latencyNs, 0.50)
            val p95 = percentile(latencyNs, 0.95)
            val p99 = percentile(latencyNs, 0.99)
            val mean = latencyNs.average()
            return buildString {
                append("{")
                append("\"schema\":1,")
                append("\"name\":\"inmemory-bm25\",")
                append("\"documents\":$documents,")
                append("\"queries\":$queries,")
                append("\"warmupQueries\":$warmupQueries,")
                append("\"checksum\":$checksum,")
                append("\"latencyMs\":{")
                append("\"min\":${nsToMs(latencyNs.first())},")
                append("\"p50\":${nsToMs(p50)},")
                append("\"p95\":${nsToMs(p95)},")
                append("\"p99\":${nsToMs(p99)},")
                append("\"max\":${nsToMs(latencyNs.last())},")
                append("\"mean\":${mean / 1_000_000.0}")
                append("}}")
            }
        }
    }

    private data class Index(
        val df: IntArray,
        val postings: Array<IntArray>,
        val tf: Array<IntArray>,
        val docLen: IntArray,
        val avgDocLen: Double,
    )
}

private class Lcg(
    private var state: ULong,
) {
    fun nextDouble(): Double {
        state = state * 6364136223846793005uL + 1442695040888963407uL
        return (state shr 11).toDouble() * (1.0 / 9007199254740992.0)
    }

    fun nextInt(bound: Int): Int = (nextDouble() * bound).toInt().coerceIn(0, bound - 1)
}

private fun percentile(
    sorted: LongArray,
    p: Double,
): Long {
    if (sorted.isEmpty()) return 0
    val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
    return sorted[idx]
}
