package io.ensomi.android.chart

/** Whether a chart came out far from the star asked for, and which way. */
enum class StarGap { NONE, EASIER, HARDER }

/**
 * How a chart's star is shown. The star a chart came out at (the runtime's `starRating` of it) is the chart's star.
 * The star asked for is said beside it when the two differ once each is rounded to a tenth, as the screens show them:
 * "4.6 stars · asked 4.5". More than [FAR_TENTHS] tenths apart, the chart-ready state also says so in words ([gap]).
 * A chart kept without a measurement (one made by 0.2.1) shows the star asked for alone, as it always did.
 */
data class StarDisplay(
    /** The chart's star: the measured one when there is one, else the one asked for. */
    val stars: Double,
    /** The star asked for, when it is said beside [stars]; null when it would say the same, or nothing was measured. */
    val asked: Double?,
    val gap: StarGap,
) {
    companion object {
        /** A chart and its request more than this many tenths apart are far apart: more than 0.5 stars. */
        const val FAR_TENTHS = 5L

        fun of(asked: Double, measured: Double?): StarDisplay {
            if (measured == null || !measured.isFinite()) return StarDisplay(asked, null, StarGap.NONE)
            val difference = tenths(measured) - tenths(asked)
            val gap = when {
                difference > FAR_TENTHS -> StarGap.HARDER
                difference < -FAR_TENTHS -> StarGap.EASIER
                else -> StarGap.NONE
            }
            return StarDisplay(measured, if (difference == 0L) null else asked, gap)
        }

        private fun tenths(stars: Double): Long = Math.round(stars * 10)
    }
}
