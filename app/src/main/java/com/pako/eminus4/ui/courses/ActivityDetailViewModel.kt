package com.pako.eminus4.ui.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.core.isNetworkError
import com.pako.eminus4.core.isSessionExpired
import com.pako.eminus4.data.model.ActivityDetail
import com.pako.eminus4.data.model.DeliveryAttachment
import com.pako.eminus4.data.model.StudentDelivery
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.repository.DeliveryRepository
import com.pako.eminus4.data.repository.DeliveryRepository.Outcome
import com.pako.eminus4.data.repository.DeliveryRepository.Step
import com.pako.eminus4.domain.DeliveryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ActivityDetailUiState(
    val courseId: Long = 0L,
    val activityId: Long = 0L,
    val detail: ActivityDetail? = null,
    val delivery: StudentDelivery? = null,
    val loading: Boolean = false,
    /** Texto del paso en curso al enviar/quitar; null = no se está enviando nada. */
    val sendingMessage: String? = null
) {
    val sending: Boolean get() = sendingMessage != null
}

sealed interface ActivityDetailEvent {
    data class Toast(val message: String) : ActivityDetailEvent
    /** Algo salió mal al enviar o quitar: se muestra en un diálogo. */
    data class Error(val message: String) : ActivityDetailEvent
    data class Submitted(val courseId: Long, val activityId: Long) : ActivityDetailEvent
}

/** Archivo elegido para entregar, ya leído por la vista. */
class PickedFile(val name: String, val mimeType: String, val read: () -> File)

class ActivityDetailViewModel(
    private val repository: DeliveryRepository,
    private val userId: () -> String?
) : ViewModel() {

    private val _state = MutableStateFlow(ActivityDetailUiState())
    val state: StateFlow<ActivityDetailUiState> = _state.asStateFlow()

    private val _events = Channel<ActivityDetailEvent>(Channel.BUFFERED)
    val events: Flow<ActivityDetailEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    /** Abre (o cambia a) una actividad. */
    fun open(courseId: Long, activityId: Long) {
        if (_state.value.sending) return
        _state.value = ActivityDetailUiState(courseId = courseId, activityId = activityId)
        reload()
    }

    fun reload() {
        val s = _state.value
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            // Instrucciones y adjuntos del docente
            launch {
                repository.getDetail(s.courseId, s.activityId)
                    .onSuccess { detail -> _state.update { it.copy(detail = detail) } }
                    .onFailure { e -> _events.send(ActivityDetailEvent.Toast("Error al cargar actividad: ${e.localizedMessage}")) }
                _state.update { it.copy(loading = false) }
            }
            // Estado de entrega y archivos que mandó el alumno
            val user = userId().orEmpty()
            if (user.isNotBlank()) launch {
                repository.getDelivery(s.courseId, s.activityId, user)
                    .onSuccess { delivery -> _state.update { it.copy(delivery = delivery) } }
            }
        }
    }

    fun submit(file: PickedFile, comment: String, preloadedComment: String?) {
        val s = _state.value
        if (s.sending) return
        setSending("Preparando archivo…")
        viewModelScope.launch {
            val temp = withContext(Dispatchers.IO) { runCatching(file.read).getOrNull() }
            if (temp == null) return@launch fail("No se pudo leer el archivo seleccionado.")
            val html = DeliveryRules.deliveryHtml(s.delivery?.descEntrega, comment, preloadedComment, file.name)
            val outcome = try {
                repository.submit(
                    s.courseId, s.activityId, s.delivery?.idActividadEntrega ?: 0L,
                    EminusHttp.UploadFile(temp, file.name, file.mimeType), html
                ) { step -> setSending(stepText(step, file.name)) }
            } finally {
                temp.delete()
            }
            when (outcome) {
                Outcome.Done -> {
                    setSending(null)
                    _events.send(ActivityDetailEvent.Toast("Entrega enviada a Eminus"))
                    _events.send(ActivityDetailEvent.Submitted(s.courseId, s.activityId))
                    reloadIfStill(s.activityId)
                }
                is Outcome.Blocked -> blocked(outcome)
                is Outcome.Failed -> fail(
                    if (outcome.step == Step.CONFIRMING) "El archivo se subió, pero no se pudo confirmar la entrega: ${errorText(outcome.error)}"
                    else errorText(outcome.error)
                )
            }
        }
    }

    fun removeFile(file: DeliveryAttachment) {
        val s = _state.value
        if (s.sending) return
        setSending("Quitando \"${file.nombreOriginal}\"…")
        viewModelScope.launch {
            val outcome = repository.removeFile(s.courseId, s.activityId, file.idArchivo) { step ->
                setSending(stepText(step, file.nombreOriginal))
            }
            when (outcome) {
                Outcome.Done -> {
                    setSending(null)
                    _events.send(ActivityDetailEvent.Toast("Archivo quitado de tu entrega"))
                    reloadIfStill(s.activityId)
                }
                is Outcome.Blocked -> blocked(outcome)
                is Outcome.Failed -> fail("No se pudo quitar el archivo: ${errorText(outcome.error)}")
            }
        }
    }

    /** Si la persona navegó a otra actividad mientras tanto, no se recarga encima. */
    private fun reloadIfStill(activityId: Long) {
        if (_state.value.activityId == activityId) reload()
    }

    private suspend fun blocked(outcome: Outcome.Blocked) {
        if (_state.value.activityId == outcome.detail.idActividad) _state.update { it.copy(detail = outcome.detail) }
        fail(outcome.reason)
    }

    private suspend fun fail(message: String) {
        setSending(null)
        _events.send(ActivityDetailEvent.Error(message))
    }

    private fun setSending(message: String?) = _state.update { it.copy(sendingMessage = message) }

    private fun stepText(step: Step, fileName: String) = when (step) {
        Step.CHECKING -> "Verificando que la actividad siga abierta…"
        Step.CREATING_DRAFT -> "Creando entrega en Eminus…"
        Step.UPLOADING -> "Subiendo \"$fileName\"…"
        Step.CONFIRMING -> "Confirmando entrega…"
        Step.REMOVING -> "Quitando \"$fileName\"…"
    }

    private fun errorText(e: Throwable) = when {
        e.isSessionExpired() -> "Tu sesión de Eminus caducó. Renueva la sesión desde la pantalla principal y vuelve a intentarlo."
        e.isNetworkError() -> "Sin conexión con Eminus. Revisa tu internet e inténtalo de nuevo."
        else -> e.localizedMessage ?: "Error desconocido"
    }
}
