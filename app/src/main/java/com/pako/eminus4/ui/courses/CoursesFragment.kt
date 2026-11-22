package com.pako.eminus4.ui.courses

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.Course

class CoursesFragment : Fragment() {

    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EminusApiClient
    private lateinit var courseAdapter: CourseAdapter

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rvCourses: RecyclerView
    private lateinit var progressLoading: ProgressBar
    private lateinit var layoutEmptyState: View
    private lateinit var txtEmptyMessage: TextView
    private lateinit var txtFiltroActual: TextView
    private lateinit var imgDesplegarFiltros: android.widget.ImageView
    private lateinit var chipGroupFilters: com.google.android.material.chip.ChipGroup
    private lateinit var chipFilterActivos: Chip
    private lateinit var chipFilterTodos: Chip
    private lateinit var chipFilterArchivados: Chip

    /** Apartados de la lista (mismos criterios que Eminus web). */
    private lateinit var layoutBuscar: View
    private lateinit var editBuscar: com.google.android.material.textfield.TextInputEditText

    /** Minúsculas y sin acentos: "Diseño" encuentra "DISENO" y al revés. */
    private fun normalizar(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ").trim()

    private enum class Filtro { ACTIVOS, TODOS, ARCHIVADOS }

    private var allCourses: List<Course> = emptyList()

    private fun filtroActual(): Filtro = when {
        chipFilterArchivados.isChecked -> Filtro.ARCHIVADOS
        chipFilterTodos.isChecked -> Filtro.TODOS
        else -> Filtro.ACTIVOS
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_courses, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = SessionManager(requireContext())
        apiClient = EminusApiClient(sessionManager)

        inicializarVistas(view)
        configurarRecyclerView()
        configurarFiltros()
        cargarCursosDesdeCache()
        cargarCursosDesdeApi()
    }

    private fun cargarCursosDesdeCache() {
        val cached = sessionManager.getCachedCourses()
        if (cached.isNotEmpty()) {
            allCourses = cached
            aplicarFiltro()
        }
    }

    private fun inicializarVistas(view: View) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        rvCourses = view.findViewById(R.id.rvCourses)
        progressLoading = view.findViewById(R.id.progressLoading)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)
        txtEmptyMessage = view.findViewById(R.id.txtEmptyMessage)
        txtFiltroActual = view.findViewById(R.id.txtFiltroActual)
        imgDesplegarFiltros = view.findViewById(R.id.imgDesplegarFiltros)
        chipGroupFilters = view.findViewById(R.id.chipGroupFilters)
        chipFilterActivos = view.findViewById(R.id.chipFilterActivos)
        chipFilterTodos = view.findViewById(R.id.chipFilterTodos)
        chipFilterArchivados = view.findViewById(R.id.chipFilterArchivados)
        layoutBuscar = view.findViewById(R.id.layoutBuscarCurso)
        editBuscar = view.findViewById(R.id.editBuscarCurso)
        editBuscar.doAfterTextChanged { aplicarFiltro(alInicio = true) }
        editBuscar.setOnEditorActionListener { v, _, _ ->
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus(); true
        }

        // La barra de estado la cubre la barra superior de MainActivity
        swipeRefreshLayout.setColorSchemeColors(requireContext().getColor(android.R.color.holo_green_dark))
        // Recargar = deslizar hacia abajo (el botón de recargar era redundante y se quitó)
        swipeRefreshLayout.setOnRefreshListener {
            cargarCursosDesdeApi()
        }
    }

    private fun configurarRecyclerView() {
        courseAdapter = CourseAdapter(onCourseLongClick = ::confirmarArchivado) { curso ->
            val intent = Intent(requireContext(), CourseDetailActivity::class.java).apply {
                putExtra(CourseDetailActivity.EXTRA_COURSE_ID, curso.idCurso)
                putExtra(CourseDetailActivity.EXTRA_COURSE_NAME, curso.nombre)
                putExtra(CourseDetailActivity.EXTRA_COURSE_COLOR, curso.getColorHexaLimpio())
                putExtra(CourseDetailActivity.EXTRA_COURSE_THUMBNAIL, curso.getFullThumbnailUrl())
                putExtra(CourseDetailActivity.EXTRA_COURSE_ACTIVIDADES, curso.actividades)
                putExtra(CourseDetailActivity.EXTRA_COURSE_FOROS, curso.foros)
                putExtra(CourseDetailActivity.EXTRA_COURSE_INTEGRANTES, curso.integrantes)
                putExtra(CourseDetailActivity.EXTRA_COURSE_DEPENDENCIA, curso.dependencia)
            }
            startActivity(intent)
        }
        rvCourses.layoutManager = LinearLayoutManager(requireContext())
        rvCourses.adapter = courseAdapter
    }

    // Filtros plegables: se recuerda si el usuario los dejó abiertos
    private val prefsUi by lazy { requireContext().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }

    private fun ponerFiltrosAbiertos(abiertos: Boolean, animar: Boolean) {
        if (animar) android.transition.TransitionManager.beginDelayedTransition(chipGroupFilters.parent as ViewGroup)
        chipGroupFilters.visibility = if (abiertos) View.VISIBLE else View.GONE
        imgDesplegarFiltros.animate().rotation(if (abiertos) 180f else 0f).setDuration(if (animar) 180 else 0).start()
        prefsUi.edit().putBoolean("cursos_filtros_abiertos", abiertos).apply()
    }

    private fun configurarFiltros() {
        ponerFiltrosAbiertos(prefsUi.getBoolean("cursos_filtros_abiertos", false), animar = false)
        requireView().findViewById<View>(R.id.btnDesplegarFiltros).setOnClickListener {
            ponerFiltrosAbiertos(chipGroupFilters.visibility != View.VISIBLE, animar = true)
        }
        listOf(chipFilterActivos, chipFilterTodos, chipFilterArchivados).forEach { chip ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) aplicarFiltro(alInicio = true)
            }
        }
    }

    // ===================== ARCHIVAR =====================

    /** Igual que Eminus web: se confirma al archivar; desarchivar es directo. */
    private fun confirmarArchivado(curso: Course) {
        val nombre = curso.getNombreLimpio()
        if (curso.esArchivado()) {
            cambiarArchivado(curso, archivar = false)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Archivar curso")
            .setMessage("¿Deseas archivar \"$nombre\"? Solo estará disponible en el apartado Archivados.")
            .setPositiveButton("Archivar") { _, _ -> cambiarArchivado(curso, archivar = true) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Actualiza al instante en la lista y en Eminus; si Eminus falla, se revierte. */
    private fun cambiarArchivado(curso: Course, archivar: Boolean) {
        val anterior = allCourses
        actualizarCursos(allCourses.map {
            if (it.idCurso == curso.idCurso) it.copy(archivado = if (archivar) 1 else 0) else it
        })
        apiClient.setCourseArchived(curso.idCurso, archivar) { result ->
            if (!isAdded) return@setCourseArchived
            result.onSuccess {
                Toast.makeText(
                    requireContext(),
                    if (archivar) "Curso archivado" else "Curso restaurado",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { e ->
                actualizarCursos(anterior)
                Toast.makeText(requireContext(), "No se pudo ${if (archivar) "archivar" else "restaurar"}: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun actualizarCursos(cursos: List<Course>) {
        allCourses = cursos
        sessionManager.saveCachedCourses(cursos)
        aplicarFiltro()
    }

    fun recargarCursos() {
        if (isAdded) {
            cargarCursosDesdeApi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allCourses.isEmpty() && !sessionManager.isSessionExpired()) {
            cargarCursosDesdeApi()
        }
    }

    private fun cargarCursosDesdeApi() {
        if (!swipeRefreshLayout.isRefreshing && allCourses.isEmpty()) {
            progressLoading.visibility = View.VISIBLE
        }

        apiClient.getAllCourses { result ->
            if (!isAdded) return@getAllCourses

            progressLoading.visibility = View.GONE
            swipeRefreshLayout.isRefreshing = false

            result.onSuccess { cursos ->
                allCourses = cursos
                sessionManager.saveCachedCourses(cursos)
                sessionManager.setSessionExpired(false)
                (activity as? com.pako.eminus4.ui.main.MainActivity)?.ocultarBannerSesion()
                aplicarFiltro()
            }.onFailure { error ->
                val esExpirada = error.message?.contains("401") == true || error.message?.contains("expirada") == true
                if (esExpirada) {
                    sessionManager.setSessionExpired(true)
                }

                if (allCourses.isNotEmpty()) {
                    val mensaje = if (esExpirada) {
                        "Sesión caducada. Mostrando cursos guardados."
                    } else {
                        "Modo sin conexión. Mostrando cursos guardados."
                    }
                    (activity as? com.pako.eminus4.ui.main.MainActivity)?.mostrarBannerSesionExpirada(mensaje)
                    aplicarFiltro()
                } else {
                    val mensaje = if (esExpirada) {
                        "Sesión en línea caducada."
                    } else {
                        "Sin conexión a internet."
                    }
                    (activity as? com.pako.eminus4.ui.main.MainActivity)?.mostrarBannerSesionExpirada(mensaje)

                    layoutEmptyState.visibility = View.VISIBLE
                    txtEmptyMessage.text = if (esExpirada) {
                        "Tu sesión en línea ha caducado y aún no tienes materias descargadas.\n\nToca \"Renovar\" arriba para iniciar sesión y sincronizar tus cursos."
                    } else {
                        "No se pudo conectar a Eminus.\nDesliza hacia abajo para reintentar."
                    }
                    courseAdapter.submitList(emptyList())
                }
            }
        }
    }

    /**
     * Apartados (mismos criterios que Eminus web):
     *  - Activos: vigentes y no archivados.
     *  - Todos: activos → cerrados → archivados (los nuevos aparecen DEBAJO de los activos,
     *    no arriba fuera de la vista).
     *  - Archivados: los que el alumno archivó.
     * @param alInicio al cambiar de apartado, volver al principio de la lista.
     */
    private fun aplicarFiltro(alInicio: Boolean = false) {
        val filtro = filtroActual()
        val (archivados, visibles) = allCourses.partition { it.esArchivado() }
        val (activos, cerrados) = visibles.partition { it.isActivo() }
        val delFiltro = when (filtro) {
            Filtro.ACTIVOS -> activos
            Filtro.TODOS -> activos + cerrados + archivados
            Filtro.ARCHIVADOS -> archivados
        }
        // Buscador solo en Todos / Archivados
        val conBuscador = filtro != Filtro.ACTIVOS
        if (layoutBuscar.visibility != (if (conBuscador) View.VISIBLE else View.GONE)) {
            layoutBuscar.visibility = if (conBuscador) View.VISIBLE else View.GONE
            if (!conBuscador && !editBuscar.text.isNullOrEmpty()) editBuscar.setText("")
        }
        val consulta = if (conBuscador) normalizar(editBuscar.text?.toString().orEmpty()) else ""
        val cursosFiltrados = if (consulta.isBlank()) delFiltro else {
            val palabras = consulta.split(' ').filter { it.isNotBlank() }
            delFiltro.filter { c ->
                val nombre = normalizar(c.nombre + " " + com.pako.eminus4.util.NombreMateria.bonito(c.nombre))
                palabras.all { nombre.contains(it) }
            }
        }

        txtFiltroActual.text = when (filtro) {
            Filtro.ACTIVOS -> "Activos"
            Filtro.TODOS -> "Todos los cursos"
            Filtro.ARCHIVADOS -> "Archivados"
        } + " · ${cursosFiltrados.size}"
        listOf(chipFilterActivos to activos.size, chipFilterTodos to (activos.size + cerrados.size + archivados.size),
            chipFilterArchivados to archivados.size).forEach { (chip, n) ->
            chip.text = chip.text.toString().substringBefore(" · ") + " · $n"
        }

        courseAdapter.submitList(cursosFiltrados) {
            if (alInicio && isAdded) rvCourses.scrollToPosition(0)
        }

        if (cursosFiltrados.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            txtEmptyMessage.text = if (consulta.isNotBlank()) "Ninguna materia coincide con \"${editBuscar.text?.toString()?.trim()}\"" else when (filtro) {
                Filtro.ACTIVOS -> "No tienes materias activas en este período"
                Filtro.TODOS -> "No se encontraron materias registradas"
                Filtro.ARCHIVADOS -> "No tienes cursos archivados.\n\nMantén presionado un curso para archivarlo."
            }
        } else {
            layoutEmptyState.visibility = View.GONE
        }
    }
}

