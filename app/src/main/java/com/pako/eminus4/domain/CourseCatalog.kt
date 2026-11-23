package com.pako.eminus4.domain

import com.pako.eminus4.data.model.Course
import java.text.Normalizer

/**
 * Apartados de la lista de cursos (mismos criterios que Eminus web) y su buscador.
 *  - ACTIVE: vigentes y no archivados.
 *  - ALL: activos → cerrados → archivados (los nuevos aparecen debajo de los activos, no fuera de la vista).
 *  - ARCHIVED: los que el alumno archivó.
 */
object CourseCatalog {

    enum class Section(val title: String) { ACTIVE("Activos"), ALL("Todos los cursos"), ARCHIVED("Archivados") }

    data class Counts(val active: Int, val all: Int, val archived: Int) {
        fun of(section: Section) = when (section) {
            Section.ACTIVE -> active
            Section.ALL -> all
            Section.ARCHIVED -> archived
        }
    }

    data class Selection(val courses: List<Course>, val counts: Counts)

    /** El buscador solo aplica a Todos / Archivados (pueden ser muchas materias). */
    fun hasSearch(section: Section) = section != Section.ACTIVE

    fun select(courses: List<Course>, section: Section, query: String): Selection {
        val (archived, visible) = courses.partition { it.esArchivado() }
        val (active, closed) = visible.partition { it.isActivo() }
        val inSection = when (section) {
            Section.ACTIVE -> active
            Section.ALL -> active + closed + archived
            Section.ARCHIVED -> archived
        }
        val words = if (hasSearch(section)) normalize(query).split(' ').filter { it.isNotBlank() } else emptyList()
        val matching = if (words.isEmpty()) inSection else inSection.filter { course ->
            val haystack = normalize(course.nombre + " " + SubjectName.displayName(course.nombre))
            words.all { haystack.contains(it) }
        }
        return Selection(matching, Counts(active.size, active.size + closed.size + archived.size, archived.size))
    }

    /** Minúsculas y sin acentos: "Diseño" encuentra "DISENO" y al revés. */
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ").trim()
}
