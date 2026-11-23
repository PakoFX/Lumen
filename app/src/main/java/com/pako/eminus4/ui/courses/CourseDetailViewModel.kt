package com.pako.eminus4.ui.courses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.model.Activity
import com.pako.eminus4.data.model.CourseMember
import com.pako.eminus4.data.repository.CourseSectionsRepository
import com.pako.eminus4.data.repository.CourseSectionsRepository.Tracking
import com.pako.eminus4.data.repository.CourseSectionsRepository.UnitContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado de una pestaña: se carga la primera vez que se abre y se guarda. */
sealed interface TabData<out T> {
    data object NotLoaded : TabData<Nothing>
    data object Loading : TabData<Nothing>
    data class Loaded<T>(val data: T) : TabData<T>
    data class Failed(val reason: String?) : TabData<Nothing>
}

enum class CourseTab { ACTIVITIES, CONTENT, MEMBERS, TRACKING }

data class CourseDetailUiState(
    val tab: CourseTab = CourseTab.ACTIVITIES,
    val activities: TabData<List<Activity>> = TabData.NotLoaded,
    val content: TabData<List<UnitContent>> = TabData.NotLoaded,
    val members: TabData<List<CourseMember>> = TabData.NotLoaded,
    val tracking: TabData<Tracking> = TabData.NotLoaded
) {
    /** Recargando la pestaña visible (para el círculo de deslizar). */
    val refreshing: Boolean get() = dataOf(tab) is TabData.Loading

    fun dataOf(tab: CourseTab): TabData<*> = when (tab) {
        CourseTab.ACTIVITIES -> activities
        CourseTab.CONTENT -> content
        CourseTab.MEMBERS -> members
        CourseTab.TRACKING -> tracking
    }
}

class CourseDetailViewModel(
    private val repository: CourseSectionsRepository,
    private val courseId: Long,
    private val userId: () -> String
) : ViewModel() {

    private val _state = MutableStateFlow(CourseDetailUiState())
    val state: StateFlow<CourseDetailUiState> = _state.asStateFlow()

    init {
        load(CourseTab.ACTIVITIES)
    }

    fun select(tab: CourseTab) {
        _state.update { it.copy(tab = tab) }
        if (_state.value.dataOf(tab) is TabData.NotLoaded) load(tab)
    }

    /** Deslizar para recargar: vuelve a pedir la pestaña visible. */
    fun reload() = load(_state.value.tab)

    /** Tras marcar un elemento como leído / me gusta / comentar. */
    fun invalidateContent() {
        if (_state.value.tab == CourseTab.CONTENT) load(CourseTab.CONTENT)
        else _state.update { it.copy(content = TabData.NotLoaded) }
    }

    private fun load(tab: CourseTab) = when (tab) {
        CourseTab.ACTIVITIES -> loadInto({ repository.getActivities(courseId) }) { s, d -> s.copy(activities = d) }
        CourseTab.CONTENT -> loadInto({ repository.getContent(courseId) }) { s, d -> s.copy(content = d) }
        CourseTab.MEMBERS -> loadInto({ repository.getMembers(courseId) }) { s, d -> s.copy(members = d) }
        CourseTab.TRACKING -> loadInto({ repository.getTracking(courseId, userId()) }) { s, d -> s.copy(tracking = d) }
    }

    /** Marca la pestaña como cargando, pide sus datos y guarda el resultado (o el error). */
    private fun <T> loadInto(fetch: suspend () -> Result<T>, put: (CourseDetailUiState, TabData<T>) -> CourseDetailUiState) {
        _state.update { put(it, TabData.Loading) }
        viewModelScope.launch {
            val data: TabData<T> = fetch().fold({ TabData.Loaded(it) }, { TabData.Failed(it.message) })
            _state.update { put(it, data) }
        }
    }
}
