package com.pako.eminus4.domain

import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase

/** Una clase en un día concreto. `end`: la que indicó la persona o la del PDF de MiUV; null = desconocida. */
data class ClassBlock(val subject: ClaseHorario, val session: SesionClase, val end: Int?) {
    val start: Int? get() = session.inicioMin
}

/** Qué mostrar en la tarjeta de arriba del día de hoy. */
sealed class NowCard {
    abstract val block: ClassBlock
    data class InClass(override val block: ClassBlock) : NowCard()
    /** Empezó y no se sabe a qué hora termina. */
    data class Started(override val block: ClassBlock, val next: ClassBlock?) : NowCard()
    data class Next(override val block: ClassBlock, val minutesLeft: Int) : NowCard()
}

/** Reglas del horario (días, clases del día, en clase/siguiente, tiempo libre). Todo en minutos desde medianoche. */
object ScheduleDay {

    private const val MIN_FREE_GAP = 10
    /** Sin hora de término, una clase que empezó hace más de 3 h ya no se considera "en curso". */
    private const val MAX_OPEN_CLASS = 180

    /** Lunes a viernes siempre; sábado y domingo solo si hay clases. */
    fun visibleDays(classes: List<ClaseHorario>): List<Int> {
        val withClass = daysWithClass(classes)
        return (1..5) + listOf(6, 7).filter { it in withClass }
    }

    /** Hoy si se muestra; si no (fin de semana sin clases), el primer día con clases o el lunes. */
    fun initialDay(classes: List<ClaseHorario>, today: Int): Int {
        val days = visibleDays(classes)
        val withClass = daysWithClass(classes)
        return if (today in days) today else days.firstOrNull { it in withClass } ?: 1
    }

    fun sessionCount(classes: List<ClaseHorario>, day: Int) = classes.sumOf { c -> c.sesiones.count { it.numeroDia == day } }

    fun blocksOf(classes: List<ClaseHorario>, day: Int, manualEnd: (ClaseHorario, SesionClase) -> Int?): List<ClassBlock> =
        classes.flatMap { c -> c.sesiones.filter { it.numeroDia == day }.map { s -> ClassBlock(c, s, manualEnd(c, s) ?: s.finMin) } }
            .sortedBy { it.start ?: Int.MAX_VALUE }

    /** Ya pasó: terminó (si se sabe a qué hora) o ya empezó una clase posterior. */
    fun hasPassed(block: ClassBlock, all: List<ClassBlock>, now: Int): Boolean {
        val start = block.start ?: return false
        if (block.end != null) return now > block.end   // termina a las ##:59: ese minuto aún cuenta
        return all.any { other -> (other.start ?: -1) in (start + 1)..now }
    }

    fun isNow(block: ClassBlock, now: Int): Boolean {
        val start = block.start ?: return false
        return block.end != null && now in start..block.end
    }

    fun nowCard(today: List<ClassBlock>, now: Int): NowCard? {
        today.firstOrNull { isNow(it, now) }?.let { return NowCard.InClass(it) }
        val next = today.firstOrNull { (it.start ?: -1) > now }
        val started = today.lastOrNull { b ->
            val start = b.start
            start != null && start <= now && b.end == null && now - start < MAX_OPEN_CLASS && !hasPassed(b, today, now)
        }
        if (started != null) return NowCard.Started(started, next)
        return next?.let { NowCard.Next(it, (it.start ?: now) - now) }
    }

    /** Tiempo libre entre dos clases seguidas, o null si no se sabe o es muy corto. "12:59" → libre desde 13:00. */
    fun freeGap(previous: ClassBlock, next: ClassBlock): IntRange? {
        val prevEnd = previous.end ?: return null
        val from = if (prevEnd % 60 == 59) prevEnd + 1 else prevEnd
        val until = next.start ?: return null
        return if (until - from >= MIN_FREE_GAP) from until until else null
    }

    fun hhmm(minutes: Int) = "%d:%02d".format(minutes / 60, minutes % 60)

    /** "1 h 20 min", "45 min", "2 h". */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** "Salón 105 · Edif. A-TEEZ" (el salón de la sesión manda sobre el de la materia). */
    fun place(subject: ClaseHorario, session: SesionClase): String {
        val room = session.salon?.takeIf { it.isNotBlank() } ?: subject.salon?.takeIf { it.isNotBlank() }
        val building = subject.edificio?.takeIf { it.isNotBlank() }?.let { "Edif. $it" }
        return listOfNotNull(room?.let { if (it.contains("sal", true)) it else "Salón $it" }, building).joinToString(" · ")
    }

    private fun daysWithClass(classes: List<ClaseHorario>) =
        classes.flatMap { it.sesiones }.map { it.numeroDia }.filter { it > 0 }.toSet()
}
