package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.data.model.ContentElement
import com.pako.eminus4.data.model.ContentUnit
import com.pako.eminus4.data.model.CourseMember
import com.pako.eminus4.data.model.TrackingDetail
import com.pako.eminus4.data.model.TrackingGroups
import com.pako.eminus4.data.model.TrackingItem
import org.json.JSONObject

/** Pestañas dentro de una materia: Contenido, Miembros y Seguimiento. */
class CourseSectionsApi(private val http: EminusHttp, private val json: JsonMapper) {

    // ---------- Contenido ----------

    suspend fun getUnits(courseId: Long): List<ContentUnit> =
        json.list<ContentUnit>(http.get("Contenido/getUnidades/$courseId/0", "getUnidades"))
            .filter { it.visible == 1 }.sortedBy { it.posicion }

    /** unitId = 0: lo publicado directo en la materia, sin unidad. */
    suspend fun getElements(courseId: Long, unitId: Long): List<ContentElement> =
        json.list<ContentElement>(http.get("Contenido/getElementos/$courseId/$unitId", "getElementos"))
            .filter { it.visible == 1 }.sortedBy { it.posicion }

    suspend fun markRead(courseId: Long, elementId: Long) {
        http.putJson("Contenido/insertaLeido", JSONObject().put("idElemento", elementId).put("idcurso", courseId), "insertaLeido")
    }

    /** Eminus alterna el me gusta. */
    suspend fun toggleLike(courseId: Long, elementId: Long) {
        http.putJson("Contenido/insertaLike", JSONObject().put("idElemento", elementId).put("idcurso", courseId), "insertaLike")
    }

    /** Comentario en un elemento: mismo cuerpo que Eminus web (`prepareComment`). [html] ya escapado. */
    suspend fun comment(courseId: Long, elementId: Long, html: String) {
        http.postJson(
            "Contenido/InsertaComentariosElemento",
            JSONObject().put("Texto", html).put("IdElemento", elementId).put("IdCurso", courseId),
            "InsertaComentariosElemento"
        )
    }

    // ---------- Miembros ----------

    suspend fun getMembers(courseId: Long): List<CourseMember> =
        json.list(http.get("Usuario/getIntegrantes/$courseId", "getIntegrantes"))

    // ---------- Seguimiento ----------

    /** Eminus devuelve una lista; se juntan las entradas del usuario en una sola. */
    suspend fun getTracking(courseId: Long, userId: String): TrackingDetail? {
        val all = json.list<TrackingDetail>(http.get("Seguimiento/SegDetalle/$courseId/$userId", "SegDetalle"))
        val mine = all.filter { it.idUsuario.equals(userId, ignoreCase = true) }.ifEmpty { all }
        val base = mine.firstOrNull() ?: return null
        fun merge(pick: (TrackingGroups) -> List<TrackingItem>?) =
            mine.flatMap { it.detalle?.let(pick).orEmpty() }.distinctBy { it.id to it.titulo }
        return base.copy(
            detalle = TrackingGroups(
                actividades = merge { it.actividades }, foros = merge { it.foros }, examenes = merge { it.examenes }
            )
        )
    }

    suspend fun getLastAccess(courseId: Long, userId: String): String? {
        val root = json.tree(http.get("Seguimiento/SegUltimoAccesoCurso/$courseId/$userId", "SegUltimoAccesoCurso"))
        return root?.takeIf { it.isJsonObject }?.asJsonObject?.get("ultimoAcceso")?.takeIf { !it.isJsonNull }?.asString
    }
}
