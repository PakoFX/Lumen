package com.pako.eminus4.ui.forums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.core.isSessionExpired
import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.model.ForumDetail
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.repository.ForumRepository
import com.pako.eminus4.data.repository.ForumRepository.PublishOutcome
import com.pako.eminus4.domain.ForumThread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ForumDetailUiState(
    val detail: ForumDetail? = null,
    val comments: List<ForumComment> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Comentario raíz al que se responde (null = comentario nuevo). */
    val replyingTo: ForumComment? = null,
    val sending: Boolean = false
) {
    val isOpen: Boolean get() = ForumThread.isOpen(detail)
    val totalComments: Int get() = maxOf(detail?.totalComentariosRaiz ?: 0, comments.size)
    val hasMoreComments: Boolean get() = totalComments > comments.size
}

sealed interface ForumDetailEvent {
    data class Message(val text: String, val long: Boolean = false) : ForumDetailEvent
    /** Publicado: limpiar la caja de texto (y subir al inicio si fue comentario nuevo). */
    data class Published(val wasReply: Boolean) : ForumDetailEvent
}

class ForumDetailViewModel(
    private val repository: ForumRepository,
    private val courseId: Long,
    private val forumId: Long,
    private val myStudentId: () -> String?,
    private val plainText: (String) -> String
) : ViewModel() {

    private val _state = MutableStateFlow(ForumDetailUiState())
    val state: StateFlow<ForumDetailUiState> = _state.asStateFlow()

    private val _events = Channel<ForumDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            launch {
                repository.getDetail(courseId, forumId)
                    .onSuccess { detail ->
                        _state.update { it.copy(detail = detail, replyingTo = it.replyingTo.takeIf { ForumThread.isOpen(detail) }) }
                    }
                    .onFailure { error("No se pudo cargar el foro", it) }
            }
            repository.getComments(courseId, forumId)
                .onSuccess { comments -> _state.update { it.copy(comments = comments) } }
                .onFailure { error("No se pudieron cargar los comentarios", it) }
            _state.update { it.copy(loading = false) }
        }
    }

    fun loadMoreComments() {
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            repository.getMoreComments(courseId, forumId)
                .onSuccess { more -> _state.update { it.copy(comments = ForumThread.appendNew(it.comments, more)) } }
                .onFailure { error("No se pudieron cargar más comentarios", it) }
            _state.update { it.copy(loadingMore = false) }
        }
    }

    fun loadAllAnswers(root: ForumComment) {
        viewModelScope.launch {
            repository.getAllAnswers(courseId, forumId, root.idComentarioForo)
                .onSuccess { answers -> _state.update { it.copy(comments = ForumThread.withAllAnswers(it.comments, root.idComentarioForo, answers)) } }
                .onFailure { error("No se pudieron cargar las respuestas", it) }
        }
    }

    /** Optimista: se ve al instante y se revierte si Eminus falla. */
    fun toggleLike(comment: ForumComment) {
        _state.update { it.copy(comments = ForumThread.replace(it.comments, ForumThread.toggledLike(comment))) }
        viewModelScope.launch {
            repository.toggleLike(courseId, comment.idComentarioForo).onFailure {
                _state.update { s -> s.copy(comments = ForumThread.replace(s.comments, comment)) }
                error("No se pudo registrar el me gusta", it)
            }
        }
    }

    fun replyTo(root: ForumComment?) = _state.update { it.copy(replyingTo = root) }

    /**
     * Publica el texto (con archivos ya copiados a la caché). [onFilesDone] borra esas copias
     * temporales cuando ya no hacen falta, salga bien o mal.
     */
    fun publish(text: String, files: List<EminusHttp.UploadFile>, onFilesDone: () -> Unit) {
        val state = _state.value
        if (state.sending || text.isBlank()) return
        if (!state.isOpen) {
            message("El foro ya está cerrado")
            onFilesDone()
            return
        }
        val reply = state.replyingTo
        _state.update { it.copy(sending = true) }
        if (files.isNotEmpty()) message("Subiendo archivos…")
        viewModelScope.launch {
            val result = repository.publish(
                courseId, forumId, text, reply?.idComentarioForo ?: 0L, files, myStudentId(), plainText
            )
            onFilesDone()
            _state.update { it.copy(sending = false) }
            result.fold(
                onSuccess = { outcome -> onPublished(outcome, wasReply = reply != null) },
                onFailure = { error("No se pudo publicar", it) }
            )
        }
    }

    private suspend fun onPublished(outcome: PublishOutcome, wasReply: Boolean) {
        _state.update { it.copy(replyingTo = null) }
        _events.send(ForumDetailEvent.Published(wasReply))
        when (outcome) {
            PublishOutcome.Published -> message(if (wasReply) "Respuesta publicada" else "Comentario publicado")
            is PublishOutcome.FilesFailed -> message(
                outcome.reason?.let { "El comentario se publicó, pero los archivos fallaron: $it" }
                    ?: "El comentario se publicó, pero no se pudieron adjuntar los archivos",
                long = true
            )
        }
        load()
    }

    private fun message(text: String, long: Boolean = false) {
        _events.trySend(ForumDetailEvent.Message(text, long))
    }

    private fun error(prefix: String, e: Throwable) =
        message("$prefix: ${if (e.isSessionExpired()) "tu sesión caducó, renuévala" else e.message}", long = true)
}
