package com.pako.eminus4.data.repository

import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.model.ActivityDetail
import com.pako.eminus4.data.model.StudentDelivery
import com.pako.eminus4.data.remote.eminus.ActivitiesApi
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.domain.DeliveryRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Una actividad y la entrega del alumno. Enviar sigue el mismo flujo que Eminus web:
 * verificar que siga abierta → usar la entrega existente o crear borrador → subir archivo → confirmar.
 */
class DeliveryRepository(private val api: ActivitiesApi, private val clock: () -> Long = System::currentTimeMillis) {

    /** Paso en curso, para mostrarlo en pantalla. */
    enum class Step { CHECKING, CREATING_DRAFT, UPLOADING, CONFIRMING, REMOVING }

    sealed interface Outcome {
        data object Done : Outcome
        /** La actividad ya no admite cambios (cerró, calificada, por equipos). */
        data class Blocked(val reason: String, val detail: ActivityDetail) : Outcome
        data class Failed(val error: Throwable, val step: Step) : Outcome
    }

    suspend fun getDetail(courseId: Long, activityId: Long): Result<ActivityDetail> =
        resultOf { api.getDetail(courseId, activityId) }

    suspend fun getDelivery(courseId: Long, activityId: Long, userId: String): Result<StudentDelivery> =
        resultOf { api.getStudentDelivery(courseId, activityId, userId) }

    /** `existingDeliveryId` > 0 = ya hay entrega; se modifica en vez de crear otra. */
    suspend fun submit(
        courseId: Long,
        activityId: Long,
        existingDeliveryId: Long,
        file: EminusHttp.UploadFile,
        html: String,
        onStep: (Step) -> Unit
    ): Outcome {
        checkOpen(courseId, activityId, onStep)?.let { return it }

        val deliveryId = if (existingDeliveryId > 0) existingDeliveryId else {
            onStep(Step.CREATING_DRAFT)
            resultOf { api.createDraft(courseId, activityId, nowIsoUtc()) }
                .getOrElse { return Outcome.Failed(it, Step.CREATING_DRAFT) }
        }
        onStep(Step.UPLOADING)
        resultOf { api.uploadFile(courseId, activityId, deliveryId, file) }
            .onFailure { return Outcome.Failed(it, Step.UPLOADING) }
        onStep(Step.CONFIRMING)
        resultOf { api.submit(courseId, activityId, deliveryId, html) }
            .onFailure { return Outcome.Failed(it, Step.CONFIRMING) }
        return Outcome.Done
    }

    /** Quita un archivo de la entrega (también confirma antes que la actividad siga abierta). */
    suspend fun removeFile(courseId: Long, activityId: Long, deliveryFileId: Long, onStep: (Step) -> Unit): Outcome {
        checkOpen(courseId, activityId, onStep)?.let { return it }
        onStep(Step.REMOVING)
        return resultOf { api.deleteFile(courseId, deliveryFileId) }
            .fold({ Outcome.Done }, { Outcome.Failed(it, Step.REMOVING) })
    }

    /** Igual que Eminus web: antes de cambiar algo se vuelve a consultar el estado real. null = abierta. */
    private suspend fun checkOpen(courseId: Long, activityId: Long, onStep: (Step) -> Unit): Outcome? {
        onStep(Step.CHECKING)
        val detail = resultOf { api.getDetail(courseId, activityId) }
            .getOrElse { return Outcome.Failed(it, Step.CHECKING) }
        return DeliveryRules.blockReason(detail)?.let { Outcome.Blocked(it, detail) }
    }

    private fun nowIsoUtc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(clock()))
}
