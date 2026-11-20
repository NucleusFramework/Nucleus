package dev.nucleusframework.lab.core.mvi

/** How many entries a probe keeps in any history list (calls, deliveries, changes). */
const val DEFAULT_HISTORY: Int = 50

/** Appends [item], keeping the newest [cap] entries (oldest first). */
fun <T> List<T>.append(
    item: T,
    cap: Int = DEFAULT_HISTORY,
): List<T> = (this + item).takeLast(cap)

/** Prepends [item], keeping the newest [cap] entries (newest first). */
fun <T> List<T>.pushFront(
    item: T,
    cap: Int = DEFAULT_HISTORY,
): List<T> = (listOf(item) + this).take(cap)

/** Replaces every element whose [key] is [match] by its [transform]. */
inline fun <T, K> List<T>.replaceWhere(
    key: (T) -> K,
    match: K,
    transform: (T) -> T,
): List<T> = map { if (key(it) == match) transform(it) else it }
