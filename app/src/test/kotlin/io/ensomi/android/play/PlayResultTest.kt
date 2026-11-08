package io.ensomi.android.play

import kotlinx.serialization.encoding.CompositeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Results add (latency.md, Results and O6): the slots the game used, and each method's average. */
class PlayResultTest {
    private val timing = PlayTiming(-2.0, 8.0, 30.0, 1_000.0, audioSlot = 1, touchSlot = 0, keyboardSlot = 2)

    @Test
    fun bothMethodsThatHitNotesGetTheirOwnAverage() {
        val means = MethodMeans.of(MethodHits(true, 4, 16.0), MethodHits(true, 2, -10.0))
        assertEquals(MethodMeans(touchMs = 4.0, keysMs = -5.0), means)
    }

    @Test
    fun oneMethodAloneHasNoAverageOfItsOwn() {
        assertNull("touch only", MethodMeans.of(MethodHits(true, 4, 16.0), MethodHits()))
        assertNull("keys only", MethodMeans.of(MethodHits(), MethodHits(true, 3, 9.0)))
        assertNull("keys pressed but hit nothing", MethodMeans.of(MethodHits(true, 4, 16.0), MethodHits(true, 0, 0.0)))
    }

    @Test
    fun theSlotsNameTheKeyboardOnlyWhenKeysPressed() {
        assertEquals(ResultSlots(audio = 1, touch = 0, keyboard = 2), ResultSlots.of(timing, keysPressed = true))
        assertEquals(ResultSlots(audio = 1, touch = 0, keyboard = null), ResultSlots.of(timing, keysPressed = false))
    }

    @Test
    fun aGameWithoutSoundHasNoAudioSlot() {
        assertNull(ResultSlots.of(timing.copy(audioSlot = null), keysPressed = false).audio)
    }

    @Test
    fun aResultCarriesNoSuggestion() {
        assertEquals(CompositeDecoder.UNKNOWN_NAME, PlayResult.serializer().descriptor.getElementIndex("suggestedAdjustmentMs"))
    }
}
