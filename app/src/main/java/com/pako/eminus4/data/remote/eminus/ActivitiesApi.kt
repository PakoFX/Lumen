package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.core.AppError
import com.pako.eminus4.core.Constants
import com.pako.eminus4.data.model.Activity
import com.pako.eminus4.data.model.ActivityDetail
import com.pako.eminus4.data.model.StudentDelivery
import org.json.JSONArray
import org.json.JSONObject

/**
 * Actividades (tareas) y su entrega. El flujo de entrega es el de Eminus web
 * (bundle eminus4 v1.0.37): borrador → archivo → confirmar; lo orquesta el repositorio.
 */
class ActivitiesApi(private val http: EminusHttp, private val json: JsonMapper) {

    suspend fun getActivities(courseId: Long): List<Activity> =
        json.list(http.get("${Constants.ENDPOINT_ACTIVITIES}/$courseId", "getActividadesEstudiante"))

    suspend fun getDetail(courseId: Long, activityId: Long): ActivityDetail =
        json.obj<ActivityDetail>(http.get("Actividad/getActividadEstudiante/$courseId/$activityId", "getActividadEstudiante"))
            ?: throw AppError.MissingData("Eminus no devolvió la actividad")

    /** GET eminusapi8 Activity/getActUsuarioRevision: lo entregado, la revisión y comentarios. */
    suspend fun getStudentDelivery(courseId: Long, activityId: Long, userId: String): StudentDelivery =
        json.obj<StudentDelivery>(
            http.getUrl("${Constants.API_BASE_URL}Activity/getActUsuarioRevision/$courseId/$activityId/$userId", "getActUsuarioRevision")
        ) ?: throw AppError.MissingData("Eminus no devolvió la entrega")

    /** Paso 1: crea el borrador de entrega; devuelve su idActividadEntrega. */
    suspend fun createDraft(courseId: Long, activityId: Long, nowIsoUtc: String): Long {
        val body = http.postJson(
            "Actividad/InsertaActividadEntrega",
            deliveryPayload(courseId, activityId, deliveryId = 0, html = "", visible = 0, hasFiles = 0, dateIso = nowIsoUtc),
            "InsertaActividadEntrega (borrador)"
        )
        return parseOutputId(body).takeIf { it > 0 } ?: throw AppError.MissingData("Eminus no devolvió el número de entrega")
    }

    /** Paso 2: sube el archivo (multipart) a la entrega. */
    suspend fun uploadFile(courseId: Long, activityId: Long, deliveryId: Long, file: EminusHttp.UploadFile) {
        http.upload(
            "actividad/adjuntarEntrega?idCurso=$courseId&idActividad=$activityId&idActividadEntregas=$deliveryId",
            listOf(file), "adjuntarEntrega"
        )
    }

    /** Paso 3: confirma la entrega para que el docente la vea. */
    suspend fun submit(courseId: Long, activityId: Long, deliveryId: Long, html: String) {
        http.postJson(
            "Actividad/InsertaActividadEntrega",
            deliveryPayload(courseId, activityId, deliveryId, html, visible = 1, hasFiles = 1, dateIso = null),
            "InsertaActividadEntrega (envío)"
        )
    }

    /** PUT actividad/eliminaArchivoEntrega { idArchivosEntregas, idcurso } */
    suspend fun deleteFile(courseId: Long, deliveryFileId: Long) {
        http.putJson(
            "actividad/eliminaArchivoEntrega",
            JSONObject().put("idArchivosEntregas", deliveryFileId).put("idcurso", courseId),
            "eliminaArchivoEntrega"
        )
    }

    /** Cuerpo que Eminus web envía a InsertaActividadEntrega (clase `co` del bundle). */
    private fun deliveryPayload(
        courseId: Long, activityId: Long, deliveryId: Long, html: String, visible: Int, hasFiles: Int, dateIso: String?
    ) = JSONObject()
        .put("idActividadEntrega", deliveryId)
        .put("texto", html)
        .put("idActividad", activityId)
        .put("idCurso", courseId)
        .put("visible", visible)
        .put("tieneAdjuntos", hasFiles)
        .put("idEquipo", 0)
        .put("fechaEntrega", dateIso ?: JSONObject.NULL)

    companion object {
        /** Eminus responde `{ "salida": 123 }` (a veces como texto o dentro de un arreglo). */
        fun parseOutputId(body: String): Long {
            val trimmed = body.trim()
            trimmed.toLongOrNull()?.let { return it }
            return try {
                val obj = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
                obj.optString("salida", "0").trim().toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
