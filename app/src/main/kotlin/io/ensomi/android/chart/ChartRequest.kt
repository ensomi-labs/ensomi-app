package io.ensomi.android.chart

import kotlinx.serialization.Serializable
import java.util.Locale

/** The section kinds the request can carry; which are offered comes from the model's capabilities. */
@Serializable
enum class SectionKind {
    JUMPSTREAM,
    CHORDSTREAM,
    STREAM,
    JACK,
    CHORDJACK,

    /** A star target for the span instead of a pattern. */
    STAR,
}

/** A kind the model offers, with the note its capabilities give for it. */
@Serializable
data class KindOption(val kind: SectionKind, val experimental: Boolean, val note: String)

/**
 * The stars the app offers, on every control that asks for them (the whole-song slider and its − and +, a section's
 * star target, Listen & Play's stepper): 1.0 to 9.0 in tenths. The runtime takes any finite star, so the range is the
 * app's own choice, not the model's; what the model is best at is [ChartCapabilities.recommendedStars].
 */
object StarRange {
    const val MIN = 1.0
    const val MAX = 9.0
    const val DEFAULT = 3.5

    /** [MIN] and [MAX] in tenths, the unit the controls move by. */
    const val MIN_TENTHS = 10
    const val MAX_TENTHS = 90

    val OFFERED: ClosedFloatingPointRange<Double> = MIN..MAX

    /** Steps between the ends of [OFFERED], for a Material slider: 81 values, so 79 between the first and the last. */
    const val SLIDER_STEPS = MAX_TENTHS - MIN_TENTHS - 1

    /** [stars] on the nearest offered tenth. */
    fun snap(stars: Double): Double = step(stars, 0)

    /** [stars] moved by [tenths] tenths and kept on an offered tenth: what − and + do. */
    fun step(stars: Double, tenths: Int): Double =
        (Math.round(stars * 10) + tenths).coerceIn(MIN_TENTHS.toLong(), MAX_TENTHS.toLong()) / 10.0

    /** A bound as a notice says it: "2" for a whole number of stars, "2.5" otherwise. */
    fun bound(stars: Double): String {
        val tenths = Math.round(stars * 10)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.ROOT, "%.1f", tenths / 10.0)
    }
}

/**
 * What the model says of itself: the runtime's `ModelCapabilities`, with the kinds whose status is not "not working".
 * [minStar] and [maxStarExclusive] are the range the model was fitted on. The runtime accepts stars outside it and then
 * uses its nearest fitted settings, so the range is a recommendation, shown as a notice; the stars offered are
 * [StarRange].
 */
@Serializable
data class ChartCapabilities(
    val modelName: String,
    val minStar: Double,
    val maxStarExclusive: Double,
    val kinds: List<KindOption>,
) {
    /** The stars the model is at its best at: the range it was fitted on, `[minStar, maxStarExclusive)`. */
    val recommendedStars: ClosedFloatingPointRange<Double>
        get() = minStar..maxOf(minStar, maxStarExclusive)
}

/** One section as the player edits it: times as typed text, so a half-typed value stays on screen. */
data class SectionDraft(
    val id: Long,
    val start: String = "00:00",
    val end: String = "00:08",
    val kind: SectionKind = SectionKind.JUMPSTREAM,
    val strength: Float = 0.5f,
    val star: Float = 3.5f,
)

/** Why a section, or the set of them, cannot be sent. The screen words each one. */
enum class SectionProblem {
    TIME_FORMAT,
    END_NOT_AFTER_START,
    OUTSIDE_SONG,
    LENGTH,
    STAR_RANGE,
    OVERLAP,
}

/** A problem and the section it belongs to, by position; [index] is null for one about the set. */
data class SectionIssue(val index: Int?, val problem: SectionProblem)

/**
 * The request checks the app makes before Generate is enabled: the runtime's rules for sections, and the app's own
 * range for a section's star.
 */
object ChartRequestRules {
    /** A section lasts 8 to 32 seconds, whatever its target. */
    val SECTION_LENGTH_MS = 8_000..32_000

    /** "mm:ss" with seconds from 00 to 59, in milliseconds; null when it is not that. */
    fun parseTime(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val (minutes, seconds) = parts
        if (minutes.isEmpty() || seconds.length != 2) return null
        if (!minutes.all { it in '0'..'9' } || !seconds.all { it in '0'..'9' }) return null
        val m = minutes.toIntOrNull() ?: return null
        val s = seconds.toInt()
        if (s >= 60 || m > 9_999) return null
        return (m * 60 + s) * 1_000
    }

    /** "m:ss" for a length. */
    fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1_000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    /** A seed is a whole number from 0 to 2^64 − 1; blank means a new chart each time. */
    fun parseSeed(text: String): ULong? = text.trim().toULongOrNull()

    fun isSeedValid(text: String): Boolean = text.isBlank() || parseSeed(text) != null

    fun check(
        sections: List<SectionDraft>,
        songDurationMs: Long,
        stars: ClosedFloatingPointRange<Double> = StarRange.OFFERED,
    ): List<SectionIssue> {
        val issues = mutableListOf<SectionIssue>()
        val spans = mutableListOf<IntRange>()
        sections.forEachIndexed { index, section ->
            val start = parseTime(section.start)
            val end = parseTime(section.end)
            when {
                start == null || end == null -> issues += SectionIssue(index, SectionProblem.TIME_FORMAT)
                end <= start -> issues += SectionIssue(index, SectionProblem.END_NOT_AFTER_START)
                end > songDurationMs -> issues += SectionIssue(index, SectionProblem.OUTSIDE_SONG)
                end - start !in SECTION_LENGTH_MS -> issues += SectionIssue(index, SectionProblem.LENGTH)
                else -> spans += start until end
            }
            if (section.kind == SectionKind.STAR && section.star.toDouble() !in stars) {
                issues += SectionIssue(index, SectionProblem.STAR_RANGE)
            }
        }
        // Sections may touch (one starts where another ends) but not overlap.
        val sorted = spans.sortedBy { it.first }
        if (sorted.zipWithNext().any { (previous, next) -> previous.last >= next.first }) {
            issues += SectionIssue(null, SectionProblem.OVERLAP)
        }
        return issues
    }
}
