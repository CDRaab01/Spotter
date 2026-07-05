package com.spotter.ui.workout

import com.spotter.data.model.ExercisePrior
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Pure DTO→display mapping for the workout progression suggestion (ROADMAP2 T3 #1). */
class ProgressionUiTest {

    private val fmt: (Double) -> String = { "${it.toInt()} lb" }

    private fun prior(
        action: String? = null,
        suggestedWeight: Double? = null,
        suggestedReason: String? = null,
        e1rm: Double? = null,
        isPr: Boolean = false,
    ) = ExercisePrior(
        exerciseId = "e1",
        reps = 5,
        date = "2026-07-04",
        suggestedWeight = suggestedWeight,
        suggestedReason = suggestedReason,
        action = action,
        e1rm = e1rm,
        isPr = isPr,
    )

    @Test
    fun add_weight_formats_suggestion_and_e1rm() {
        val ui = progressionUi(
            prior("add_weight", 105.0, "All sets at 5+ reps — add 5 lb.", e1rm = 116.7),
            fmt,
        )
        assertEquals("Suggested: 105 lb — All sets at 5+ reps — add 5 lb.", ui.suggestionText)
        assertFalse(ui.isDeload)
        assertFalse(ui.showPr)
        assertEquals("e1RM ~116 lb", ui.e1rmText)
    }

    @Test
    fun deload_flags_caution() {
        val ui = progressionUi(prior("deload", 121.5, "Stalled 3 sessions — deload to 121.5 lb."), fmt)
        assertTrue(ui.isDeload)
        assertTrue(ui.suggestionText!!.contains("Suggested: 121 lb"))
    }

    @Test
    fun pr_shows_badge() {
        assertTrue(progressionUi(prior("add_weight", 105.0, "add 5 lb.", isPr = true), fmt).showPr)
    }

    @Test
    fun bodyweight_shows_reason_only_no_e1rm() {
        val ui = progressionUi(prior("bodyweight", null, "Bodyweight — add reps before adding load."), fmt)
        assertEquals("Bodyweight — add reps before adding load.", ui.suggestionText)
        assertNull(ui.e1rmText)
    }
}
