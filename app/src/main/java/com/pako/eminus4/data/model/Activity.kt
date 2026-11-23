package com.pako.eminus4.data.model

import androidx.annotation.DrawableRes
import com.google.gson.annotations.SerializedName
import com.pako.eminus4.R
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.domain.StudentStatus

/**
 * Representa una actividad/tarea dentro de un curso de Eminus.
 * Mapeado desde GET /eminusapi/api/Actividad/getActividadesEstudiante/{idCurso}
 *
 * IMPORTANTE: `estado` es Int? porque Eminus distingue null (nunca tocada) de 0 (borrador).
 * Gson crea esta clase sin constructor, así que un Int no-nulo convertiría ambos en 0.
 */
data class Activity(
    @SerializedName("idActividad")       val idActividad: Long,
    @SerializedName("titulo")            val titulo: String? = null,
    @SerializedName("nombre")            val nombreRaw: String? = null,
    @SerializedName("descripcion")       val descripcion: String? = null,
    @SerializedName("tipo")              val tipo: String? = null,
    @SerializedName("fechaEntrega")      val fechaEntrega: String? = null,
    @SerializedName("fechaTermino")      val fechaTermino: String? = null,
    @SerializedName("fechaInicio")       val fechaInicio: String? = null,
    @SerializedName("fechaRevision")     val fechaRevision: String? = null,
    @SerializedName("calificacion")      val calificacion: Double? = null,
    @SerializedName("valor")             val valor: Double? = null,
    @SerializedName("calificacionMax")   val calificacionMaxRaw: Double? = null,
    @SerializedName("estado")            val estado: Int? = null,
    @SerializedName("estadoEntrega")     val estadoEntrega: Int? = null,
    @SerializedName("estadoAct")         val estadoAct: Int? = null,
    @SerializedName("porEquipos")        val porEquipos: Int? = null,
    @SerializedName("idCurso")           val idCurso: Long = 0,
    @SerializedName("orden")             val orden: Int = 0
) {
    val nombre: String
        get() = titulo ?: nombreRaw ?: "Actividad"

    val calificacionMax: Double
        get() = valor?.takeIf { it > 0 } ?: calificacionMaxRaw ?: 10.0

    val esPorEquipos: Boolean
        get() = porEquipos == 1

    val terminoMillis: Long?
        get() = DueDates.parseEminusDate(fechaTermino)

    /** Abierta según Eminus (estadoAct); si no viene, se decide por la fecha límite. */
    val estaAbierta: Boolean
        get() = estadoAct?.let { it == 2 || it == 4 }
            ?: (terminoMillis?.let { it > System.currentTimeMillis() } ?: true)

    /** Situación real para el alumno. Ver [StudentStatus]. */
    val studentStatus: StudentStatus
        get() = when {
            calificacion != null || estado == 3 || estado == 4 -> StudentStatus.GRADED
            estado == 1 || estado == 2                         -> StudentStatus.AWAITING_GRADE
            estaAbierta -> if (estado == 0) StudentStatus.DRAFT else StudentStatus.TO_DO
            else                                               -> StudentStatus.NOT_SUBMITTED
        }

    val entregada: Boolean
        get() = studentStatus == StudentStatus.AWAITING_GRADE || studentStatus == StudentStatus.GRADED

    val estaEntregada: Boolean
        get() = entregada

    val estaCalificada: Boolean
        get() = studentStatus == StudentStatus.GRADED

    val fechaLimite: String?
        get() = fechaTermino ?: fechaEntrega

    /** Ícono representativo según el tipo de actividad */
    @DrawableRes
    fun getIconoTipo(): Int = when (tipo?.lowercase()) {
        "foro"     -> R.drawable.ic_forum
        "examen"   -> R.drawable.ic_quiz
        "recurso"  -> R.drawable.ic_description
        "encuesta" -> R.drawable.ic_poll
        else       -> R.drawable.ic_assignment
    }

    /** Calcula el porcentaje de calificación para una barra de progreso */
    fun getPorcentajeCalificacion(): Int {
        val max = calificacionMax
        val cal = calificacion ?: return -1
        return ((cal / max) * 100).toInt().coerceIn(0, 100)
    }
}
