package com.pako.eminus4.domain

import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.model.ReminderSource
import com.pako.eminus4.data.model.ReminderType
import com.pako.eminus4.data.model.ScheduledAlarm

/** Lo que eligió la persona en Perfil → Notificaciones. */
data class ReminderSettings(
    val enabled: Boolean = true,
    val tasks: Boolean = true,
    val exams: Boolean = true,
    val examOnOpen: Boolean = true,
    val forums: Boolean = false,
    val overdue: Boolean = true,
    val minutesBefore: Set<Int> = setOf(24 * 60, 5 * 60, 60)
)

/** Qué recordar y cuándo. Sin Android: se prueba con datos de ejemplo. */
object ReminderPlanner {

    const val MAX_ALARMS = 400
    const val MOMENT_OVERDUE = "vencida"
    const val MOMENT_OPENS = "abre"
    /** Una alarma que sonaría en menos de 30 s ya no se programa. */
    private const val MIN_LEAD_MS = 30_000L
    private const val MINUTE_MS = 60_000L

    fun momentBefore(minutes: Int) = "antes_$minutes"

    /** Código único de la alarma (también es el del PendingIntent: no cambiar la fórmula). */
    fun alarmCode(key: String, moment: String) = "$key|$moment".hashCode()

    // ---------- fuentes ----------

    /** Tareas por hacer (solo pendientes: lo entregado deja de recordarse). */
    fun fromActivities(items: List<CourseActivityItem>): List<ReminderSource> =
        items.filter { it.activity.studentStatus.isPending }.map {
            ReminderSource(
                ReminderType.TASK, it.courseId, it.activity.idActividad, it.activity.nombre, it.courseName,
                DueDates.parseEminusDate(it.activity.fechaInicio), it.activity.terminoMillis,
                points = it.activity.calificacionMax, inTeam = it.activity.esPorEquipos
            )
        }

    /** Exámenes sin calificar que aún no cierran. */
    fun fromExams(exams: List<ExamItem>): List<ReminderSource> =
        exams.filter { !it.estaCalificado && !it.estaVencido }.map {
            ReminderSource(
                ReminderType.EXAM, it.idCurso, it.idExamen, it.titulo, it.nombreCurso,
                DueDates.parseEminusDate(it.fechaInicio), DueDates.parseEminusDate(it.fechaTermino), points = it.valor
            )
        }

    /** Foros abiertos (para avisar antes de que cierren; la prórroga manda). */
    fun fromForums(forums: List<ForumItem>): List<ReminderSource> =
        forums.filter { it.estaAbierto() }.map {
            ReminderSource(
                ReminderType.FORUM, it.idCurso, it.idForo, it.titulo, it.nombreCurso,
                DueDates.parseEminusDate(it.fechaInicio),
                DueDates.parseEminusDate(it.fechaProrroga) ?: DueDates.parseEminusDate(it.fechaTermino)
            )
        }

    // ---------- alarmas ----------

    /**
     * Las alarmas que tocan, de la más próxima a la más lejana (máximo [MAX_ALARMS]).
     * Si las fuentes son de otra cuenta, ninguna.
     */
    fun plan(
        sources: Map<ReminderType, List<ReminderSource>>,
        settings: ReminderSettings,
        now: Long,
        sourcesOwner: String? = null,
        currentUser: String? = null
    ): List<ScheduledAlarm> {
        val foreign = sourcesOwner != null && currentUser != null && sourcesOwner != currentUser
        if (!settings.enabled || foreign) return emptyList()

        val minutes = settings.minutesBefore.sortedDescending()
        val alarms = mutableListOf<ScheduledAlarm>()
        fun add(source: ReminderSource, at: Long, moment: String, minutesBefore: Int) {
            if (at <= now + MIN_LEAD_MS) return
            alarms += ScheduledAlarm(alarmCode(source.key, moment), source.key, at, moment, minutesBefore)
        }
        fun beforeClosing(source: ReminderSource) {
            val end = source.end ?: return
            minutes.forEach { m -> add(source, end - m * MINUTE_MS, momentBefore(m), m) }
        }

        if (settings.tasks) sources[ReminderType.TASK].orEmpty().forEach { s ->
            beforeClosing(s)
            if (settings.overdue) s.end?.let { add(s, it + MINUTE_MS, MOMENT_OVERDUE, 0) }
        }
        if (settings.exams) sources[ReminderType.EXAM].orEmpty().forEach { s ->
            if (settings.examOnOpen) s.start?.let { add(s, it, MOMENT_OPENS, 0) }
            beforeClosing(s)
        }
        if (settings.forums) sources[ReminderType.FORUM].orEmpty().forEach(::beforeClosing)

        return alarms.sortedBy { it.at }.take(MAX_ALARMS)
    }

    /** "2 días 3 h", "5 h 20 min", "45 min". */
    fun duration(ms: Long): String {
        val min = ms / MINUTE_MS
        val d = min / (24 * 60)
        val h = (min % (24 * 60)) / 60
        val m = min % 60
        return when {
            d > 0 -> "$d día${if (d == 1L) "" else "s"}" + if (h > 0) " $h h" else ""
            h > 0 -> "$h h" + if (m > 0) " $m min" else ""
            else -> "${m.coerceAtLeast(1)} min"
        }
    }
}
