package com.pako.eminus4.ui.courses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.domain.CourseCatalog
import com.pako.eminus4.domain.CourseCatalog.Section
import com.pako.eminus4.ui.main.MainActivity
import kotlinx.coroutines.launch

/** Lista de cursos. Solo dibuja [CoursesUiState]; la lógica vive en [CoursesViewModel]. */
class CoursesFragment : Fragment(R.layout.fragment_courses) {

    private val viewModel: CoursesViewModel by viewModels {
        viewModelFactory { initializer { CoursesViewModel(requireContext().appContainer.courseRepository) } }
    }

    private lateinit var courseAdapter: CourseAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvCourses: RecyclerView
    private lateinit var progressLoading: ProgressBar
    private lateinit var layoutEmptyState: View
    private lateinit var txtEmptyMessage: TextView
    private lateinit var txtCurrentSection: TextView
    private lateinit var imgToggleFilters: ImageView
    private lateinit var chipGroupFilters: ChipGroup
    private lateinit var layoutSearch: View
    private lateinit var editSearch: TextInputEditText
    private lateinit var sectionChips: Map<Section, Chip>

    /** Para avisar del estado de la sesión una sola vez por cambio, no en cada repintado. */
    private var shownFailure: CoursesUiState.LoadFailure? = null
    private var shownSection: Section? = null

    private val uiPrefs by lazy { requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setUpList()
        setUpFilters(view)
        observe()
    }

    /** Llamado por MainActivity al volver a la pantalla: reutiliza la lista si se pidió hace segundos. */
    fun recargarCursos() {
        if (isAdded) viewModel.refresh(force = false)
    }

    private fun bindViews(view: View) {
        swipeRefresh = view.findViewById(R.id.swipeRefreshLayout)
        rvCourses = view.findViewById(R.id.rvCourses)
        progressLoading = view.findViewById(R.id.progressLoading)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)
        txtEmptyMessage = view.findViewById(R.id.txtEmptyMessage)
        txtCurrentSection = view.findViewById(R.id.txtFiltroActual)
        imgToggleFilters = view.findViewById(R.id.imgDesplegarFiltros)
        chipGroupFilters = view.findViewById(R.id.chipGroupFilters)
        layoutSearch = view.findViewById(R.id.layoutBuscarCurso)
        editSearch = view.findViewById(R.id.editBuscarCurso)
        sectionChips = mapOf(
            Section.ACTIVE to view.findViewById(R.id.chipFilterActivos),
            Section.ALL to view.findViewById(R.id.chipFilterTodos),
            Section.ARCHIVED to view.findViewById(R.id.chipFilterArchivados)
        )
        swipeRefresh.setColorSchemeColors(requireContext().getColor(android.R.color.holo_green_dark))
        swipeRefresh.setOnRefreshListener { viewModel.refresh() }
    }

    private fun setUpList() {
        courseAdapter = CourseAdapter(onCourseLongClick = ::confirmArchive, onCourseClick = ::openCourse)
        rvCourses.layoutManager = LinearLayoutManager(requireContext())
        rvCourses.adapter = courseAdapter
    }

    private fun setUpFilters(view: View) {
        showFilters(uiPrefs.getBoolean(PREF_FILTERS_OPEN, false), animate = false)
        view.findViewById<View>(R.id.btnDesplegarFiltros).setOnClickListener {
            showFilters(chipGroupFilters.visibility != View.VISIBLE, animate = true)
        }
        sectionChips.forEach { (section, chip) ->
            chip.setOnCheckedChangeListener { _, checked -> if (checked) viewModel.selectSection(section) }
        }
        editSearch.doAfterTextChanged { viewModel.search(it?.toString().orEmpty()) }
        editSearch.setOnEditorActionListener { v, _, _ ->
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        }
    }

    /** Los filtros se pliegan para ahorrar pantalla; se recuerda cómo los dejó la persona. */
    private fun showFilters(open: Boolean, animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(chipGroupFilters.parent as ViewGroup)
        chipGroupFilters.visibility = if (open) View.VISIBLE else View.GONE
        imgToggleFilters.animate().rotation(if (open) 180f else 0f).setDuration(if (animate) 180 else 0).start()
        uiPrefs.edit().putBoolean(PREF_FILTERS_OPEN, open).apply()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun render(state: CoursesUiState) {
        val selection = state.selection
        swipeRefresh.isRefreshing = state.loading && state.all.isNotEmpty()
        progressLoading.visibility = if (state.loading && state.all.isEmpty()) View.VISIBLE else View.GONE

        renderSection(state, selection.counts)
        val sectionChanged = shownSection != state.section
        shownSection = state.section
        courseAdapter.submitList(selection.courses) { if (sectionChanged && isAdded) rvCourses.scrollToPosition(0) }

        renderEmptyState(state, selection.courses.isEmpty())
        renderSessionBanner(state)
    }

    private fun renderSection(state: CoursesUiState, counts: CourseCatalog.Counts) {
        txtCurrentSection.text = "${state.section.title} · ${state.selection.courses.size}"
        sectionChips.forEach { (section, chip) ->
            chip.text = chip.text.toString().substringBefore(" · ") + " · ${counts.of(section)}"
            if (chip.isChecked != (section == state.section)) chip.isChecked = section == state.section
        }
        val withSearch = CourseCatalog.hasSearch(state.section)
        layoutSearch.visibility = if (withSearch) View.VISIBLE else View.GONE
        if (!withSearch && !editSearch.text.isNullOrEmpty()) editSearch.setText("")
    }

    private fun renderEmptyState(state: CoursesUiState, empty: Boolean) {
        layoutEmptyState.visibility = if (empty && !state.loading) View.VISIBLE else View.GONE
        if (!empty) return
        txtEmptyMessage.text = when {
            state.all.isEmpty() && state.loadFailure == CoursesUiState.LoadFailure.SESSION_EXPIRED ->
                "Tu sesión en línea ha caducado y aún no tienes materias descargadas.\n\nToca \"Renovar\" arriba para iniciar sesión y sincronizar tus cursos."
            state.all.isEmpty() && state.loadFailure == CoursesUiState.LoadFailure.OFFLINE ->
                "No se pudo conectar a Eminus.\nDesliza hacia abajo para reintentar."
            state.query.isNotBlank() && CourseCatalog.hasSearch(state.section) ->
                "Ninguna materia coincide con \"${state.query.trim()}\""
            state.section == Section.ACTIVE -> "No tienes materias activas en este período"
            state.section == Section.ALL -> "No se encontraron materias registradas"
            else -> "No tienes cursos archivados.\n\nMantén presionado un curso para archivarlo."
        }
    }

    /** Banner de MainActivity: sesión caducada / sin conexión, o se oculta al volver a cargar bien. */
    private fun renderSessionBanner(state: CoursesUiState) {
        if (state.loading || state.loadFailure == shownFailure) return
        shownFailure = state.loadFailure
        val main = activity as? MainActivity ?: return
        val expired = state.loadFailure == CoursesUiState.LoadFailure.SESSION_EXPIRED
        when {
            state.loadFailure == null -> main.ocultarBannerSesion()
            state.all.isNotEmpty() -> main.mostrarBannerSesionExpirada(
                if (expired) "Sesión caducada. Mostrando cursos guardados." else "Modo sin conexión. Mostrando cursos guardados."
            )
            else -> main.mostrarBannerSesionExpirada(if (expired) "Sesión en línea caducada." else "Sin conexión a internet.")
        }
    }

    private fun onEvent(event: CoursesEvent) {
        val message = when (event) {
            is CoursesEvent.Archived -> if (event.archived) "Curso archivado" else "Curso restaurado"
            is CoursesEvent.ArchiveFailed -> "No se pudo ${if (event.archived) "archivar" else "restaurar"}: ${event.reason}"
        }
        Toast.makeText(requireContext(), message, if (event is CoursesEvent.ArchiveFailed) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** Igual que Eminus web: se confirma al archivar; restaurar es directo. */
    private fun confirmArchive(course: Course) {
        if (course.esArchivado()) {
            viewModel.setArchived(course, archived = false)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Archivar curso")
            .setMessage("¿Deseas archivar \"${course.getNombreLimpio()}\"? Solo estará disponible en el apartado Archivados.")
            .setPositiveButton("Archivar") { _, _ -> viewModel.setArchived(course, archived = true) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openCourse(course: Course) {
        startActivity(Intent(requireContext(), CourseDetailActivity::class.java).apply {
            putExtra(CourseDetailActivity.EXTRA_COURSE_ID, course.idCurso)
            putExtra(CourseDetailActivity.EXTRA_COURSE_NAME, course.nombre)
            putExtra(CourseDetailActivity.EXTRA_COURSE_COLOR, course.getColorHexaLimpio())
            putExtra(CourseDetailActivity.EXTRA_COURSE_THUMBNAIL, course.getFullThumbnailUrl())
            putExtra(CourseDetailActivity.EXTRA_COURSE_ACTIVIDADES, course.actividades)
            putExtra(CourseDetailActivity.EXTRA_COURSE_FOROS, course.foros)
            putExtra(CourseDetailActivity.EXTRA_COURSE_INTEGRANTES, course.integrantes)
            putExtra(CourseDetailActivity.EXTRA_COURSE_DEPENDENCIA, course.dependencia)
        })
    }

    private companion object {
        const val PREF_FILTERS_OPEN = "cursos_filtros_abiertos"
    }
}
