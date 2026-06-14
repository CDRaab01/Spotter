package com.spotter.ui.cardio

import com.spotter.data.model.CardioDay
import com.spotter.data.model.CardioPhase
import com.spotter.data.model.CardioProgram
import com.spotter.data.model.CardioProgramType
import com.spotter.data.model.CardioWeek
import com.spotter.data.model.Interval

/**
 * The static, bundled cardio program catalog. Definitions are not user data — they ship with the
 * app. Sessions performed against them are persisted separately (server + Room mirror).
 *
 * The Couch to 5K table below is an 8-week × 3-day progression: every session is a 5-minute
 * warm-up walk + run/walk intervals + a 5-minute cool-down walk, ramping from short jog/walk
 * repeats up to a continuous 30-minute run in week 8. Each day's intervals sum to its stated
 * total (asserted in CardioProgramsTest).
 */
object CardioPrograms {

    const val C25K_ID = "c25k"
    const val FREE_RUN_ID = "free_run"

    const val WARM_UP_SEC = 5 * 60
    const val COOL_DOWN_SEC = 5 * 60

    val c25k: CardioProgram by lazy { buildC25k() }

    val freeRun = CardioProgram(
        id = FREE_RUN_ID,
        name = "Free Run",
        type = CardioProgramType.FREE,
        description = "An open-ended run on your terms — go continuous, or set your own warm-up, " +
            "run/walk repeats, and cool-down. No schedule, no targets.",
        weeks = null,
    )

    val all: List<CardioProgram> = listOf(c25k, freeRun)

    fun byId(id: String): CardioProgram? = all.firstOrNull { it.id == id }

    /** The intervals for a specific guided day, or null if out of range. */
    fun dayIntervals(programId: String, week: Int, day: Int): List<Interval>? {
        val program = byId(programId) ?: return null
        val w = program.weeks?.firstOrNull { it.weekNumber == week } ?: return null
        return w.days.firstOrNull { it.dayNumber == day }?.intervals
    }

    fun day(programId: String, week: Int, day: Int): CardioDay? {
        val program = byId(programId) ?: return null
        val w = program.weeks?.firstOrNull { it.weekNumber == week } ?: return null
        return w.days.firstOrNull { it.dayNumber == day }
    }

    // -- builders -----------------------------------------------------------

    /** Wrap a middle (run/walk) sequence with the standard warm-up and cool-down walks. */
    private fun session(vararg middle: Interval): List<Interval> {
        val list = ArrayList<Interval>(middle.size + 2)
        list.add(Interval(CardioPhase.WARM_UP, WARM_UP_SEC))
        list.addAll(middle)
        list.add(Interval(CardioPhase.COOL_DOWN, COOL_DOWN_SEC))
        return list
    }

    private fun run(sec: Int) = Interval(CardioPhase.RUN, sec)
    private fun walk(sec: Int) = Interval(CardioPhase.WALK, sec)

    /** Repeat a [block] of intervals [times] times. */
    private fun repeat(times: Int, vararg block: Interval): List<Interval> {
        val out = ArrayList<Interval>(times * block.size)
        repeat(times) { out.addAll(block) }
        return out
    }

    private fun day(dayNumber: Int, intervals: List<Interval>): CardioDay =
        CardioDay(
            dayNumber = dayNumber,
            totalDurationSec = intervals.sumOf { it.durationSec },
            intervals = intervals,
        )

    /** A week whose three days share the same interval plan. */
    private fun uniformWeek(weekNumber: Int, intro: String, middle: List<Interval>): CardioWeek {
        val intervals = session(*middle.toTypedArray())
        return CardioWeek(
            weekNumber = weekNumber,
            intro = intro,
            days = (1..3).map { day(it, intervals) },
        )
    }

    private fun buildC25k(): CardioProgram {
        val weeks = listOf(
            uniformWeek(
                1,
                "Week 1 — ease in. Short jogs with generous walk breaks. The goal is just to show up.",
                // 8 × (jog 60s / walk 90s) = 20 min
                repeat(8, run(60), walk(90)),
            ),
            uniformWeek(
                2,
                "We're going to step it up a little. Slightly longer jogs, a touch less walking.",
                // 6 × (jog 90s / walk 120s) = 21 min
                repeat(6, run(90), walk(120)),
            ),
            uniformWeek(
                3,
                "Mixing it up: a couple of longer jogs in among the shorter ones. Find a steady pace.",
                // 2 × (jog 90 / walk 90 / jog 180 / walk 180) = 18 min
                repeat(2, run(90), walk(90), run(180), walk(180)),
            ),
            uniformWeek(
                4,
                "The jogs are getting longer now. Breathe, relax your shoulders, and keep it easy.",
                // jog 3, walk 1.5, jog 5, walk 2.5, jog 3, walk 1.5, jog 5 = 21.5 min
                listOf(
                    run(180), walk(90), run(300), walk(150),
                    run(180), walk(90), run(300),
                ),
            ),
            CardioWeek(
                5,
                "A big week — each day is different, building toward your first long continuous run.",
                days = listOf(
                    // D1: jog 5, walk 3, jog 5, walk 3, jog 5 = 21 min
                    day(1, session(run(300), walk(180), run(300), walk(180), run(300))),
                    // D2: jog 8, walk 5, jog 8 = 21 min
                    day(2, session(run(480), walk(300), run(480))),
                    // D3: jog 20 continuous
                    day(3, session(run(1200))),
                ),
            ),
            CardioWeek(
                6,
                "You ran 20 minutes straight. Now we consolidate before the longer continuous runs.",
                days = listOf(
                    // D1: jog 5, walk 3, jog 8, walk 3, jog 5 = 24 min
                    day(1, session(run(300), walk(180), run(480), walk(180), run(300))),
                    // D2: jog 10, walk 3, jog 10 = 23 min
                    day(2, session(run(600), walk(180), run(600))),
                    // D3: jog 25 continuous
                    day(3, session(run(1500))),
                ),
            ),
            uniformWeek(
                7,
                "Continuous running now. Settle into a rhythm you could hold a conversation at.",
                // jog 25 min continuous
                listOf(run(1500)),
            ),
            uniformWeek(
                8,
                "The final week — 30 minutes of continuous running. You're a runner now.",
                // jog 30 min continuous
                listOf(run(1800)),
            ),
        )
        return CardioProgram(
            id = C25K_ID,
            name = "Couch to 5K",
            type = CardioProgramType.GUIDED,
            description = "A structured 8-week, 3-day-a-week plan that builds from short jog/walk " +
                "intervals to a continuous 30-minute run. Check with your doctor before starting a " +
                "new exercise program.",
            weeks = weeks,
        )
    }
}
