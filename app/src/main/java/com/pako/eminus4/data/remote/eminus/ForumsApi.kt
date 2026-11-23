package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.model.ForumDetail
import com.pako.eminus4.data.model.ForumItem
import org.json.JSONObject

/** Foros: leer, comentar, responder, dar me gusta y adjuntar (mismos endpoints que Eminus web). */
class ForumsApi(private val http: EminusHttp, private val json: JsonMapper) {

    suspend fun getForums(courseId: Long): List<ForumItem> =
        json.list(http.get("Foro/getForos/$courseId/0", "getForos"))

    suspend fun getDetail(courseId: Long, forumId: Long): ForumDetail =
        json.obj<ForumDetail>(http.get("Foro/getForo/$courseId/$forumId", "getForo")) ?: ForumDetail()

    /** Primera página de comentarios raíz (con algunas respuestas cada uno). */
    suspend fun getComments(courseId: Long, forumId: Long): List<ForumComment> =
        json.list(http.get("Foro/getComentariosForo/$courseId/$forumId", "getComentariosForo"))

    /** Resto de comentarios raíz ("ver más comentarios"). */
    suspend fun getMoreComments(courseId: Long, forumId: Long): List<ForumComment> =
        json.list(http.get("Foro/getMasComentarios/$courseId/$forumId", "getMasComentarios"))

    /** Todas las respuestas de un comentario ("ver más respuestas"). */
    suspend fun getMoreAnswers(courseId: Long, forumId: Long, commentId: Long): List<ForumComment> =
        json.list(http.get("Foro/getMasRespuestas/$courseId/$forumId/$commentId", "getMasRespuestas"))

    /**
     * Publica un comentario (parentId = 0) o una respuesta. POST Foro/InsertaComentarioForo.
     * Eminus responde con el/los comentarios guardados: el id más alto es el nuevo.
     */
    suspend fun postComment(courseId: Long, forumId: Long, html: String, parentId: Long): Long? {
        val body = http.postJson(
            "Foro/InsertaComentarioForo",
            JSONObject().put("IdComentarioForo", 0).put("Descripcion", html)
                .put("IdPadre", parentId).put("IdCurso", courseId).put("IdForo", forumId),
            "InsertaComentarioForo"
        )
        return runCatching {
            json.objects(body).mapNotNull { it.get("idComentarioForo")?.takeIf { v -> v.isJsonPrimitive }?.asLong }.maxOrNull()
        }.getOrNull()
    }

    /** Da o quita "me gusta" (Eminus lo alterna). */
    suspend fun toggleLike(courseId: Long, commentId: Long) {
        http.putJson(
            "Foro/insertaComentarioLike",
            JSONObject().put("IdComentarioForo", commentId).put("IdCurso", courseId),
            "insertaComentarioLike"
        )
    }

    /** Adjuntos de un comentario ya publicado. */
    suspend fun uploadCommentFiles(courseId: Long, forumId: Long, commentId: Long, files: List<EminusHttp.UploadFile>) {
        if (files.isEmpty()) return
        http.upload("Foro/adjuntarComentarioForo?idCurso=$courseId&idForo=$forumId&idComentario=$commentId", files, "adjuntarComentarioForo")
    }
}
