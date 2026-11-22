package com.pako.eminus4.data.model

import com.google.gson.annotations.SerializedName

/**
 * Comentario (o respuesta) de un foro de Eminus.
 * GET Foro/getComentariosForo/{idCurso}/{idForo} → lista de comentarios raíz con sus `respuestas`.
 */
data class ForumComment(
    @SerializedName("idComentarioForo") val idComentarioForo: Long = 0,
    @SerializedName("idPadre") val idPadre: Long? = 0,
    @SerializedName("idUsuario") val idUsuario: String? = null,
    @SerializedName("nombreCompleto") val nombreCompleto: String? = null,
    /** Foto del autor: ruta relativa al repositorio ("profile/…") o URL completa. */
    @SerializedName("ruta") val ruta: String? = null,
    /** Texto en HTML (editor de Eminus). */
    @SerializedName("descripcion") val descripcion: String? = null,
    @SerializedName("fechaPublicacion") val fechaPublicacion: String? = null,
    @SerializedName("likes") val likes: Int = 0,
    /** "Si" / "No" */
    @SerializedName("tieneLike") val tieneLike: String? = null,
    @SerializedName("totalRespuestas") val totalRespuestas: Int = 0,
    @SerializedName("respuestas") val respuestas: List<ForumComment>? = null,
    @SerializedName("archivoForoConTamano") val archivos: List<ForumCommentFile>? = null
) {
    val meGusta: Boolean get() = tieneLike.equals("Si", ignoreCase = true)

    fun getAvatarUrl(): String? {
        val r = ruta?.takeIf { it.isNotBlank() } ?: return null
        if (r.startsWith("http")) return r
        return "https://eminus.uv.mx/eminusapi/" + r.replace("\\", "/").trimStart('/')
    }
}

/** Archivo adjunto de un comentario o del foro (Eminus lo llama `idArchivosForos`). */
data class ForumCommentFile(
    @SerializedName("idArchivosForos") val idArchivosForos: Long = 0,
    @SerializedName("nombreOriginal") val nombreOriginal: String? = null,
    @SerializedName("tamano") val tamano: Long = 0
) {
    /** GET Foro/descargaComentarioArchivo/{idComentario}/{idArchivo} (igual que Eminus web). */
    fun urlDescargaComentario(idComentario: Long) =
        "${com.pako.eminus4.core.Constants.API_GLOBAL_URL}Foro/descargaComentarioArchivo/$idComentario/$idArchivosForos"

    /** GET Foro/descargaForoArchivo/{idCurso}/{idForo}/{idArchivo}: archivos del foro mismo. */
    fun urlDescargaForo(idCurso: Long, idForo: Long) =
        "${com.pako.eminus4.core.Constants.API_GLOBAL_URL}Foro/descargaForoArchivo/$idCurso/$idForo/$idArchivosForos"

    fun tamanoLegible(): String = com.pako.eminus4.util.Adjuntos.tamanoLegible(tamano)
}

/** GET Foro/getForo/{idCurso}/{idForo}: datos del foro (instrucciones, fechas, estado). */
data class ForumDetail(
    @SerializedName("idForo") val idForo: Long = 0,
    @SerializedName("titulo") val titulo: String? = null,
    @SerializedName("descripcion") val descripcion: String? = null,
    @SerializedName("estado") val estado: Int = 1,
    @SerializedName("fechaInicio") val fechaInicio: String? = null,
    @SerializedName("fechaTermino") val fechaTermino: String? = null,
    @SerializedName("fechaProrroga") val fechaProrroga: String? = null,
    @SerializedName("totalComentariosRaiz") val totalComentariosRaiz: Int = 0,
    /** Archivos que el profesor adjuntó al foro. */
    @SerializedName("listaArchivos") val archivos: List<ForumCommentFile>? = null
)
