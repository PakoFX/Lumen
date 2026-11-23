package com.pako.eminus4.ui.forums

import com.pako.eminus4.R
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.ui.common.DatosBloque

/** Filas de la lista de foros: un bloque por materia y, si está desplegado, sus foros. Función pura. */
object ForumBlocks {

    data class Result(val rows: List<FilaForo>, val activeSubjects: Int, val pastSubjects: Int)

    fun build(forums: List<ForumItem>, coursesById: Map<Long, Course>, showPast: Boolean, expanded: Set<Long>): Result {
        fun isActive(courseId: Long) = coursesById[courseId]?.isActivo() ?: true
        val bySubject = forums.groupBy { it.idCurso }
        val activeCount = bySubject.keys.count(::isActive)

        // Primero las materias con aportes nuevos, luego las que tienen foros abiertos
        val groups = bySubject.filter { (id, _) -> isActive(id) != showPast }.toList()
            .sortedWith(compareByDescending<Pair<Long, List<ForumItem>>> { g -> g.second.sumOf { it.nuevosComentarios } }
                .thenByDescending { g -> g.second.count { it.estaAbierto() } }
                .thenBy { it.second.first().nombreCurso })

        val rows = groups.flatMap { (courseId, subjectForums) ->
            val open = courseId in expanded || groups.size == 1
            listOf(header(courseId, subjectForums, coursesById[courseId], open)) +
                if (open) sorted(subjectForums).map { FilaForo.Foro(it) } else emptyList()
        }
        return Result(rows, activeCount, bySubject.size - activeCount)
    }

    private fun header(courseId: Long, forums: List<ForumItem>, course: Course?, open: Boolean): FilaForo {
        val withNews = forums.count { it.nuevosComentarios > 0 }
        val openForums = forums.count { it.estaAbierto() }
        val summary = listOfNotNull(
            plural(forums.size, "foro", "foros"),
            if (openForums > 0) plural(openForums, "abierto", "abiertos") else null
        ).joinToString(" · ")
        return FilaForo.Encabezado(
            DatosBloque(
                courseId, forums.first().nombreCurso, summary, course?.getColorHexaLimpio(), open,
                aviso = if (withNews > 0) "$withNews con novedades" else null,
                avisoColor = R.color.badge_info_text
            )
        )
    }

    private fun sorted(forums: List<ForumItem>) = forums.sortedWith(
        compareByDescending<ForumItem> { it.nuevosComentarios }.thenByDescending { it.estaAbierto() }.thenBy { it.titulo }
    )

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
