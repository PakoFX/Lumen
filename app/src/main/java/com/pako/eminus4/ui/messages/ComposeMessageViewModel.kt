package com.pako.eminus4.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.pako.eminus4.core.isSessionExpired
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.repository.CourseRepository
import com.pako.eminus4.data.repository.MessageRepository
import com.pako.eminus4.domain.MessageDraft
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ComposeUiState(
    /** Solo materias vigentes (igual que Eminus web). */
    val courses: List<Course> = emptyList(),
    val course: Course? = null,
    val available: List<JsonObject> = emptyList(),
    val chosen: List<JsonObject> = emptyList(),
    val loadingRecipients: Boolean = false,
    val recipientsError: String? = null,
    val sending: Boolean = false,
    val sendingFiles: Boolean = false
)

sealed interface ComposeEvent {
    data object Sent : ComposeEvent
    data class Invalid(val problem: MessageDraft.Problem) : ComposeEvent
    data class Failed(val reason: String?) : ComposeEvent
}

/**
 * Redactar (o responder) un mensaje.
 * @param parentId 0 = mensaje nuevo; id del original = respuesta.
 * @param initialCourseId / [preselectedUserId] para abrir ya con materia y destinatario (Miembros, Responder).
 */
class ComposeMessageViewModel(
    private val messages: MessageRepository,
    courses: CourseRepository,
    private val parentId: Long,
    initialCourseId: Long,
    private val preselectedUserId: String?,
    private val myStudentId: () -> String?
) : ViewModel() {

    private val _state = MutableStateFlow(
        ComposeUiState(courses = courses.cached().filter { it.isActivo() }.sortedBy { it.getNombreLimpio() })
    )
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    private val _events = Channel<ComposeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var recipientsJob: Job? = null

    val isReply: Boolean get() = parentId > 0

    init {
        val list = _state.value.courses
        (list.firstOrNull { it.idCurso == initialCourseId } ?: list.singleOrNull())?.let { selectCourse(it, preselectedUserId) }
    }

    /** Al cambiar de materia se reinician los destinatarios y se piden los de la nueva. */
    fun selectCourse(course: Course, preselect: String? = null) {
        val current = _state.value
        if (current.course?.idCurso == course.idCurso && current.available.isNotEmpty()) return
        _state.update { it.copy(course = course, available = emptyList(), chosen = emptyList(), loadingRecipients = true, recipientsError = null) }
        recipientsJob?.cancel()
        recipientsJob = viewModelScope.launch {
            messages.getRecipients(course.idCurso).fold(
                onSuccess = { all ->
                    val available = MessageDraft.selectable(all, myStudentId())
                    val chosen = preselect?.let { id -> available.filter { MessageDraft.recipientId(it).equals(id, ignoreCase = true) } }.orEmpty()
                    _state.update { it.copy(available = available, chosen = chosen, loadingRecipients = false) }
                },
                onFailure = { e -> _state.update { it.copy(loadingRecipients = false, recipientsError = e.message) } }
            )
        }
    }

    /** Qué falta para poder enviar (antes de copiar archivos). */
    fun problem(subject: String, body: String): MessageDraft.Problem? =
        _state.value.let { MessageDraft.validate(it.course != null, it.chosen.size, subject, body) }

    fun setChosen(chosen: List<JsonObject>) = _state.update { it.copy(chosen = chosen) }

    fun remove(recipient: JsonObject) = _state.update { it.copy(chosen = it.chosen - recipient) }

    /**
     * Envía (borrador → adjuntos → enviar). [onFilesDone] borra las copias temporales de los archivos
     * cuando ya no hacen falta, salga bien o mal.
     */
    fun send(subject: String, body: String, files: List<EminusHttp.UploadFile>, onFilesDone: () -> Unit) {
        val s = _state.value
        if (s.sending) return
        MessageDraft.validate(s.course != null, s.chosen.size, subject, body)?.let {
            onFilesDone()
            _events.trySend(ComposeEvent.Invalid(it))
            return
        }
        _state.update { it.copy(sending = true, sendingFiles = files.isNotEmpty()) }
        viewModelScope.launch {
            val result = messages.send(subject.trim(), MessageDraft.toHtml(body.trim()), s.chosen, parentId, files)
            onFilesDone()
            _state.update { it.copy(sending = false, sendingFiles = false) }
            _events.send(result.fold(
                onSuccess = { ComposeEvent.Sent },
                onFailure = { ComposeEvent.Failed(if (it.isSessionExpired()) "tu sesión caducó, renuévala" else it.message) }
            ))
        }
    }
}
