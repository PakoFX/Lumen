package com.pako.eminus4.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.model.MessageDetail
import com.pako.eminus4.data.repository.CourseRepository
import com.pako.eminus4.data.repository.MessageRepository
import com.pako.eminus4.domain.MessageRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado del detalle de un mensaje. */
sealed interface MessageDetailUiState {
    data object Loading : MessageDetailUiState
    data class Loaded(val detail: MessageDetail, val canReply: Boolean) : MessageDetailUiState
    /** No se pudo cargar: la pantalla avisa y se cierra (como antes). */
    data class Failed(val reason: String?) : MessageDetailUiState
}

class MessageDetailViewModel(
    private val messages: MessageRepository,
    private val courses: CourseRepository,
    private val messageId: Long,
    private val myStudentId: () -> String?
) : ViewModel() {

    private val _state = MutableStateFlow<MessageDetailUiState>(MessageDetailUiState.Loading)
    val state: StateFlow<MessageDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = messages.getDetail(messageId).fold(
                onSuccess = { detail ->
                    val active = courses.cached().filter { it.isActivo() }.map { it.idCurso }.toSet()
                    MessageDetailUiState.Loaded(detail, MessageRules.canReply(detail, myStudentId(), active))
                },
                onFailure = { MessageDetailUiState.Failed(it.localizedMessage) }
            )
        }
    }
}
