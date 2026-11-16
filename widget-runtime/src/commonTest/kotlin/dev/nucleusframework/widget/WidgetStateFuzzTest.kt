package dev.nucleusframework.widget

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seeded fuzz of the state encoding every host persists: any key and value round-trip, and no
 * input, however broken, makes decoding throw.
 */
class WidgetStateFuzzTest {
    // Quotes, escapes, controls, line separators, a BOM and lone or paired surrogates.
    private val alphabet =
        (
            "aZ0 \"\\/\n\r\t\b\u000c\u0000\u001f\u007f\u00e9\u05e9\u2028\u2029\ufeff" +
                "\uD83D\uDE00\uDC00\uD800{}:,[]u"
        ).toList()

    private fun Random.text(): String = CharArray(nextInt(0, 24)) { alphabet[nextInt(alphabet.size)] }.concatToString()

    @Test
    fun anyStateRoundTrips() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val state = WidgetState.decode(null)
            val expected = mutableMapOf<String, String>()
            repeat(random.nextInt(0, 12)) {
                val key = stringStateKey(random.text())
                val value = random.text()
                state[key] = value
                expected[key.name] = value
            }
            val decoded = WidgetState.decode(state.encode())
            for ((name, value) in expected) {
                assertEquals(value, decoded[stringStateKey(name)], "seed $seed, key '$name'")
            }
            assertEquals(state, decoded, "seed $seed")
        }
    }

    @Test
    fun brokenInputNeverThrows() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val valid = WidgetState.decode(null).apply { this[stringStateKey(random.text())] = random.text() }.encode()
            val broken =
                when (random.nextInt(4)) {
                    0 -> valid.take(random.nextInt(0, valid.length + 1))
                    1 -> valid.replaceRange(random.nextInt(valid.length).let { it..it }, random.text())
                    2 -> random.text()
                    else -> valid + random.text()
                }
            WidgetState.decode(broken)
            WidgetEngine.parseInputs(broken)
        }
    }

    private companion object {
        const val SEEDS = 5_000
    }
}
