package com.pako.eminus4.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.data.repository.CourseRepository
import com.pako.eminus4.data.repository.MessageRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Bandeja: carpeta (Recibidos/Enviados), sus mensajes y la sección abierta (null = ver secciones). */
data class MessagesUiState(
    val folder: Folder = Folder.INBOX,
    val messages: List<MessageItem> = emptyList(),
    val openSection: String? = null,
    val loading: Boolean = false
) {
    enum class Folder(val id: Int) { INBOX(1), SENT(2) }

    val countsUnread: Boolean get() = folder == Folder.INBOX
}

class MessagesViewModel(
    private val messages: MessageRepository,
    private val courses: CourseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MessagesUiState())
    val state: StateFlow<MessagesUiState> = _state.asStateFlow()

    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    init {
        reload()
    }

    /** Color de la materia (de los cursos guardados), para la tarjeta de la sección. */
    fun colorOf(subject: String): String? =
        courses.cached().firstOrNull { it.getNombreLimpio().equals(subject, ignoreCase = true) }?.getColorHexaLimpio()

    fun reload() {
        val folder = _state.value.folder
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            messages.getMessages(folder.id).fold(
                onSuccess = { list -> _state.update { it.copy(messages = list, loading = false, openSection = keepIfStillThere(it.openSection, list)) } },
                onFailure = { error ->
                    _state.update { it.copy(loading = false) }
                    _errors.send("Error al cargar mensajes: ${error.localizedMessage}")
                }
            )
        }
    }

    fun selectFolder(folder: MessagesUiState.Folder) {
        if (folder == _state.value.folder) return
        _state.update { it.copy(folder = folder) }
        reload()
    }

    fun openSection(key: String) = _state.update { it.copy(openSection = key) }

    /** Atrás: de los mensajes de una materia vuelve a las secciones. @return false si hay que cerrar la pantalla. */
    fun back(): Boolean {
        if (_state.value.openSection == null) return false
        _state.update { it.copy(openSection = null) }
        return true
    }

    /** Si la materia abierta ya no tiene mensajes en esta carpeta, se vuelve a las secciones. */
    private fun keepIfStillThere(open: String?, list: List<MessageItem>): String? =
        open?.takeIf { it == MessageSections.ALL || list.any { m -> MessageSections.subjectOf(m) == it } }
}
