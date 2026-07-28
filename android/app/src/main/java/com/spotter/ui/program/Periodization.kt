package com.spotter.ui.program

import com.spotter.data.local.entity.WorkoutProgramEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Where a program sits in its planned block. Pure display maths over the mirrored fields
 * (`weeks` / `deloadWeek` / `startedOn`) — the server computes the same thing on `ProgramOut`,
 * but `current_week`/`is_deload_week` are deliberately not mirrored into Room (they'd go stale in
 * a cache), so the client derives them from the start date at render time.
 *
 * Programs without `weeks` are open-ended and have no week info at all — those render exactly as
 * they always did.
 */
data class ProgramWeek(
    /** 1-based week within the block, clamped to [totalWeeks]; null when the program hasn't started. */
    val currentWeek: Int?,
    val totalWeeks: Int,
    val isDeloadWeek: Boolean,
)

/** The week/deload position of a program, or null when it has no planned length. */
fun programWeek(
    weeks: Int?,
    deloadWeek: Int?,
    startedOn: String?,
    today: LocalDate = LocalDate.now(),
): ProgramWeek? {
    val total = weeks?.takeIf { it > 0 } ?: return null
    val start = startedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return ProgramWeek(currentWeek = null, totalWeeks = total, isDeloadWeek = false)
    val days = ChronoUnit.DAYS.between(start, today)
    // A start date in the future (or today) is week 1; past the block, hold at the last week.
    val week = ((days / 7) + 1).coerceIn(1L, total.toLong()).toInt()
    return ProgramWeek(
        currentWeek = week,
        totalWeeks = total,
        isDeloadWeek = deloadWeek != null && deloadWeek == week,
    )
}

fun programWeek(program: WorkoutProgramEntity, today: LocalDate = LocalDate.now()): ProgramWeek? =
    programWeek(program.weeks, program.deloadWeek, program.startedOn, today)

/** "Week 3 of 8" once started, "8-week program" before then, null when open-ended. */
fun weekLabel(week: ProgramWeek?): String? = when {
    week == null -> null
    week.currentWeek == null -> "${week.totalWeeks}-week program"
    else -> "Week ${week.currentWeek} of ${week.totalWeeks}"
}

fun weekLabel(program: WorkoutProgramEntity, today: LocalDate = LocalDate.now()): String? =
    weekLabel(programWeek(program, today))

/** The one-line explanation shown beside the DELOAD WEEK badge. */
const val DELOAD_EXPLANATION =
    "Lighter loads and fewer sets this week — planned recovery."
