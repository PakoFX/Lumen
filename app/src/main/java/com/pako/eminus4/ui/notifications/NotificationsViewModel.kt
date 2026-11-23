package com.pako.eminus4.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.data.repository.AgendaRepository
import com.pako.eminus4.data.repository.AgendaRepository.Agenda
import com.pako.eminus4.data.repository.AgendaRepository.Partial
import com.pako.eminus4.domain.ExamRules
import com.pako.eminus4.notificaciones.ReminderUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que pinta Avisos. Cada fuente llega por su cuenta; `null` = aún no llega. */
data class NoticesUiState(
    val agenda: Agenda? = null,
    val unreadMessages: Partial<MessageItem>? = null,
    val exams: Partial<ExamItem>? = null,
    val forums: Partial<ForumItem>? = null,
    val loading: Boolean = false
) {
    val agendaComplete: Boolean get() = agenda?.isComplete == true
    /** Exámenes/foros solo cuentan como completos si la agenda también lo está (son de sus cursos). */
    val examsComplete: Boolean get() = exams?.complete == true && agendaComplete
    val forumsComplete: Boolean get() = forums?.complete == true && agendaComplete
    val messagesComplete: Boolean get() = unreadMessages?.complete == true

    val availableExams: List<ExamItem> get() = ExamRules.availableByClosing(exams?.items.orEmpty())
    val forumsWithNews: List<ForumItem> get() = forumsWithNews(forums?.items.orEmpty())
}

/** Foros con aportes nuevos, el de más aportes arriba. */
fun forumsWithNews(forums: List<ForumItem>): List<ForumItem> =
    forums.filter { it.nuevosComentarios > 0 }.sortedByDescending { it.nuevosComentarios }

class NotificationsViewModel(
    private val repository: AgendaRepository,
    private val reminders: ReminderUpdater,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _state = MutableStateFlow(NoticesUiState())
    val state: StateFlow<NoticesUiState> = _state.asStateFlow()

    private var lastLoadAt = 0L

    /** Al volver a la pantalla: recargar si nunca cargó o si pasó más de un minuto. */
    fun refreshIfStale() {
        if (lastLoadAt == 0L || clock() - lastLoadAt > STALE_MS) load()
    }

    /** Deslizar para recargar / volver de un detalle. */
    fun load(force: Boolean = false) {
        if (_state.value.loading && !force) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val agenda = repository.loadAgenda()
            lastLoadAt = clock()
            _state.update { it.copy(agenda = agenda, loading = false) }
            // Solo con datos completos (si faltan cursos, no borrar recordatorios buenos)
            if (agenda.isComplete) reminders.tasks(agenda.activities)
            publishSnapshot()

            launch {
                val result = repository.loadUnreadMessages()
                _state.update { it.copy(unreadMessages = Partial(result.getOrNull().orEmpty(), result.isSuccess)) }
                publishSnapshot()
            }
            launch {
                val exams = repository.loadExams(agenda.courses)
                _state.update { it.copy(exams = exams) }
                if (_state.value.examsComplete) reminders.exams(exams.items)
                publishSnapshot()
            }
            launch {
                val forums = repository.loadForums(agenda.courses)
                _state.update { it.copy(forums = forums) }
                if (_state.value.forumsComplete) reminders.forums(forums.items)
                publishSnapshot()
            }
        }
    }

    /** Lo que Pendientes desglosa sin volver a pedir a Eminus. */
    private fun publishSnapshot() {
        val s = _state.value
        repository.updateSnapshot(
            AgendaRepository.Snapshot(
                agenda = s.agenda,
                unreadMessages = s.unreadMessages?.items.orEmpty(),
                availableExams = s.availableExams,
                forumsWithNews = s.forumsWithNews
            )
        )
    }

    private companion object {
        const val STALE_MS = 60_000L
    }
}
