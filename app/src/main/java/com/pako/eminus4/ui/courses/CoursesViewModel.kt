package com.pako.eminus4.ui.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.core.isSessionExpired
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.repository.CourseRepository
import com.pako.eminus4.domain.CourseCatalog
import com.pako.eminus4.domain.CourseCatalog.Section
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de la lista de cursos: datos, apartado, búsqueda y si falló la carga. */
data class CoursesUiState(
    val all: List<Course> = emptyList(),
    val section: Section = Section.ACTIVE,
    val query: String = "",
    val loading: Boolean = false,
    val loadFailure: LoadFailure? = null
) {
    enum class LoadFailure { SESSION_EXPIRED, OFFLINE }

    val selection: CourseCatalog.Selection get() = CourseCatalog.select(all, section, query)
}

/** Avisos de un solo uso para la vista. */
sealed interface CoursesEvent {
    data class Archived(val archived: Boolean) : CoursesEvent
    data class ArchiveFailed(val archived: Boolean, val reason: String?) : CoursesEvent
}

class CoursesViewModel(private val repository: CourseRepository) : ViewModel() {

    private val _state = MutableStateFlow(CoursesUiState(all = repository.cached()))
    val state: StateFlow<CoursesUiState> = _state.asStateFlow()

    private val _events = Channel<CoursesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            repository.refresh(force).fold(
                onSuccess = { courses -> _state.update { it.copy(all = courses, loading = false, loadFailure = null) } },
                onFailure = { error ->
                    val failure = if (error.isSessionExpired()) CoursesUiState.LoadFailure.SESSION_EXPIRED
                    else CoursesUiState.LoadFailure.OFFLINE
                    _state.update { it.copy(loading = false, loadFailure = failure) }
                }
            )
        }
    }

    fun selectSection(section: Section) = _state.update {
        it.copy(section = section, query = if (CourseCatalog.hasSearch(section)) it.query else "")
    }

    fun search(query: String) = _state.update { it.copy(query = query) }

    /** Se ve al instante; si Eminus falla, se revierte y se avisa. */
    fun setArchived(course: Course, archived: Boolean) {
        val before = _state.value.all
        _state.update { s ->
            s.copy(all = s.all.map { if (it.idCurso == course.idCurso) it.copy(archivado = if (archived) 1 else 0) else it })
        }
        viewModelScope.launch {
            repository.setArchived(course.idCurso, archived).fold(
                onSuccess = { _events.send(CoursesEvent.Archived(archived)) },
                onFailure = {
                    _state.update { s -> s.copy(all = before) }
                    _events.send(CoursesEvent.ArchiveFailed(archived, it.message))
                }
            )
        }
    }
}
