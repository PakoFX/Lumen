package com.pako.eminus4.data.repository

import com.pako.eminus4.core.isSessionExpired
import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.local.CourseCache
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.remote.eminus.CoursesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * Cursos del alumno, con copia local para usar la app sin conexión.
 * Varias pantallas piden la lista al abrir la app: si llegan casi juntas, comparten una sola
 * petición a Eminus (antes salían 3 iguales).
 */
class CourseRepository(
    private val api: CoursesApi,
    private val cache: CourseCache,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var lastFetchAt = 0L

    /** Lo último guardado (puede estar vacío la primera vez). */
    fun cached(): List<Course> = cache.getCachedCourses()

    /**
     * Pide la lista a Eminus y la guarda. Si se pidió hace menos de [reuseWindowMs] (otra pantalla,
     * al mismo tiempo), devuelve esa misma respuesta. [force] = deslizar para recargar.
     */
    suspend fun refresh(force: Boolean = false, reuseWindowMs: Long = REUSE_WINDOW_MS): Result<List<Course>> =
        mutex.withLock {
            if (!force && clock() - lastFetchAt < reuseWindowMs) return@withLock Result.success(cached())
            resultOf { withContext(Dispatchers.IO) { api.getAllCourses() } }
                .onSuccess { courses ->
                    cache.saveCachedCourses(courses)
                    cache.setSessionExpired(false)
                    lastFetchAt = clock()
                }
                .onFailure { if (it.isSessionExpired()) cache.setSessionExpired(true) }
        }

    /** Archiva o restaura un curso en Eminus y en la copia local. */
    suspend fun setArchived(courseId: Long, archived: Boolean): Result<Unit> =
        resultOf { withContext(Dispatchers.IO) { api.setArchived(courseId, archived) } }.onSuccess {
            cache.saveCachedCourses(cached().map {
                if (it.idCurso == courseId) it.copy(archivado = if (archived) 1 else 0) else it
            })
        }

    private companion object {
        const val REUSE_WINDOW_MS = 10_000L
    }
}
