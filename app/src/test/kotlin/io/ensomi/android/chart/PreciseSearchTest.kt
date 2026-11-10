package io.ensomi.android.chart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search against generators that answer by a rule, so each of its moves can be read off. The range offered is the
 * slider's for R2, 2.0 to 5.9.
 */
class PreciseSearchTest {
    /** A generator that counts its charts and remembers what it was asked. */
    private class Fake(val star: (request: Double, seed: ULong) -> Double) {
        val asked = mutableListOf<Pair<Double, ULong>>()
        val kept = mutableListOf<PreciseSearch.Try>()

        fun search(target: Double, seed: ULong): PreciseSearch.Outcome = PreciseSearch.run(
            target = target,
            seed = seed,
            lowest = LOWEST,
            highest = HIGHEST,
            keep = { kept.add(it) },
        ) { _, request, trySeed ->
            asked.add(request to trySeed)
            star(request, trySeed)
        }!!
    }

    /** A little star that depends on the seed alone: between −0.06 and +0.06. */
    private fun bySeed(seed: ULong): Double = ((seed % 7uL).toInt() - 3) * 0.02

    /** A star off by up to 0.3 either way, fixed by the seed and the request. */
    private fun noisy(request: Double, seed: ULong): Double =
        request + (java.util.Random(seed.toLong() * 31 + Math.round(request * 1000)).nextDouble() - 0.5) * 0.6

    private fun error(outcome: PreciseSearch.Outcome, target: Double, index: Int): Double =
        abs(outcome.tries[index].measured - target)

    @Test
    fun theConstantsAreTheAgreedOnes() {
        assertEquals(6, PreciseSearch.MAX_CHARTS)
        assertEquals(0.05, PreciseSearch.TOLERANCE, 0.0)
    }

    @Test
    fun aChartCloseEnoughAtOnceIsTheOnlyOne() {
        val fake = Fake { request, _ -> request + 0.05 }
        val outcome = fake.search(4.0, 1uL)
        assertEquals(1, outcome.tries.size)
        assertEquals(listOf(4.0 to 1uL), fake.asked)
        assertEquals(PreciseSearch.Try(0, 4.0, 1uL, 4.05), outcome.best)
    }

    @Test
    fun aLinearResponseIsMetOnTheSecondChart() {
        // Every chart comes out 0.3 above its request: the second request is 0.3 lower.
        val fake = Fake { request, _ -> request + 0.3 }
        val outcome = fake.search(4.0, 7uL)
        assertEquals(2, outcome.tries.size)
        assertEquals(3.7, outcome.tries[1].request, 1e-9)
        assertEquals(7uL, outcome.tries[1].seed)
        assertSame(outcome.tries[1], outcome.best)
        assertTrue(abs(outcome.best.measured - 4.0) <= PreciseSearch.TOLERANCE)
    }

    @Test
    fun aShallowResponseIsMetOnceItsSlopeIsKnown() {
        // The star moves 0.6 per star of request. The first step assumes 1 and falls short; the second uses 0.6.
        val fake = Fake { request, _ -> 0.6 * request + 1.0 }
        val outcome = fake.search(4.0, 7uL)
        assertEquals(3, outcome.tries.size)
        assertEquals(4.6, outcome.tries[1].request, 1e-9)
        assertEquals(5.0, outcome.tries[2].request, 1e-9)
        assertEquals(2, outcome.best.index)
        assertTrue(abs(outcome.best.measured - 4.0) <= PreciseSearch.TOLERANCE)
        // One seed throughout: the request steered.
        assertEquals(setOf(7uL), outcome.tries.map { it.seed }.toSet())
    }

    @Test
    fun aTargetAtTheTopOfTheRangeGoesToOtherSeedsAfterOneFlatStep() {
        // The hardest chart measures about 5.3, whatever is asked. Asked for 5.9, the first chart falls short, and the
        // next request would be above the range: it stays at 5.9, which changes nothing, so further seeds are tried.
        val fake = Fake { request, seed -> min(request, 5.3) + bySeed(seed) }
        val outcome = fake.search(5.9, 10uL)
        assertEquals(PreciseSearch.MAX_CHARTS, outcome.tries.size)
        assertEquals(listOf(10uL, 11uL, 12uL, 13uL, 14uL, 15uL), outcome.tries.map { it.seed })
        outcome.tries.forEach { assertEquals(5.9, it.request, 0.0) }
        // Seed 13 gives the most, 5.36: the closest to 5.9.
        assertEquals(3, outcome.best.index)
        assertEquals(5.36, outcome.best.measured, 1e-9)
        assertEquals(listOf(0, 1, 2, 3), fake.kept.map { it.index })
    }

    @Test
    fun aTargetAtTheBottomOfTheRangeWorksTheSameWay() {
        val fake = Fake { request, seed -> max(request, 2.5) + bySeed(seed) }
        val outcome = fake.search(2.0, 10uL)
        assertEquals(PreciseSearch.MAX_CHARTS, outcome.tries.size)
        assertEquals(listOf(10uL, 11uL, 12uL, 13uL, 14uL, 15uL), outcome.tries.map { it.seed })
        outcome.tries.forEach { assertEquals(2.0, it.request, 0.0) }
        // Seed 14 gives the least, 2.44.
        assertEquals(4, outcome.best.index)
    }

    @Test
    fun theEarliestOfEquallyCloseChartsIsKept() {
        val fake = Fake { request, _ -> min(request, 5.3) }
        val outcome = fake.search(5.9, 3uL)
        assertEquals(PreciseSearch.MAX_CHARTS, outcome.tries.size)
        outcome.tries.forEach { assertEquals(5.3, it.measured, 0.0) }
        assertEquals(0, outcome.best.index)
        assertEquals(listOf(0), fake.kept.map { it.index })
    }

    @Test
    fun aTargetInsideSteersUntilItMeetsTheTopOfTheRange() {
        // Every chart comes out 1.5 under its request. The second request would be 6.5: it is 5.9, the top of the
        // range. The third would be above it again, so the request stays and further seeds are tried there.
        val fake = Fake { request, _ -> request - 1.5 }
        val outcome = fake.search(5.0, 1uL)
        assertEquals(PreciseSearch.MAX_CHARTS, outcome.tries.size)
        assertEquals(listOf(5.0, 5.9, 5.9, 5.9, 5.9, 5.9), outcome.tries.map { it.request })
        assertEquals(listOf(1uL, 1uL, 2uL, 3uL, 4uL, 5uL), outcome.tries.map { it.seed })
        assertEquals(1, outcome.best.index)
        assertEquals(4.4, outcome.best.measured, 1e-9)
    }

    @Test
    fun aTargetInsideSteersUntilItMeetsTheBottomOfTheRange() {
        val fake = Fake { request, _ -> request + 1.5 }
        val outcome = fake.search(3.0, 1uL)
        assertEquals(PreciseSearch.MAX_CHARTS, outcome.tries.size)
        assertEquals(listOf(3.0, 2.0, 2.0, 2.0, 2.0, 2.0), outcome.tries.map { it.request })
        assertEquals(listOf(1uL, 1uL, 2uL, 3uL, 4uL, 5uL), outcome.tries.map { it.seed })
        assertEquals(1, outcome.best.index)
    }

    @Test
    fun aTargetOutsideTheRangeStartsAtTheNearerEndAndIsStillTheTarget() {
        // No control of the app sends such a target; a debug launch can. The request is brought inside, the target
        // is not: the chart kept is the one closest to 8, the hardest, not the one closest to 5.9.
        val above = Fake { request, seed -> min(request, 5.3) + bySeed(seed) }
        val hardest = above.search(8.0, 10uL)
        assertEquals(5.9 to 10uL, above.asked.first())
        hardest.tries.forEach { assertEquals(5.9, it.request, 0.0) }
        assertEquals(3, hardest.best.index)
        val below = Fake { request, seed -> max(request, 2.5) + bySeed(seed) }
        val easiest = below.search(1.0, 10uL)
        assertEquals(2.0 to 10uL, below.asked.first())
        easiest.tries.forEach { assertEquals(2.0, it.request, 0.0) }
        assertEquals(4, easiest.best.index)
    }

    @Test
    fun aNoisyResponseKeepsItsRules() {
        for (target in listOf(2.0, 2.4, 3.5, 5.0, 5.9)) {
            for (seed in listOf(0uL, 1uL, 2uL, 99uL, 123_456_789uL)) {
                val fake = Fake { request, trySeed -> noisy(request, trySeed) }
                val outcome = fake.search(target, seed)
                val label = "target $target seed $seed"
                // At most N generations, and each try is one generation.
                assertTrue(label, outcome.tries.size in 1..PreciseSearch.MAX_CHARTS)
                assertEquals(label, outcome.tries.size, fake.asked.size)
                assertEquals(label, outcome.tries.indices.toList(), outcome.tries.map { it.index })
                // The first try is the request as it was made.
                assertEquals(label, target to seed, fake.asked.first())
                // Never worse than the first, and no try is closer than the one kept.
                val kept = abs(outcome.best.measured - target)
                assertTrue(label, kept <= error(outcome, target, 0))
                outcome.tries.forEach { assertTrue(label, kept <= abs(it.measured - target)) }
                // The caller's last keep is the chart returned.
                assertEquals(label, outcome.best, fake.kept.last())
                // It stopped because it was close enough, or because it had made N charts, and not before.
                val closeEnough = kept <= PreciseSearch.TOLERANCE
                assertTrue(label, closeEnough || outcome.tries.size == PreciseSearch.MAX_CHARTS)
                outcome.tries.dropLast(1).forEach {
                    assertTrue(label, abs(it.measured - target) > PreciseSearch.TOLERANCE)
                }
                // No request leaves the range offered.
                outcome.tries.forEach { assertTrue(label, it.request in LOWEST..HIGHEST) }
            }
        }
    }

    @Test
    fun theSameTargetAndSeedSearchTheSameWay() {
        val first = Fake { request, trySeed -> noisy(request, trySeed) }.search(3.5, 99uL)
        val second = Fake { request, trySeed -> noisy(request, trySeed) }.search(3.5, 99uL)
        assertEquals(first, second)
        val flat = Fake { request, seed -> min(request, 5.3) + bySeed(seed) }
        assertEquals(flat.search(5.9, 10uL), flat.search(5.9, 10uL))
    }

    @Test
    fun theSeedAfterTheLargestIsZero() {
        val largest = ULong.MAX_VALUE
        val fake = Fake { request, seed -> min(request, 5.3) + bySeed(seed) }
        val outcome = fake.search(5.9, largest)
        assertEquals(listOf(largest, 0uL, 1uL, 2uL, 3uL, 4uL), outcome.tries.map { it.seed })
        assertEquals(4uL, outcome.best.seed)
    }

    @Test
    fun aSearchStoppedPartWayKeepsNothing() {
        var charts = 0
        val outcome = PreciseSearch.run(target = 5.9, seed = 1uL, lowest = LOWEST, highest = HIGHEST) { _, request, _ ->
            charts += 1
            if (charts == 3) null else min(request, 5.3)
        }
        assertNull(outcome)
        assertEquals(3, charts)
    }

    @Test
    fun fewerChartsCanBeAskedFor() {
        val one = PreciseSearch.run(
            target = 5.9, seed = 1uL, lowest = LOWEST, highest = HIGHEST, maxCharts = 1,
        ) { _, request, _ -> min(request, 5.3) }
        assertNotNull(one)
        assertEquals(1, one!!.tries.size)
        val wide = PreciseSearch.run(
            target = 4.0, seed = 1uL, lowest = LOWEST, highest = HIGHEST, tolerance = 0.5,
        ) { _, request, _ -> request + 0.3 }
        assertEquals(1, wide!!.tries.size)
    }

    @Test
    fun aRangeOfOneStarOnlyTriesSeeds() {
        val outcome = PreciseSearch.run(target = 4.0, seed = 1uL, lowest = 3.0, highest = 3.0) { _, request, seed ->
            request + bySeed(seed)
        }!!
        assertEquals(PreciseSearch.MAX_CHARTS, outcome.tries.size)
        outcome.tries.forEach { assertEquals(3.0, it.request, 0.0) }
        assertEquals(listOf(1uL, 2uL, 3uL, 4uL, 5uL, 6uL), outcome.tries.map { it.seed })
        assertEquals(5, outcome.best.index)
    }

    @Test
    fun aStarThatIsNotANumberEndsTheSearch() {
        val first = PreciseSearch.run(target = 4.0, seed = 1uL, lowest = LOWEST, highest = HIGHEST) { _, _, _ ->
            Double.NaN
        }!!
        assertEquals(1, first.tries.size)
        assertEquals(0, first.best.index)
        val later = PreciseSearch.run(target = 4.0, seed = 1uL, lowest = LOWEST, highest = HIGHEST) { index, request, _ ->
            if (index == 1) Double.NaN else request + 0.3
        }!!
        assertEquals(2, later.tries.size)
        assertEquals(0, later.best.index)
    }

    private companion object {
        const val LOWEST = 2.0
        const val HIGHEST = 5.9
    }
}
