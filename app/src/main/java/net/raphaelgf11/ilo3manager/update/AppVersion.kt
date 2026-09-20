package net.raphaelgf11.ilo3manager.update

/**
 * Compares version strings as published on the releases page.
 *
 * Tags carry a "v" prefix while the application reports a bare version, and pre-releases must sort
 * *below* the final release of the same number — `1.1.0-beta1` precedes `1.1.0`. Comparing the
 * strings directly would get both wrong, and would offer a downgrade as an update.
 */
object AppVersion {

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        val (numbersA, preA) = parse(a)
        val (numbersB, preB) = parse(b)

        for (i in 0 until maxOf(numbersA.size, numbersB.size)) {
            val diff = numbersA.getOrElse(i) { 0 } - numbersB.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }

        // Equal numbers: whichever has no pre-release suffix is the finished one, so it wins.
        return when {
            preA == preB -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    private fun parse(version: String): Pair<List<Int>, String?> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val prerelease = cleaned.substringAfter('-', "").takeIf { it.isNotEmpty() }
        val numbers = cleaned.substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return numbers to prerelease
    }
}
