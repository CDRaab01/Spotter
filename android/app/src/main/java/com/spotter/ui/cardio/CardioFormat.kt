package com.spotter.ui.cardio

import com.spotter.data.model.CardioPhase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Shared cardio display formatting. */
object CardioFormat {

    /** Seconds → `M:SS` (or `MM:SS`). */
    fun clock(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** A rounded "31 mins" style label for an interval/session total. */
    fun minutesLabel(totalSec: Int): String {
        val mins = Math.round(totalSec / 60.0).toInt()
        return "$mins min${if (mins == 1) "" else "s"}"
    }

    fun parseDate(iso: String?): LocalDate? = iso?.let {
        try {
            Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate()
        } catch (_: Exception) {
            null
        }
    }

    /** "Saturday, Apr 25" — matches the overview screenshot. */
    fun longDate(date: LocalDate): String {
        val dow = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val md = date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        return "$dow, $md"
    }

    fun shortDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))

    /** Single-letter abbreviation used on the compact segmented interval bar. */
    fun shortPhase(phase: CardioPhase): String = when (phase) {
        CardioPhase.WARM_UP -> "W"
        CardioPhase.RUN -> "Run"
        CardioPhase.WALK -> "Walk"
        CardioPhase.COOL_DOWN -> "C"
    }
}
