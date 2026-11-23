package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.data.model.ExamItem

class ExamsApi(private val http: EminusHttp, private val json: JsonMapper) {

    /** GET Examen/getExamenesEst/{idCurso} (204 o "[]" = sin exámenes). */
    suspend fun getExams(courseId: Long): List<ExamItem> =
        json.list(http.get("Examen/getExamenesEst/$courseId", "getExamenesEst"))
}
