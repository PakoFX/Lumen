package com.pako.eminus4.ui.courses

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.util.iconoInicio

/**
 * CourseDetailActivity — Muestra el detalle de un curso con su lista de actividades.
 *
 * Recibe los datos del curso por Intent (ID, nombre, color, thumbnail, stats)
 * y carga las actividades de la API de Eminus en segundo plano.
 */
class CourseDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE_ID          = "extra_course_id"
        const val EXTRA_COURSE_NAME        = "extra_course_name"
        const val EXTRA_COURSE_COLOR       = "extra_course_color"
        const val EXTRA_COURSE_THUMBNAIL   = "extra_course_thumbnail"
        const val EXTRA_COURSE_ACTIVIDADES = "extra_course_actividades"
        const val EXTRA_COURSE_FOROS       = "extra_course_foros"
        const val EXTRA_COURSE_INTEGRANTES = "extra_course_integrantes"
        const val EXTRA_COURSE_DEPENDENCIA = "extra_course_dependencia"
    }

    private lateinit var apiClient: EminusApiClient
    private lateinit var activityAdapter: ActivityAdapter

    private lateinit var collapsingToolbar: CollapsingToolbarLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var headerBg: View
    private lateinit var imgThumbnail: ImageView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvActivities: RecyclerView
    private lateinit var progressActivities: ProgressBar
    private lateinit var layoutEmpty: View
    private lateinit var txtEmpty: TextView
    private lateinit var tabs: com.google.android.material.tabs.TabLayout
    private lateinit var scrollSeccion: androidx.core.widget.NestedScrollView
    private lateinit var secciones: SeccionesCurso
    private var courseId = -1L

    /** Al volver de un elemento (leído / me gusta) se refresca el contenido. */
    private val abrirElemento = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == ElementoActivity.RESULT_CAMBIO) secciones.invalidarContenido() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course_detail)

        val courseId          = intent.getLongExtra(EXTRA_COURSE_ID, -1L)
        val courseName        = intent.getStringExtra(EXTRA_COURSE_NAME) ?: "Curso"
        val courseColor       = intent.getStringExtra(EXTRA_COURSE_COLOR) ?: "#00897B"
        val courseThumbnail   = intent.getStringExtra(EXTRA_COURSE_THUMBNAIL)
        val numActividades    = intent.getIntExtra(EXTRA_COURSE_ACTIVIDADES, 0)
        val numForos          = intent.getIntExtra(EXTRA_COURSE_FOROS, 0)
        val numIntegrantes    = intent.getIntExtra(EXTRA_COURSE_INTEGRANTES, 0)

        if (courseId == -1L) {
            finish()
            return
        }
        this.courseId = courseId

        apiClient = EminusApiClient(SessionManager(this))

        inicializarVistas()
        configurarHeader(courseName, courseColor, courseThumbnail)
        configurarRecyclerView()
        configurarPestanas(numActividades, numIntegrantes)
        cargarActividades(courseId)
    }

    private fun inicializarVistas() {
        collapsingToolbar = findViewById(R.id.collapsingToolbar)
        toolbar           = findViewById(R.id.detailToolbar)
        headerBg          = findViewById(R.id.detailHeaderBg)
        imgThumbnail      = findViewById(R.id.imgDetailThumbnail)
        swipeRefresh      = findViewById(R.id.detailSwipeRefresh)
        rvActivities      = findViewById(R.id.rvActivities)
        progressActivities = findViewById(R.id.progressActivities)
        layoutEmpty       = findViewById(R.id.layoutDetailEmpty)
        txtEmpty          = findViewById(R.id.txtDetailEmpty)
        tabs              = findViewById(R.id.tabsCurso)
        scrollSeccion     = findViewById(R.id.scrollSeccion)

        // Botón de navegación atrás
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Sistema de insets para el AppBar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
    }

    private fun configurarHeader(nombre: String, colorHex: String, thumbnailUrl: String?) {
        collapsingToolbar.title = com.pako.eminus4.util.NombreMateria.bonito(nombre)
        collapsingToolbar.setContentScrimColor(
            try { Color.parseColor(colorHex) } catch (_: Exception) { ContextCompat.getColor(this, R.color.brand_uv_green) }
        )

        try {
            headerBg.setBackgroundColor(Color.parseColor(colorHex))
        } catch (_: Exception) {
            headerBg.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_uv_green))
        }

        if (!thumbnailUrl.isNullOrBlank()) {
            imgThumbnail.visibility = View.VISIBLE
            imgThumbnail.load(thumbnailUrl) { crossfade(true) }
        }
    }

    /** Actividades · Contenido · Miembros · Seguimiento (como las secciones de Eminus web). */
    private fun configurarPestanas(actividades: Int, integrantes: Int) {
        secciones = SeccionesCurso(this, apiClient, courseId, findViewById(R.id.contenedorSeccion),
            alTerminar = { swipeRefresh.isRefreshing = false },
            abrirElemento = { e, unidad ->
                abrirElemento.launch(ElementoActivity.intent(this, courseId, unidad, e))
            },
            abrirActividad = { t, lista ->
                val conId = lista.filter { it.id > 0 }
                abrirDetalleActividad(t.id, t.titulo.orEmpty(),
                    conId.map { it.id }.toLongArray(), conId.map { it.titulo.orEmpty() }.toTypedArray())
            })
        listOf(
            if (actividades > 0) "Actividades $actividades" else "Actividades",
            "Contenido",
            if (integrantes > 0) "Miembros $integrantes" else "Miembros",
            "Seguimiento"
        ).forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) = mostrarPestana(tab.position)
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (tab.position == 0) rvActivities.smoothScrollToPosition(0) else scrollSeccion.smoothScrollTo(0, 0)
            }
        })
        // Deslizar para recargar debe respetar la lista que está visible
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            if (tabs.selectedTabPosition == 0) rvActivities.canScrollVertically(-1) else scrollSeccion.canScrollVertically(-1)
        }
    }

    /** Detalle de actividad (desde Actividades o desde Seguimiento). */
    private fun abrirDetalleActividad(id: Long, titulo: String, ids: LongArray, titulos: Array<String>) {
        val index = ids.indexOf(id)
        startActivity(Intent(this, ActivityDetailActivity::class.java).apply {
            putExtra(ActivityDetailActivity.EXTRA_COURSE_ID, courseId)
            putExtra(ActivityDetailActivity.EXTRA_COURSE_NAME, intent.getStringExtra(EXTRA_COURSE_NAME) ?: "Curso")
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_ID, id)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLE, titulo)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_IDS, ids)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLES, titulos)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_INDEX, if (index >= 0) index else 0)
        })
    }

    private fun seccionDe(pos: Int) = when (pos) {
        1 -> SeccionesCurso.Seccion.CONTENIDO
        2 -> SeccionesCurso.Seccion.MIEMBROS
        else -> SeccionesCurso.Seccion.SEGUIMIENTO
    }

    private fun mostrarPestana(pos: Int, recargar: Boolean = false) {
        val esActividades = pos == 0
        rvActivities.visibility = if (esActividades) View.VISIBLE else View.GONE
        scrollSeccion.visibility = if (esActividades) View.GONE else View.VISIBLE
        layoutEmpty.visibility = View.GONE
        progressActivities.visibility = View.GONE
        if (esActividades) {
            if (recargar || activityAdapter.currentList.isEmpty()) cargarActividades(courseId)
            else layoutEmpty.visibility = View.GONE
        } else {
            scrollSeccion.scrollTo(0, 0)
            secciones.mostrar(seccionDe(pos), recargar)
        }
    }

    private fun configurarRecyclerView() {
        activityAdapter = ActivityAdapter { actividad ->
            val actividades = activityAdapter.currentList
            val index = actividades.indexOfFirst { it.idActividad == actividad.idActividad }
            val ids    = actividades.map { it.idActividad }.toLongArray()
            val titles = actividades.map { it.nombre }.toTypedArray()

            val intent = Intent(this, ActivityDetailActivity::class.java).apply {
                putExtra(ActivityDetailActivity.EXTRA_COURSE_ID, intent.getLongExtra(EXTRA_COURSE_ID, 0L))
                putExtra(ActivityDetailActivity.EXTRA_COURSE_NAME, intent.getStringExtra(EXTRA_COURSE_NAME) ?: "Curso")
                putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_ID, actividad.idActividad)
                putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLE, actividad.nombre)
                putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_IDS, ids)
                putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLES, titles)
                putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_INDEX, if (index >= 0) index else 0)
            }
            startActivity(intent)
        }
        rvActivities.layoutManager = LinearLayoutManager(this)
        rvActivities.adapter = activityAdapter

        swipeRefresh.setOnRefreshListener { mostrarPestana(tabs.selectedTabPosition, recargar = true) }
    }

    private fun cargarActividades(idCurso: Long) {
        if (!swipeRefresh.isRefreshing) {
            progressActivities.visibility = View.VISIBLE
        }
        layoutEmpty.visibility = View.GONE

        apiClient.getActivities(idCurso) { result ->
            progressActivities.visibility = View.GONE
            swipeRefresh.isRefreshing = false

            if (tabs.selectedTabPosition != 0) return@getActivities
            result.onSuccess { actividades ->
                activityAdapter.submitList(actividades)
                if (actividades.isEmpty()) {
                    layoutEmpty.visibility = View.VISIBLE
                    txtEmpty.text = "Este curso no tiene actividades registradas"
                }
            }.onFailure { error ->
                layoutEmpty.visibility = View.VISIBLE
                txtEmpty.text = "No se pudieron cargar las actividades.\n${error.localizedMessage}"
                Toast.makeText(this, "Error: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
