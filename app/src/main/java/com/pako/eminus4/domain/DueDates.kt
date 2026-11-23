package com.pako.eminus4.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fechas legibles para humanos: "Vence mañana, 23:45", "Vence en 3 días", "hace 2 h".
 * Eminus envía fechas locales sin zona: "2026-11-18T23:45:00".
 */
object DueDates {

    enum class Urgency { OVERDUE, CRITICAL, SOON, NORMAL, NO_DATE }

    data class DueDate(val text: String, val urgency: Urgency)

    private val spanish = Locale("es", "MX")

    /** "2026-11-18T23:45:00" (hora local) → milisegundos, o null si no es una fecha válida. */
    fun parseEminusDate(iso: String?): Long? {
        if (iso.isNullOrBlank() || iso.length < 19) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.substring(0, 19))?.time
        } catch (_: Exception) {
            null
        }
    }

    /** Texto de vencimiento con su nivel de urgencia. */
    fun dueDate(deadlineMillis: Long?, now: Long = System.currentTimeMillis()): DueDate {
        if (deadlineMillis == null) return DueDate("Sin fecha límite", Urgency.NO_DATE)
        val remaining = deadlineMillis - now
        if (remaining < 0) return DueDate("Venció el ${format("d MMM", deadlineMillis)}", Urgency.OVERDUE)
        if (remaining < TimeUnit.HOURS.toMillis(1)) {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining).coerceAtLeast(1)
            return DueDate("Vence en $minutes min", Urgency.CRITICAL)
        }
        val time = format("HH:mm", deadlineMillis)
        return when (val days = calendarDaysBetween(now, deadlineMillis)) {
            0 -> DueDate("Vence hoy, $time", Urgency.CRITICAL)
            1 -> DueDate("Vence mañana, $time", Urgency.CRITICAL)
            2, 3 -> DueDate("Vence en $days días · ${format("EEE d", deadlineMillis)}, $time", Urgency.SOON)
            in 4..7 -> DueDate("Vence en $days días · ${format("EEE d MMM", deadlineMillis)}", Urgency.NORMAL)
            else -> DueDate("Vence el ${shortDate(deadlineMillis)}", Urgency.NORMAL)
        }
    }

    /** "16 nov", o "16 nov 2025" si no es del año actual. */
    fun shortDate(millis: Long?): String {
        if (millis == null) return "—"
        val sameYear = Calendar.getInstance().get(Calendar.YEAR) ==
            Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
        return format(if (sameYear) "d MMM" else "d MMM yyyy", millis)
    }

    /** "hace 5 min", "hace 3 h", "ayer", "hace 4 días", o la fecha corta. */
    fun timeAgo(millis: Long?, now: Long = System.currentTimeMillis()): String {
        if (millis == null) return ""
        val diff = now - millis
        if (diff < 0) return shortDate(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val days = calendarDaysBetween(millis, now)
        return when {
            minutes < 1 -> "ahora"
            minutes < 60 -> "hace $minutes min"
            days == 0 -> "hace ${minutes / 60} h"
            days == 1 -> "ayer"
            days < 7 -> "hace $days días"
            else -> shortDate(millis)
        }
    }

    /** "Lunes 16 de noviembre" */
    fun todayLong(): String = format("EEEE d 'de' MMMM", System.currentTimeMillis())
        .replaceFirstChar { it.uppercase() }

    private fun format(pattern: String, millis: Long): String =
        SimpleDateFormat(pattern, spanish).format(millis).replace(".", "")

    /** Diferencia en días de calendario (hoy = 0, mañana = 1…). */
    private fun calendarDaysBetween(from: Long, to: Long): Int {
        fun startOfDay(m: Long) = Calendar.getInstance().apply {
            timeInMillis = m
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return TimeUnit.MILLISECONDS.toDays(startOfDay(to) - startOfDay(from)).toInt()
    }
}
