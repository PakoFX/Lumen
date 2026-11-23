package com.pako.eminus4.data.repository

import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.remote.eminus.ExamsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Exámenes de todas las materias que pueden tenerlos (vigentes o con exámenes registrados). */
class ExamRepository(private val api: ExamsApi, private val courses: CourseRepository) {

    data class Loaded(val exams: List<ExamItem>, val coursesById: Map<Long, Course>)

    /**
     * Pide los exámenes de cada materia en paralelo. Si una materia falla, se omite (como antes):
     * mejor mostrar las demás que nada.
     */
    suspend fun loadAll(force: Boolean = false): Result<Loaded> = courses.refresh(force).mapCatching { all ->
        val withExams = all.filter { it.isActivo() || it.examenes > 0 }
        val exams = coroutineScope {
            withExams.map { course ->
                async {
                    resultOf { api.getExams(course.idCurso) }.getOrDefault(emptyList())
                        .onEach { it.idCurso = course.idCurso; it.nombreCurso = course.getNombreLimpio() }
                }
            }.awaitAll().flatten()
        }
        Loaded(exams, all.associateBy { it.idCurso })
    }
}
