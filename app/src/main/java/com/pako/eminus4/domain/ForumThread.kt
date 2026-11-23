package com.pako.eminus4.domain

import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.model.ForumDetail

/** Reglas de un foro y operaciones sobre su lista de comentarios (raíz + respuestas). Puras. */
object ForumThread {

    /** Cierre efectivo: la prórroga si existe, si no el término. */
    fun closesAt(detail: ForumDetail?): Long? =
        DueDates.parseEminusDate(detail?.fechaProrroga) ?: DueDates.parseEminusDate(detail?.fechaTermino)

    /** Abierto = estado 1 y sin pasar su cierre. Sin detalle aún, se asume abierto. */
    fun isOpen(detail: ForumDetail?, now: Long = System.currentTimeMillis()): Boolean {
        if (detail == null) return true
        if (detail.estado != 1) return false
        return now <= (closesAt(detail) ?: return true)
    }

    /** "Cierra mañana, 23:59" / "Cerró el 3 nov" (mismo texto que el vencimiento de tareas). */
    fun closingText(detail: ForumDetail?, now: Long = System.currentTimeMillis()): String {
        val close = closesAt(detail) ?: return ""
        return DueDates.dueDate(close, now).text.replace("Vence", "Cierra").replace("Venció", "Cerró")
    }

    /** Agrega comentarios sin repetir los que ya estaban. */
    fun appendNew(current: List<ForumComment>, more: List<ForumComment>): List<ForumComment> {
        val ids = current.map { it.idComentarioForo }.toSet()
        return current + more.filter { it.idComentarioForo !in ids }
    }

    /** Reemplaza un comentario (raíz o respuesta) por su versión nueva, conservando las respuestas de la raíz. */
    fun replace(comments: List<ForumComment>, updated: ForumComment): List<ForumComment> = comments.map { root ->
        when {
            root.idComentarioForo == updated.idComentarioForo -> updated.copy(respuestas = root.respuestas)
            root.respuestas.orEmpty().any { it.idComentarioForo == updated.idComentarioForo } ->
                root.copy(respuestas = root.respuestas.orEmpty().map { if (it.idComentarioForo == updated.idComentarioForo) updated else it })
            else -> root
        }
    }

    /** Une todas las respuestas de un comentario raíz ("ver más respuestas"). */
    fun withAllAnswers(comments: List<ForumComment>, rootId: Long, answers: List<ForumComment>): List<ForumComment> =
        comments.map { root ->
            if (root.idComentarioForo != rootId) return@map root
            val merged = appendNew(root.respuestas.orEmpty(), answers)
            root.copy(respuestas = merged, totalRespuestas = maxOf(root.totalRespuestas, merged.size))
        }

    /** Da o quita el me gusta (lo que se ve al instante; Eminus lo confirma después). */
    fun toggledLike(comment: ForumComment): ForumComment {
        val liked = !comment.meGusta
        return comment.copy(tieneLike = if (liked) "Si" else "No", likes = (comment.likes + if (liked) 1 else -1).coerceAtLeast(0))
    }

    fun findById(comments: List<ForumComment>, id: Long): ForumComment? =
        comments.asSequence().flatMap { sequenceOf(it) + it.respuestas.orEmpty() }.firstOrNull { it.idComentarioForo == id }

    /** Texto plano → HTML como lo guarda el editor de Eminus (párrafo con saltos de línea). */
    fun toHtml(text: String): String =
        "<p>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</p>"

    /**
     * Cuando Eminus no devuelve el id del comentario recién publicado: el más nuevo propio cuyo texto
     * empieza igual. [plainText] convierte el HTML a texto (en Android, HtmlCompat).
     */
    fun findOwnComment(
        comments: List<ForumComment>, myStudentId: String?, text: String, plainText: (String) -> String
    ): Long? {
        val start = text.take(30)
        return comments.flatMap { listOf(it) + it.respuestas.orEmpty() }
            .filter { StudentId.normalize(it.idUsuario) == myStudentId && plainText(it.descripcion.orEmpty()).contains(start) }
            .maxByOrNull { it.idComentarioForo }?.idComentarioForo
    }
}
