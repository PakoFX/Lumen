package com.pako.eminus4.data.model

import com.google.gson.annotations.SerializedName

data class ForumItem(
    @SerializedName("idForo") val idForo: Long,
    @SerializedName("titulo") val titulo: String,
    @SerializedName("valor") val valor: Double? = 0.0,
    @SerializedName("descripcion") val descripcion: String? = null,
    @SerializedName("totalComentarios") val totalComentarios: Int = 0,
    @SerializedName("nuevosComentarios") val nuevosComentarios: Int = 0,
    @SerializedName("estado") val estado: Int = 1,
    @SerializedName("calificacion") val calificacion: Double? = null,
    @SerializedName("fechaInicio") val fechaInicio: String? = null,
    @SerializedName("fechaTermino") val fechaTermino: String? = null,
    @SerializedName("fechaProrroga") val fechaProrroga: String? = null,
    var idCurso: Long = 0L,
    var nombreCurso: String = "",
    /** Si el curso sigue vigente (lo pone quien carga los foros). */
    var cursoActivo: Boolean = true
) {
    /**
     * Abierto = curso vigente, estado 1 y sin pasar su cierre (prórroga o término).
     * Si Eminus no manda fechas, se decide solo con el curso y el estado.
     */
    fun estaAbierto(ahora: Long = System.currentTimeMillis()): Boolean {
        if (!cursoActivo || estado != 1) return false
        val cierre = com.pako.eminus4.util.Plazos.parseIso(fechaProrroga)
            ?: com.pako.eminus4.util.Plazos.parseIso(fechaTermino) ?: return true
        return ahora <= cierre
    }
}

