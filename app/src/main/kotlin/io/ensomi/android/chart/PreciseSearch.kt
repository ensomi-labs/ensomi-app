package io.ensomi.android.chart

import kotlin.math.abs

/**
 * Precise difficulty: make several charts and keep the one whose measured star is closest to the star asked for.
 *
 * The generator answers a request for a star with a chart near it, not on it. The search asks again, moving the
 * request by how far the last chart missed (a secant step: the slope between the last two charts of one seed, held
 * between [MIN_SLOPE] and [MAX_SLOPE]), and, once moving the request no longer moves the star, tries further seeds at
 * the best request so far. It stops at the first chart within [TOLERANCE] of the target, or after [MAX_CHARTS].
 *
 * Requests stay inside the range the interface offers, `[lo, hi]`, both ends allowed: the search never asks the
 * generator for a star the person could not ask for themselves. Beyond the model's range every request makes the same
 * chart for a song and seed, so a chart spent there would teach nothing, and a runtime may refuse it. The target is
 * never moved: one outside the range starts at the nearer end and is still what every chart is scored against.
 *
 * The website implements the same rule, so the two must be changed together. This file is the rule alone: plain
 * Kotlin with no Android and no runtime in it; [run] is handed the function that makes a chart and measures it.
 *
 * ```
 * precise(target T, seed s, offered range [lo, hi], N = 6, tol = 0.05):
 *   tries = []; r = clamp(T, lo, hi); seed = s; saturated = false; seedTries = 0
 *   for k in 0 until N:
 *     m = star(generate(star = r, seed = seed))
 *     tries += (k, r, seed, m)
 *     best = the try with the smallest |m − T|; the earliest wins a tie
 *     if |best.m − T| <= tol or k == N − 1: stop
 *     if not saturated:
 *       prev = the latest earlier try with the same seed and a different r, if any
 *       if prev exists and |m − prev.m| < 0.01:
 *         saturated = true
 *       else:
 *         slope = prev exists ? clamp((m − prev.m) / (r − prev.r), 0.5, 2.0) : 1.0
 *         next = clamp(r + (T − m) / slope, lo, hi)
 *         if |next − r| < 0.01: saturated = true else: r = next
 *     if saturated:
 *       r = best.r; seedTries += 1; seed = s + seedTries
 *   return best, tries
 * ```
 */
object PreciseSearch {
    /** N: the most charts one search makes. */
    const val MAX_CHARTS = 6

    /** tol: a chart this close to the target ends the search. */
    const val TOLERANCE = 0.05

    /** Two stars, or two requests, closer than this count as the same: the request no longer steers. */
    const val SAME = 0.01

    /** The least and the most the star is taken to move per star of request, whatever two charts suggest. */
    const val MIN_SLOPE = 0.5
    const val MAX_SLOPE = 2.0

    /** One chart of a search: the [request] star and [seed] it was made with, and the star it [measured]. */
    data class Try(val index: Int, val request: Double, val seed: ULong, val measured: Double)

    /** The chart kept, and every chart made, in order. */
    data class Outcome(val best: Try, val tries: List<Try>)

    /**
     * Runs the search for [target] from [seed], with every request kept within [lowest]..[highest], the ends of the
     * range the interface offers.
     *
     * [generate] makes the chart of a try (its number from 0, its request star, its seed) and returns the star it
     * measures; null when no chart was made (the person stopped it), which ends the search with null. [keep] is told
     * each time the chart just made is the closest so far, so the caller holds that one and lets the others go.
     *
     * Seeds are 64-bit and unsigned as the runtime's are: past 2^64 − 1 the next seed is 0.
     *
     * A measured star that is not a number cannot steer anything: the search ends there, keeping the closest chart
     * before it, or that chart when it is the first.
     */
    fun run(
        target: Double,
        seed: ULong,
        lowest: Double,
        highest: Double,
        maxCharts: Int = MAX_CHARTS,
        tolerance: Double = TOLERANCE,
        keep: (Try) -> Unit = {},
        generate: (index: Int, request: Double, seed: ULong) -> Double?,
    ): Outcome? {
        require(maxCharts >= 1) { "A search makes at least one chart" }
        require(lowest <= highest) { "The offered range is empty: $lowest to $highest" }
        val tries = ArrayList<Try>(maxCharts)
        var best: Try? = null
        var request = target.coerceIn(lowest, highest)
        var currentSeed = seed
        var saturated = false
        var seedTries = 0
        for (index in 0 until maxCharts) {
            val measured = generate(index, request, currentSeed) ?: return null
            val current = Try(index, request, currentSeed, measured)
            // prev: looked for before this try joins the list, so it is always an earlier one.
            val previous = tries.lastOrNull { it.seed == currentSeed && it.request != request }
            tries.add(current)
            val bestSoFar = best
            if (!measured.isFinite()) {
                if (bestSoFar == null) {
                    best = current
                    keep(current)
                }
                break
            }
            // Strictly closer: the earliest wins a tie.
            val closest: Try
            if (bestSoFar == null || abs(measured - target) < abs(bestSoFar.measured - target)) {
                closest = current
                best = current
                keep(current)
            } else {
                closest = bestSoFar
            }
            if (abs(closest.measured - target) <= tolerance || index == maxCharts - 1) break
            if (!saturated) {
                if (previous != null && abs(measured - previous.measured) < SAME) {
                    // The request moved and the star did not.
                    saturated = true
                } else {
                    val slope = if (previous != null) {
                        ((measured - previous.measured) / (request - previous.request)).coerceIn(MIN_SLOPE, MAX_SLOPE)
                    } else {
                        1.0
                    }
                    val next = (request + (target - measured) / slope).coerceIn(lowest, highest)
                    if (abs(next - request) < SAME) saturated = true else request = next
                }
            }
            if (saturated) {
                request = closest.request
                seedTries += 1
                // ULong arithmetic wraps: the seed after 2^64 − 1 is 0.
                currentSeed = seed + seedTries.toULong()
            }
        }
        val kept = best ?: return null
        return Outcome(kept, tries)
    }
}
