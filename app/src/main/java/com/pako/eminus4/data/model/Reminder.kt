package com.pako.eminus4.data.model

import com.google.gson.annotations.SerializedName

/*
 * Recordatorios guardados en el teléfono (SharedPreferences "recordatorios", con Gson).
 * Los @SerializedName conservan los nombres de antes del refactor: así se siguen leyendo
 * los recordatorios que ya tenían guardados los usuarios.
 */

/** `keyPrefix` forma parte de la llave de cada alarma: no cambiarlo o no se podrían cancelar las ya programadas. */
enum class ReminderType(val keyPrefix: String) {
    @SerializedName("TAREA") TASK("TAREA"),
    @SerializedName("EXAMEN") EXAM("EXAMEN"),
    @SerializedName("FORO") FORUM("FORO")
}

/** Algo con fecha que puede generar recordatorios (una tarea pendiente, un examen, un foro abierto). */
data class ReminderSource(
    @SerializedName("tipo") val type: ReminderType,
    @SerializedName("idCurso") val courseId: Long,
    @SerializedName("idRef") val refId: Long,
    @SerializedName("nombre") val name: String,
    @SerializedName("curso") val course: String,
    @SerializedName("inicio") val start: Long?,
    @SerializedName("cierre") val end: Long?,
    /** Valor de la actividad/examen (para el detalle del aviso). */
    @SerializedName("puntos") val points: Double? = null,
    @SerializedName("enEquipo") val inTeam: Boolean = false
) {
    val key: String get() = "${type.keyPrefix}:$courseId:$refId"
}

/** Alarma concreta: fuente × momento ("antes_60", "vencida", "abre"). Se guarda para cancelarla luego. */
data class ScheduledAlarm(
    @SerializedName("codigo") val code: Int,
    @SerializedName("clave") val key: String,
    @SerializedName("cuando") val at: Long,
    @SerializedName("momento") val moment: String,
    @SerializedName("minutosAntes") val minutesBefore: Int
)
