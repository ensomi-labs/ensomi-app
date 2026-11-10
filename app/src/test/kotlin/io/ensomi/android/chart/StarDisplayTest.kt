package io.ensomi.android.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarDisplayTest {
    @Test
    fun theMeasuredStarIsTheChartsStar() {
        val display = StarDisplay.of(asked = 4.5, measured = 4.62)
        assertEquals(4.62, display.stars, 0.0)
        assertEquals(4.5, display.asked!!, 0.0)
        assertEquals(StarGap.NONE, display.gap)
    }

    @Test
    fun theStarAskedForIsSaidOnlyWhenTheTenthsDiffer() {
        // 4.54 and 4.5 both show as 4.5: saying "asked 4.5" beside it would say nothing.
        assertNull(StarDisplay.of(4.5, 4.54).asked)
        assertNull(StarDisplay.of(4.5, 4.46).asked)
        assertNull(StarDisplay.of(4.5, 4.5).asked)
        // 4.56 shows as 4.6, 4.44 as 4.4.
        assertEquals(4.5, StarDisplay.of(4.5, 4.56).asked!!, 0.0)
        assertEquals(4.5, StarDisplay.of(4.5, 4.44).asked!!, 0.0)
    }

    @Test
    fun moreThanHalfAStarApartIsFar() {
        assertEquals(StarGap.NONE, StarDisplay.of(4.0, 4.5).gap)
        assertEquals(StarGap.NONE, StarDisplay.of(4.0, 3.5).gap)
        assertEquals(StarGap.HARDER, StarDisplay.of(4.0, 4.6).gap)
        assertEquals(StarGap.EASIER, StarDisplay.of(4.0, 3.4).gap)
        // The ends of the slider, about where the model's hardest and easiest charts were measured.
        assertEquals(StarGap.EASIER, StarDisplay.of(5.9, 5.3).gap)
        assertEquals(StarGap.HARDER, StarDisplay.of(2.0, 2.6).gap)
    }

    @Test
    fun aChartWithoutAMeasurementShowsTheStarAskedFor() {
        assertEquals(StarDisplay(3.5, null, StarGap.NONE), StarDisplay.of(3.5, null))
        // A star that is not a number is no measurement.
        assertEquals(StarDisplay(3.5, null, StarGap.NONE), StarDisplay.of(3.5, Double.NaN))
        assertEquals(StarDisplay(3.5, null, StarGap.NONE), StarDisplay.of(3.5, Double.POSITIVE_INFINITY))
    }
}
