package com.pako.eminus4.ui.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.model.ContentElement
import com.pako.eminus4.data.repository.CourseSectionsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ElementUiState(
    val element: ContentElement,
    val read: Boolean = element.leido,
    val liked: Boolean = element.usuarioLike == 1,
    val likes: Int = element.likes,
    val markingRead: Boolean = false,
    val sendingComment: Boolean = false
)

sealed interface ElementEvent {
    data class Message(val text: String, val long: Boolean = false) : ElementEvent
    /** Comentario publicado: limpiar la caja de texto. */
    data object CommentPosted : ElementEvent
    /** Algo cambió en Eminus (leído, me gusta, comentario): la lista de contenido debe refrescarse. */
    data object Changed : ElementEvent
}

/** Un elemento del Contenido: "Marcar como leído", me gusta y comentarios (como Eminus web). */
class ElementViewModel(
    private val repository: CourseSectionsRepository,
    private val courseId: Long,
    element: ContentElement
) : ViewModel() {

    private val _state = MutableStateFlow(ElementUiState(element))
    val state: StateFlow<ElementUiState> = _state.asStateFlow()

    private val _events = Channel<ElementEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val elementId get() = _state.value.element.idElemento

    fun markRead() {
        if (_state.value.read || _state.value.markingRead) return
        _state.update { it.copy(markingRead = true) }
        viewModelScope.launch {
            repository.markRead(courseId, elementId).fold(
                onSuccess = {
                    _state.update { it.copy(read = true, markingRead = false) }
                    _events.send(ElementEvent.Changed)
                    _events.send(ElementEvent.Message("Marcado como leído"))
                },
                onFailure = {
                    _state.update { s -> s.copy(markingRead = false) }
                    _events.send(ElementEvent.Message("No se pudo marcar: ${it.message}", long = true))
                }
            )
        }
    }

    /** Optimista: se ve al instante y se revierte si Eminus falla. */
    fun toggleLike() {
        val before = _state.value
        _state.update { it.copy(liked = !it.liked, likes = (it.likes + if (it.liked) -1 else 1).coerceAtLeast(0)) }
        viewModelScope.launch {
            repository.toggleLike(courseId, elementId).fold(
                onSuccess = { _events.send(ElementEvent.Changed) },
                onFailure = {
                    _state.update { s -> s.copy(liked = before.liked, likes = before.likes) }
                    _events.send(ElementEvent.Message("No se pudo registrar el me gusta"))
                }
            )
        }
    }

    /** Publica el comentario y vuelve a pedir el elemento para mostrarlo. */
    fun comment(text: String) {
        if (text.isBlank() || _state.value.sendingComment) return
        _state.update { it.copy(sendingComment = true) }
        viewModelScope.launch {
            val result = repository.comment(courseId, elementId, text)
            _state.update { it.copy(sendingComment = false) }
            result.fold(
                onSuccess = {
                    _events.send(ElementEvent.CommentPosted)
                    _events.send(ElementEvent.Changed)
                    _events.send(ElementEvent.Message("Comentario publicado"))
                    val current = _state.value.element
                    repository.getElement(courseId, current.idUnidad, current.idElemento).getOrNull()
                        ?.let { updated -> _state.update { it.copy(element = updated) } }
                },
                onFailure = { _events.send(ElementEvent.Message("No se pudo publicar: ${it.message}", long = true)) }
            )
        }
    }
}
