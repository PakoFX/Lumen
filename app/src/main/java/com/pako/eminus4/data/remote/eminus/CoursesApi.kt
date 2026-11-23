package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.core.Constants
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.CourseResponse
import org.json.JSONObject

/** Cursos del alumno (API v8 para la lista, API global para archivar). */
class CoursesApi(
    private val http: EminusHttp,
    private val json: JsonMapper,
    private val allCoursesUrl: String = Constants.API_BASE_URL + Constants.ENDPOINT_ALL_COURSES
) {

    /** GET eminusapi8 Course/getAllCourses → { contenido: [ { curso } ] } */
    suspend fun getAllCourses(): List<Course> {
        val body = http.getUrl(allCoursesUrl, "getAllCourses")
        return json.obj<CourseResponse>(body)?.contenido?.map { it.curso }.orEmpty()
    }

    /** PUT Cursos/ActualizaArchivado { idCurso, archivado: 1|0 }: solo cambia el apartado donde aparece. */
    suspend fun setArchived(courseId: Long, archived: Boolean) {
        http.putJson(
            "Cursos/ActualizaArchivado",
            JSONObject().put("idCurso", courseId).put("archivado", if (archived) 1 else 0),
            "ActualizaArchivado"
        )
    }
}
