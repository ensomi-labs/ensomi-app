package io.ensomi.android.play

import io.ensomi.runtime.Score
import kotlinx.serialization.Serializable

/** A finished game as the results screen shows it. Counts come from the runtime's score. */
@Serializable
data class PlayResult(
    val title: String,
    val detail: String,
    val accuracy: Double,
    val perfect: Int,
    val good: Int,
    val miss: Int,
    val maxCombo: Int,
    /** Hits per 10 ms of error from −150 to +150 ms; early on the left. */
    val histogram: List<Int>,
    val meanOffsetMs: Double?,
    /** The slots the game used; null for a game that kept none. */
    val slots: ResultSlots? = null,
    /** Each method's average, when both touch and keys hit notes. */
    val methodMeans: MethodMeans? = null,
    /** The game was the playfield preview's sample chart, not a chart of the player's song. */
    val sample: Boolean,
    /** Chart facts stay separate from display text so restored results follow the current language. */
    val chart: ResultChart? = null,
) {
    companion object {
        const val BIN_MS = 10
        const val RANGE_MS = 150

        fun of(title: String, detail: String, score: Score, hud: PlayHud, sample: Boolean): PlayResult {
            val counts = score.tierCounts
            return PlayResult(
                title = title,
                detail = detail,
                accuracy = score.accuracy,
                // The runtime's rule: bigP shows Perfect, p1 to g show Good, m shows Miss.
                perfect = counts.bigP.toInt(),
                good = (counts.p1 + counts.p2 + counts.p3 + counts.g).toInt(),
                miss = counts.m.toInt(),
                maxCombo = score.maxCombo.toInt(),
                histogram = histogram(hud.hitOffsetsMs),
                meanOffsetMs = hud.hitOffsetsMs.takeIf { it.isNotEmpty() }?.average(),
                slots = hud.timing?.let { ResultSlots.of(it, keysPressed = hud.keyboard.pressed) },
                methodMeans = MethodMeans.of(hud.touch, hud.keyboard),
                sample = sample,
            )
        }

        fun histogram(offsetsMs: List<Double>): List<Int> {
            val bins = IntArray(2 * RANGE_MS / BIN_MS)
            for (offset in offsetsMs) {
                val bin = ((offset + RANGE_MS) / BIN_MS).toInt().coerceIn(0, bins.size - 1)
                bins[bin]++
            }
            return bins.toList()
        }
    }
}

@Serializable
data class ResultChart(val artist: String?, val stars: Double, val seed: String)

/** One method's part in a game: whether it pressed, and its hits timed with the sum of their errors. */
data class MethodHits(val pressed: Boolean = false, val hits: Int = 0, val errorSumMs: Double = 0.0)

/**
 * The slots a game used, for Results' line "Audio latency 1 · WH-1000XM4. Touch on input 0. Keyboard on input 1."
 * (latency.md, Results): [audio] null for a game without sound (the sample, Listen & Play), [keyboard] null unless keys
 * pressed.
 */
@Serializable
data class ResultSlots(val audio: Int?, val touch: Int?, val keyboard: Int?) {
    companion object {
        fun of(timing: PlayTiming, keysPressed: Boolean): ResultSlots =
            ResultSlots(timing.audioSlot, timing.touchSlot, timing.keyboardSlot?.takeIf { keysPressed })
    }
}

/**
 * Touch's and the keys' average errors, by the method of the input that decided each hit (latency.md, O6). Only when both
 * methods hit notes; one method's average is the game's own.
 */
@Serializable
data class MethodMeans(val touchMs: Double, val keysMs: Double) {
    companion object {
        fun of(touch: MethodHits, keys: MethodHits): MethodMeans? =
            if (touch.hits > 0 && keys.hits > 0) MethodMeans(touch.errorSumMs / touch.hits, keys.errorSumMs / keys.hits) else null
    }
}
