package com.pako.eminus4.data.repository

import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.model.ForumDetail
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.remote.eminus.ForumsApi
import com.pako.eminus4.domain.ForumThread
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Foros: la lista por materia y, dentro de un foro, leer, dar me gusta y publicar con adjuntos. */
class ForumRepository(private val api: ForumsApi, private val courses: CourseRepository) {

    data class Loaded(val forums: List<ForumItem>, val coursesById: Map<Long, Course>)

    /** Cómo terminó una publicación: el comentario siempre queda publicado; los archivos pueden fallar. */
    sealed interface PublishOutcome {
        data object Published : PublishOutcome
        data class FilesFailed(val reason: String?) : PublishOutcome
    }

    /** En paralelo por materia; si una falla se omite (mejor mostrar las demás que nada). */
    suspend fun loadAll(force: Boolean = false): Result<Loaded> = courses.refresh(force).mapCatching { all ->
        val withForums = all.filter { it.isActivo() || it.foros > 0 }
        val forums = coroutineScope {
            withForums.map { course ->
                async {
                    resultOf { api.getForums(course.idCurso) }.getOrDefault(emptyList()).onEach {
                        it.idCurso = course.idCurso
                        it.nombreCurso = course.getNombreLimpio()
                        it.cursoActivo = course.isActivo()
                    }
                }
            }.awaitAll().flatten()
        }
        Loaded(forums, all.associateBy { it.idCurso })
    }

    suspend fun getDetail(courseId: Long, forumId: Long): Result<ForumDetail> = resultOf { api.getDetail(courseId, forumId) }

    suspend fun getComments(courseId: Long, forumId: Long): Result<List<ForumComment>> =
        resultOf { api.getComments(courseId, forumId) }

    suspend fun getMoreComments(courseId: Long, forumId: Long): Result<List<ForumComment>> =
        resultOf { api.getMoreComments(courseId, forumId) }

    suspend fun getAllAnswers(courseId: Long, forumId: Long, commentId: Long): Result<List<ForumComment>> =
        resultOf { api.getMoreAnswers(courseId, forumId, commentId) }

    suspend fun toggleLike(courseId: Long, commentId: Long): Result<Unit> = resultOf { api.toggleLike(courseId, commentId) }

    /**
     * Publica un comentario (parentId = 0) o respuesta y, como Eminus web, después le sube los archivos.
     * Si Eminus no devuelve el id del comentario nuevo, se busca entre los comentarios propios.
     * @param plainText convierte el HTML de un comentario a texto (para reconocer el propio).
     */
    suspend fun publish(
        courseId: Long, forumId: Long, text: String, parentId: Long, files: List<EminusHttp.UploadFile>,
        myStudentId: String?, plainText: (String) -> String
    ): Result<PublishOutcome> = resultOf {
        val returnedId = api.postComment(courseId, forumId, ForumThread.toHtml(text), parentId)
        if (files.isEmpty()) return@resultOf PublishOutcome.Published
        val commentId = returnedId?.takeIf { it > 0 } ?: resultOf { api.getComments(courseId, forumId) }.getOrNull()
            ?.let { ForumThread.findOwnComment(it, myStudentId, text, plainText) }
            ?: return@resultOf PublishOutcome.FilesFailed(null)
        resultOf { api.uploadCommentFiles(courseId, forumId, commentId, files) }.fold(
            onSuccess = { PublishOutcome.Published },
            onFailure = { PublishOutcome.FilesFailed(it.message) }
        )
    }
}
