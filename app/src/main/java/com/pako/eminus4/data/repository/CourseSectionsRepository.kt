package com.pako.eminus4.data.repository

import com.pako.eminus4.core.resultOf
import com.pako.eminus4.domain.HtmlText
import com.pako.eminus4.data.model.Activity
import com.pako.eminus4.data.model.ContentElement
import com.pako.eminus4.data.model.ContentUnit
import com.pako.eminus4.data.model.CourseMember
import com.pako.eminus4.data.model.TrackingDetail
import com.pako.eminus4.data.remote.eminus.ActivitiesApi
import com.pako.eminus4.data.remote.eminus.CourseSectionsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Lo que hay dentro de una materia: actividades, contenido, miembros y seguimiento. */
class CourseSectionsRepository(private val sections: CourseSectionsApi, private val activities: ActivitiesApi) {

    data class UnitContent(val unit: ContentUnit, val elements: List<ContentElement>)

    data class Tracking(val detail: TrackingDetail?, val lastAccess: String?)

    suspend fun getActivities(courseId: Long): Result<List<Activity>> = resultOf { activities.getActivities(courseId) }

    /**
     * Unidades con sus elementos (en paralelo). Muchos profes publican directo en la materia, sin unidad:
     * esos elementos solo llegan con la unidad 0, aunque la materia también tenga unidades. Van primero, como "General".
     * Si los elementos de una unidad fallan, esa unidad sale vacía (como antes).
     */
    suspend fun getContent(courseId: Long): Result<List<UnitContent>> = resultOf {
        val units = listOf(ContentUnit(idUnidad = 0, nombre = GENERAL_UNIT)) + sections.getUnits(courseId)
        coroutineScope {
            units.map { unit ->
                async { UnitContent(unit, resultOf { sections.getElements(courseId, unit.idUnidad) }.getOrDefault(emptyList())) }
            }.awaitAll()
        }
    }

    suspend fun getMembers(courseId: Long): Result<List<CourseMember>> = resultOf { sections.getMembers(courseId) }

    /** Seguimiento y último acceso en paralelo; solo falla si falla el seguimiento. */
    suspend fun getTracking(courseId: Long, userId: String): Result<Tracking> = resultOf {
        coroutineScope {
            val lastAccess = async { resultOf { sections.getLastAccess(courseId, userId) }.getOrNull() }
            val detail = sections.getTracking(courseId, userId)
            Tracking(detail, lastAccess.await())
        }
    }

    suspend fun markRead(courseId: Long, elementId: Long): Result<Unit> = resultOf { sections.markRead(courseId, elementId) }

    suspend fun toggleLike(courseId: Long, elementId: Long): Result<Unit> = resultOf { sections.toggleLike(courseId, elementId) }

    /** Comentario en texto plano (se escapa y los saltos de línea van como <br>, como Eminus web). */
    suspend fun comment(courseId: Long, elementId: Long, text: String): Result<Unit> = resultOf {
        sections.comment(courseId, elementId, HtmlText.escape(text.trim()).replace("\n", "<br>"))
    }

    /** Vuelve a pedir un elemento (para ver comentarios nuevos): se busca en los de su unidad. */
    suspend fun getElement(courseId: Long, unitId: Long, elementId: Long): Result<ContentElement?> =
        resultOf { sections.getElements(courseId, unitId).firstOrNull { it.idElemento == elementId } }


    companion object {
        const val GENERAL_UNIT = "General"
    }
}
