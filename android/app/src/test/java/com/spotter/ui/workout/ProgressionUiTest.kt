package com.spotter.ui.workout

import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SetLogOut
import com.spotter.util.WeightUnit
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

    // ── target header ─────────────────────────────────────────────────────────

    private fun seeded(weight: Double?, targetWeight: Double?) = SetLogOut(
        id = "s1", sessionId = "x", exerciseId = "e1", setNumber = 1, reps = 8,
        weight = weight, targetSets = 3, targetReps = 8, targetWeight = targetWeight,
    )

    @Test
    fun header_shows_the_seeded_set_load_over_the_routine_prescription() {
        // Server seeded 40 lb from related-lift history; the routine still says 10.
        assertEquals("3 × 8 @ 40 lb", buildTargetHeader(seeded(40.0, 10.0), WeightUnit.LBS))
    }

    @Test
    fun header_falls_back_to_the_prescription_and_keeps_bodyweight() {
        assertEquals("3 × 8 @ 10 lb", buildTargetHeader(seeded(null, 10.0), WeightUnit.LBS))
        assertEquals("3 × 8  BW", buildTargetHeader(seeded(40.0, null), WeightUnit.LBS))
    }
}
