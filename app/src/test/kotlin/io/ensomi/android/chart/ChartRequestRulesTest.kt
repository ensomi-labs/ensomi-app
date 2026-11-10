package io.ensomi.android.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRequestRulesTest {
    private val stars = StarRange.OFFERED
    private val song = 133_000L

    @Test
    fun readsMinutesAndSeconds() {
        assertEquals(32_000, ChartRequestRules.parseTime("00:32"))
        assertEquals(125_000, ChartRequestRules.parseTime("2:05"))
        assertNull(ChartRequestRules.parseTime("1:60"))
        assertNull(ChartRequestRules.parseTime("1:5"))
        assertNull(ChartRequestRules.parseTime(":05"))
        assertNull(ChartRequestRules.parseTime("1:05:00"))
        assertNull(ChartRequestRules.parseTime("a:05"))
    }

    @Test
    fun formatsLengths() {
        assertEquals("2:13", ChartRequestRules.formatDuration(133_900))
        assertEquals("0:08", ChartRequestRules.formatDuration(8_000))
    }

    @Test
    fun seedsAreBlankOrAnUnsignedSixtyFourBitNumber() {
        assertTrue(ChartRequestRules.isSeedValid(""))
        assertTrue(ChartRequestRules.isSeedValid("18446744073709551615"))
        assertFalse(ChartRequestRules.isSeedValid("18446744073709551616"))
        assertFalse(ChartRequestRules.isSeedValid("-1"))
        assertEquals(2914uL, ChartRequestRules.parseSeed(" 2914 "))
    }

    @Test
    fun aSectionLastsEightToThirtyTwoSecondsInsideTheSong() {
        fun problems(start: String, end: String) =
            ChartRequestRules.check(listOf(SectionDraft(1, start, end)), song, stars).map { it.problem }
        assertEquals(emptyList<SectionProblem>(), problems("00:32", "00:48"))
        assertEquals(emptyList<SectionProblem>(), problems("00:00", "00:08"))
        assertEquals(emptyList<SectionProblem>(), problems("00:00", "00:32"))
        assertEquals(listOf(SectionProblem.LENGTH), problems("00:32", "00:36"))
        assertEquals(listOf(SectionProblem.LENGTH), problems("00:00", "00:33"))
        assertEquals(listOf(SectionProblem.END_NOT_AFTER_START), problems("00:40", "00:40"))
        assertEquals(listOf(SectionProblem.OUTSIDE_SONG), problems("02:05", "02:14"))
        assertEquals(listOf(SectionProblem.TIME_FORMAT), problems("0:3", "00:40"))
    }

    @Test
    fun sectionsMayTouchButNotOverlap() {
        val touching = listOf(SectionDraft(1, "00:10", "00:20"), SectionDraft(2, "00:20", "00:30"))
        assertEquals(emptyList<SectionIssue>(), ChartRequestRules.check(touching, song, stars))
        val overlapping = listOf(SectionDraft(1, "00:10", "00:20"), SectionDraft(2, "00:19", "00:30"))
        assertEquals(listOf(SectionIssue(null, SectionProblem.OVERLAP)), ChartRequestRules.check(overlapping, song, stars))
    }

    @Test
    fun aStarSectionUsesTheStarsTheAppOffers() {
        fun problems(star: Float) = ChartRequestRules
            .check(listOf(SectionDraft(1, "00:10", "00:20", kind = SectionKind.STAR, star = star)), song)
            .map { it.problem }
        // Outside the range the model was fitted on, and offered all the same.
        assertEquals(emptyList<SectionProblem>(), problems(1.0f))
        assertEquals(emptyList<SectionProblem>(), problems(6.5f))
        assertEquals(emptyList<SectionProblem>(), problems(9.0f))
        assertEquals(listOf(SectionProblem.STAR_RANGE), problems(0.9f))
        assertEquals(listOf(SectionProblem.STAR_RANGE), problems(9.1f))
        // A pattern section's star is not looked at.
        val pattern = SectionDraft(1, "00:10", "00:20", kind = SectionKind.STREAM, star = 12f)
        assertEquals(emptyList<SectionIssue>(), ChartRequestRules.check(listOf(pattern), song))
    }

    @Test
    fun theAppOffersOneToNineStarsInTenths() {
        assertEquals(1.0, StarRange.OFFERED.start, 0.0)
        assertEquals(9.0, StarRange.OFFERED.endInclusive, 0.0)
        assertEquals(StarRange.MIN, StarRange.MIN_TENTHS / 10.0, 0.0)
        assertEquals(StarRange.MAX, StarRange.MAX_TENTHS / 10.0, 0.0)
        assertEquals(3.5, StarRange.DEFAULT, 0.0)
        assertTrue(StarRange.DEFAULT in StarRange.OFFERED)
        // 1.0, 1.1, ... 9.0 is 81 values: a slider with 79 steps between its ends.
        assertEquals(79, StarRange.SLIDER_STEPS)
    }

    @Test
    fun aStepIsATenthAndStopsAtTheEnds() {
        assertEquals(3.6, StarRange.step(3.5, 1), 0.0)
        assertEquals(3.4, StarRange.step(3.5, -1), 0.0)
        assertEquals(6.0, StarRange.step(5.9, 1), 0.0)
        assertEquals(1.0, StarRange.step(1.0, -1), 0.0)
        assertEquals(9.0, StarRange.step(9.0, 1), 0.0)
        // Eighty steps from one end reach the other, each on its tenth.
        var stars = StarRange.MIN
        repeat(80) { stars = StarRange.step(stars, 1) }
        assertEquals(9.0, stars, 0.0)
        // A slider's float lands on the tenth, and a value from outside comes inside.
        assertEquals(3.7, StarRange.snap(3.7f.toDouble()), 0.0)
        assertEquals(4.3, StarRange.snap(4.26), 0.0)
        assertEquals(9.0, StarRange.snap(12.0), 0.0)
        assertEquals(1.0, StarRange.snap(0.2), 0.0)
    }

    @Test
    fun theNoticeNamesTheRangeTheModelWasFittedOn() {
        val capabilities = ChartCapabilities("Ensomi R2", minStar = 2.0, maxStarExclusive = 6.0, kinds = emptyList())
        // The capabilities no longer bound what can be asked for: they say what is recommended.
        assertEquals(2.0, capabilities.recommendedStars.start, 0.0)
        assertEquals(6.0, capabilities.recommendedStars.endInclusive, 0.0)
        assertEquals("2", StarRange.bound(capabilities.recommendedStars.start))
        assertEquals("6", StarRange.bound(capabilities.recommendedStars.endInclusive))
        assertEquals("2.5", StarRange.bound(2.5))
        assertEquals("1", StarRange.bound(StarRange.MIN))
        assertEquals("9", StarRange.bound(StarRange.MAX))
    }
}
