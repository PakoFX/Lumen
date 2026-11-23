package com.pako.eminus4.ui.exams

import com.pako.eminus4.R
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.domain.ExamRules
import com.pako.eminus4.ui.common.DatosBloque

/**
 * Arma las filas de la lista: un bloque por materia (activas o pasadas) y, si está desplegado,
 * sus exámenes. Función pura: misma entrada, misma salida (se prueba sin teléfono).
 */
object ExamBlocks {

    data class Result(val rows: List<FilaExamen>, val activeSubjects: Int, val pastSubjects: Int)

    fun build(
        exams: List<ExamItem>,
        coursesById: Map<Long, Course>,
        showPast: Boolean,
        expanded: Set<Long>,
        now: Long = System.currentTimeMillis()
    ): Result {
        fun isActive(courseId: Long) = coursesById[courseId]?.isActivo() ?: true
        val bySubject = exams.groupBy { it.idCurso }
        val activeCount = bySubject.keys.count(::isActive)

        val groups = bySubject.filter { (id, _) -> isActive(id) != showPast }.toList()
            .sortedWith(compareByDescending<Pair<Long, List<ExamItem>>> { g -> g.second.count { ExamRules.isAvailable(it, now) } }
                .thenBy { it.second.first().nombreCurso })

        val rows = groups.flatMap { (courseId, subjectExams) ->
            val open = courseId in expanded || groups.size == 1
            listOf(header(courseId, subjectExams, coursesById[courseId], open, now)) +
                if (open) sorted(subjectExams, now).map { FilaExamen.Examen(it) } else emptyList()
        }
        return Result(rows, activeCount, bySubject.size - activeCount)
    }

    private fun header(courseId: Long, exams: List<ExamItem>, course: Course?, open: Boolean, now: Long): FilaExamen {
        val available = exams.count { ExamRules.isAvailable(it, now) }
        val upcoming = exams.count { ExamRules.isUpcoming(it, now) }
        val graded = exams.count { it.estaCalificado }
        val summary = listOfNotNull(
            plural(exams.size, "examen", "exámenes"),
            if (graded > 0) plural(graded, "calificado", "calificados") else null,
            if (upcoming > 0) plural(upcoming, "próximo", "próximos") else null
        ).joinToString(" · ")
        return FilaExamen.Encabezado(
            DatosBloque(
                courseId, exams.first().nombreCurso, summary, course?.getColorHexaLimpio(), open,
                aviso = if (available > 0) plural(available, "disponible", "disponibles") else null,
                avisoColor = R.color.badge_info_text
            )
        )
    }

    /** Disponibles primero, luego próximos, luego por fecha de cierre. */
    private fun sorted(exams: List<ExamItem>, now: Long) = exams.sortedWith(
        compareByDescending<ExamItem> { ExamRules.isAvailable(it, now) }
            .thenByDescending { ExamRules.isUpcoming(it, now) }
            .thenBy { DueDates.parseEminusDate(it.fechaTermino) ?: Long.MAX_VALUE }
    )

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
