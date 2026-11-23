package com.pako.eminus4.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado común de las listas "por materia" (Exámenes, Foros): apartado, bloques abiertos y datos. */
data class SubjectBlocksState<T>(
    val loaded: T? = null,
    val showPast: Boolean = false,
    val expanded: Set<Long> = emptySet(),
    val loading: Boolean = false
)

/** Cuántas materias hay en cada apartado (para los chips y para abrir en "pasadas" si no hay activas). */
data class SubjectCounts(val active: Int, val past: Int)

/**
 * Lógica compartida de las pantallas con bloques por materia, activas o pasadas:
 * cargar, cambiar de apartado, desplegar bloques y, la primera vez, abrir en "pasadas"
 * si en las activas no hay nada.
 */
abstract class SubjectBlocksViewModel<T> : ViewModel() {

    private val _state = MutableStateFlow(SubjectBlocksState<T>())
    val state: StateFlow<SubjectBlocksState<T>> = _state.asStateFlow()

    private val _errors = Channel<String>(Channel.BUFFERED)
    /** Errores para mostrar una sola vez (Toast). */
    val errors = _errors.receiveAsFlow()

    private var sectionDecided = false

    protected abstract suspend fun fetch(force: Boolean): Result<T>

    protected abstract fun counts(loaded: T): SubjectCounts

    /** Llamar desde el `init` de la subclase (cuando sus dependencias ya existen). */
    protected fun start() = load(force = false)

    fun load(force: Boolean = true) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            fetch(force).fold(
                onSuccess = { loaded ->
                    _state.update { it.copy(loaded = loaded, loading = false) }
                    decideInitialSection(counts(loaded))
                },
                onFailure = { error ->
                    _state.update { it.copy(loading = false) }
                    _errors.send("Error al cargar cursos: ${error.localizedMessage}")
                }
            )
        }
    }

    fun showPast(past: Boolean) {
        sectionDecided = true
        _state.update { it.copy(showPast = past) }
    }

    fun toggle(courseId: Long) = _state.update {
        it.copy(expanded = if (courseId in it.expanded) it.expanded - courseId else it.expanded + courseId)
    }

    private fun decideInitialSection(counts: SubjectCounts) {
        if (sectionDecided) return
        if (counts.active == 0 && counts.past > 0) _state.update { it.copy(showPast = true) }
        if (counts.active + counts.past > 0) sectionDecided = true
    }
}
