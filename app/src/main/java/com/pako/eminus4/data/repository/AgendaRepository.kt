package com.pako.eminus4.data.repository

import com.pako.eminus4.core.isSessionExpired
import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.data.remote.eminus.ActivitiesApi
import com.pako.eminus4.data.remote.eminus.ExamsApi
import com.pako.eminus4.data.remote.eminus.ForumsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * La agenda del alumno: actividades de todas las materias vigentes, más mensajes sin leer, exámenes y
 * foros (lo que pinta Avisos). Guarda lo último cargado para que otras pantallas (Pendientes,
 * Mis actividades) lo muestren al instante sin volver a pedirlo.
 */
class AgendaRepository(
    private val courses: CourseRepository,
    private val activities: ActivitiesApi,
    private val exams: ExamsApi,
    private val forums: ForumsApi,
    private val messages: MessageRepository
) {

    data class Agenda(
        val courses: List<Course>,
        val activities: List<CourseActivityItem>,
        /** Cuántos cursos no se pudieron consultar (sin conexión, sesión caducada…). */
        val coursesWithErrors: Int,
        val sessionExpired: Boolean,
        /** Los cursos vienen de la copia del teléfono porque Eminus no respondió. */
        val fromCache: Boolean
    ) {
        /** Solo con datos completos y frescos se programan recordatorios y se calculan novedades. */
        val isComplete: Boolean get() = !fromCache && !sessionExpired && coursesWithErrors == 0
    }

    /** Resultado de pedir algo a varias materias: lo que llegó y si llegó todo. */
    data class Partial<T>(val items: List<T>, val complete: Boolean)

    /** Lo último que cargó Avisos (para Pendientes y Mis actividades). */
    data class Snapshot(
        val agenda: Agenda? = null,
        val unreadMessages: List<MessageItem> = emptyList(),
        val availableExams: List<ExamItem> = emptyList(),
        val forumsWithNews: List<ForumItem> = emptyList()
    ) {
        val hasData: Boolean get() = agenda?.courses?.isNotEmpty() == true
    }

    @Volatile var snapshot: Snapshot = Snapshot()
        private set

    fun updateSnapshot(snapshot: Snapshot) {
        this.snapshot = snapshot
    }

    /** Al cambiar de cuenta, no mostrar la agenda de la persona anterior. */
    fun clear() {
        snapshot = Snapshot()
    }

    /** Actividades de las materias vigentes (si Eminus no responde, con los cursos guardados). */
    suspend fun loadAgenda(): Agenda {
        val fresh = courses.refresh()
        val sessionExpired = fresh.exceptionOrNull()?.isSessionExpired() == true
        val active = (fresh.getOrNull() ?: courses.cached()).filter { it.isActivo() }
        val fromCache = fresh.isFailure
        if (active.isEmpty()) return Agenda(active, emptyList(), 0, sessionExpired, fromCache)

        val perCourse = coroutineScope {
            active.map { course -> async { course to resultOf { activities.getActivities(course.idCurso) } } }.awaitAll()
        }
        val items = perCourse.flatMap { (course, result) ->
            result.getOrNull().orEmpty()
                .filter { !it.titulo.isNullOrBlank() || !it.nombreRaw.isNullOrBlank() }
                .map { CourseActivityItem(it, course.idCurso, course.getNombreLimpio(), course.getColorHexaLimpio()) }
        }
        val failures = perCourse.mapNotNull { it.second.exceptionOrNull() }
        return Agenda(
            active, items, failures.size,
            sessionExpired = sessionExpired || failures.any { it.isSessionExpired() },
            fromCache = fromCache
        ).also { agenda -> if (agenda.activities.isNotEmpty()) snapshot = snapshot.copy(agenda = agenda) }
    }

    suspend fun loadUnreadMessages(): Result<List<MessageItem>> =
        messages.getMessages(INBOX).map { list -> list.filter { it.esNoLeido } }

    /** Exámenes de las materias que tienen. */
    suspend fun loadExams(courses: List<Course>): Partial<ExamItem> = loadPerCourse(courses.filter { it.examenes > 0 }) { course ->
        exams.getExams(course.idCurso).onEach { it.idCurso = course.idCurso; it.nombreCurso = course.getNombreLimpio() }
    }

    /** Foros de las materias que tienen. */
    suspend fun loadForums(courses: List<Course>): Partial<ForumItem> = loadPerCourse(courses.filter { it.foros > 0 }) { course ->
        forums.getForums(course.idCurso).onEach {
            it.idCurso = course.idCurso
            it.nombreCurso = course.getNombreLimpio()
            it.cursoActivo = course.isActivo()
        }
    }

    private suspend fun <T> loadPerCourse(courses: List<Course>, fetch: suspend (Course) -> List<T>): Partial<T> {
        val results = coroutineScope { courses.map { async { resultOf { fetch(it) } } }.awaitAll() }
        return Partial(results.flatMap { it.getOrNull().orEmpty() }, complete = results.all { it.isSuccess })
    }

    private companion object {
        const val INBOX = 1
    }
}
