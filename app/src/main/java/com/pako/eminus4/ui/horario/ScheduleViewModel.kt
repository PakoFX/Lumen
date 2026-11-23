package com.pako.eminus4.ui.horario

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.local.SchedulePrefs
import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase
import com.pako.eminus4.data.repository.ScheduleRepository
import com.pako.eminus4.data.repository.ScheduleRepository.ReloadResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScheduleUiState(
    /** null = nunca se pudo traer de MiUV; vacío = MiUV no tiene clases este periodo. */
    val classes: List<ClaseHorario>? = null,
    val updatedAt: Long = 0L,
    val showFreeTime: Boolean = false,
    val reloading: Boolean = false,
    /** Cambia cada vez que hay que repintar las páginas (horas de término nuevas o a mano). */
    val revision: Int = 0
)

sealed interface ScheduleEvent {
    data object Reloaded : ScheduleEvent
    data object NoClasses : ScheduleEvent
    /** Hay que entrar otra vez a MiUV para traer el horario. */
    data object NeedsLogin : ScheduleEvent
    data class FreeTimeToggled(val visible: Boolean) : ScheduleEvent
}

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val prefs: SchedulePrefs,
    private val updatedAt: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleUiState(showFreeTime = prefs.showFreeTime))
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    private val _events = Channel<ScheduleEvent>(Channel.BUFFERED)
    val events: Flow<ScheduleEvent> = _events.receiveAsFlow()

    init {
        readSaved()
        completeMissingEndTimes()
        // Las horas de término llegan después (PDF de MiUV en segundo plano): repintar al llegar
        viewModelScope.launch { repository.updates.collect { readSaved() } }
    }

    fun manualEnd(subject: ClaseHorario, session: SesionClase): Int? = prefs.manualEnd(subject, session)

    /** Al volver a la pantalla: puede haber cambiado (p. ej. tras iniciar sesión otra vez). */
    fun onResume() = readSaved()

    fun toggleFreeTime() {
        val visible = !prefs.showFreeTime
        prefs.showFreeTime = visible
        _state.update { it.copy(showFreeTime = visible, revision = it.revision + 1) }
        _events.trySend(ScheduleEvent.FreeTimeToggled(visible))
    }

    fun setManualEnd(subject: ClaseHorario, session: SesionClase, minutes: Int) {
        prefs.setManualEnd(subject, session, minutes)
        bumpRevision()
    }

    fun removeManualEnd(subject: ClaseHorario, session: SesionClase) {
        prefs.removeManualEnd(subject, session)
        bumpRevision()
    }

    /** "Volver a cargar horario": lo trae de nuevo de MiUV (y sus horas de término). */
    fun reloadFromMiuv() {
        if (_state.value.reloading) return
        _state.update { it.copy(reloading = true) }
        viewModelScope.launch {
            val result = repository.reload()
            _state.update { it.copy(reloading = false) }
            if (result == ReloadResult.OK) readSaved()
            _events.send(
                when (result) {
                    ReloadResult.OK -> ScheduleEvent.Reloaded
                    ReloadResult.NO_CLASSES -> ScheduleEvent.NoClasses
                    else -> ScheduleEvent.NeedsLogin
                }
            )
        }
    }

    private fun readSaved() {
        val saved = repository.saved()
        _state.update {
            // Si lo guardado se vació a medias, conservar lo que ya se mostraba
            val classes = if (saved.isNullOrEmpty() && !it.classes.isNullOrEmpty()) it.classes else saved
            it.copy(classes = classes, updatedAt = updatedAt(), revision = it.revision + 1)
        }
    }

    /** Si falta alguna hora de término, reintentar el PDF de MiUV (como mucho cada 10 min). */
    private fun completeMissingEndTimes() {
        val missing = _state.value.classes.orEmpty().any { c -> c.sesiones.any { it.fin.isNullOrBlank() } }
        if (!missing || clock() - prefs.lastPdfAttemptAt <= PDF_RETRY_MS) return
        prefs.lastPdfAttemptAt = clock()
        repository.completeEndTimesInBackground()
    }

    private fun bumpRevision() = _state.update { it.copy(revision = it.revision + 1) }

    private companion object {
        const val PDF_RETRY_MS = 10 * 60_000L
    }
}
