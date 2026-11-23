package com.pako.eminus4.ui.courses

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.model.Activity
import com.pako.eminus4.domain.SubjectName
import kotlinx.coroutines.launch

/**
 * Dentro de una materia: pestañas Actividades · Contenido · Miembros · Seguimiento (como Eminus web).
 * Solo dibuja [CourseDetailUiState]; cargar vive en [CourseDetailViewModel].
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

    private val courseId by lazy { intent.getLongExtra(EXTRA_COURSE_ID, -1L) }
    private val courseName by lazy { intent.getStringExtra(EXTRA_COURSE_NAME) ?: "Curso" }

    private val viewModel: CourseDetailViewModel by viewModels {
        viewModelFactory {
            initializer {
                CourseDetailViewModel(appContainer.courseSectionsRepository, courseId) {
                    appContainer.sessionStore.getUserId().orEmpty()
                }
            }
        }
    }

    private lateinit var activityAdapter: ActivityAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvActivities: RecyclerView
    private lateinit var progressActivities: ProgressBar
    private lateinit var layoutEmpty: View
    private lateinit var txtEmpty: TextView
    private lateinit var tabs: TabLayout
    private lateinit var scrollSection: NestedScrollView
    private lateinit var sections: SeccionesCurso
    private var shownActivitiesError: String? = null

    /** Al volver de un elemento (leído / me gusta / comentario) se refresca el contenido. */
    private val openElementForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == ElementoActivity.RESULT_CAMBIO) viewModel.invalidateContent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course_detail)
        if (courseId == -1L) {
            finish()
            return
        }
        bindViews()
        setUpHeader(courseName, intent.getStringExtra(EXTRA_COURSE_COLOR) ?: "#00897B", intent.getStringExtra(EXTRA_COURSE_THUMBNAIL))
        setUpActivitiesList()
        setUpTabs(intent.getIntExtra(EXTRA_COURSE_ACTIVIDADES, 0), intent.getIntExtra(EXTRA_COURSE_INTEGRANTES, 0))
        observe()
    }

    private fun bindViews() {
        swipeRefresh = findViewById(R.id.detailSwipeRefresh)
        rvActivities = findViewById(R.id.rvActivities)
        progressActivities = findViewById(R.id.progressActivities)
        layoutEmpty = findViewById(R.id.layoutDetailEmpty)
        txtEmpty = findViewById(R.id.txtDetailEmpty)
        tabs = findViewById(R.id.tabsCurso)
        scrollSection = findViewById(R.id.scrollSeccion)

        val toolbar = findViewById<MaterialToolbar>(R.id.detailToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
            insets
        }
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { viewModel.reload() }
        // Deslizar para recargar debe respetar la lista que está visible
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            if (tabs.selectedTabPosition == 0) rvActivities.canScrollVertically(-1) else scrollSection.canScrollVertically(-1)
        }
    }

    private fun setUpHeader(name: String, colorHex: String, thumbnailUrl: String?) {
        val color = runCatching { Color.parseColor(colorHex) }.getOrDefault(ContextCompat.getColor(this, R.color.brand_uv_green))
        findViewById<CollapsingToolbarLayout>(R.id.collapsingToolbar).apply {
            title = SubjectName.displayName(name)
            setContentScrimColor(color)
        }
        findViewById<View>(R.id.detailHeaderBg).setBackgroundColor(color)
        if (!thumbnailUrl.isNullOrBlank()) {
            findViewById<ImageView>(R.id.imgDetailThumbnail).apply {
                visibility = View.VISIBLE
                load(thumbnailUrl) { crossfade(true) }
            }
        }
    }

    private fun setUpActivitiesList() {
        activityAdapter = ActivityAdapter { activity ->
            val list = activityAdapter.currentList
            openActivityDetail(activity.idActividad, activity.nombre, list.map { it.idActividad }.toLongArray(), list.map { it.nombre }.toTypedArray())
        }
        rvActivities.layoutManager = LinearLayoutManager(this)
        rvActivities.adapter = activityAdapter
    }

    private fun setUpTabs(activities: Int, members: Int) {
        sections = SeccionesCurso(this, courseId, { appContainer.sessionStore.getUserId() }, findViewById(R.id.contenedorSeccion),
            openElement = { e, unit -> openElementForResult.launch(ElementoActivity.intent(this, courseId, unit, e)) },
            openActivity = { item, list ->
                val withId = list.filter { it.id > 0 }
                openActivityDetail(item.id, item.titulo.orEmpty(), withId.map { it.id }.toLongArray(), withId.map { it.titulo.orEmpty() }.toTypedArray())
            })
        listOf(
            if (activities > 0) "Actividades $activities" else "Actividades",
            "Contenido",
            if (members > 0) "Miembros $members" else "Miembros",
            "Seguimiento"
        ).forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                scrollSection.scrollTo(0, 0)
                viewModel.select(CourseTab.entries[tab.position])
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 0) rvActivities.smoothScrollToPosition(0) else scrollSection.smoothScrollTo(0, 0)
            }
        })
    }

    /** Detalle de actividad (desde Actividades o desde Seguimiento), con la lista para pasar a la anterior/siguiente. */
    private fun openActivityDetail(id: Long, title: String, ids: LongArray, titles: Array<String>) {
        val index = ids.indexOf(id)
        startActivity(Intent(this, ActivityDetailActivity::class.java).apply {
            putExtra(ActivityDetailActivity.EXTRA_COURSE_ID, courseId)
            putExtra(ActivityDetailActivity.EXTRA_COURSE_NAME, courseName)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_ID, id)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLE, title)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_IDS, ids)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLES, titles)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_INDEX, if (index >= 0) index else 0)
        })
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    private fun render(state: CourseDetailUiState) {
        val onActivities = state.tab == CourseTab.ACTIVITIES
        rvActivities.visibility = if (onActivities) View.VISIBLE else View.GONE
        scrollSection.visibility = if (onActivities) View.GONE else View.VISIBLE
        if (!state.refreshing) swipeRefresh.isRefreshing = false
        if (onActivities) renderActivities(state.activities) else renderSection(state)
    }

    private fun renderActivities(data: TabData<List<Activity>>) {
        progressActivities.visibility = if (data is TabData.Loading && !swipeRefresh.isRefreshing && activityAdapter.currentList.isEmpty()) View.VISIBLE else View.GONE
        layoutEmpty.visibility = View.GONE
        when (data) {
            is TabData.Loaded -> {
                activityAdapter.submitList(data.data)
                if (data.data.isEmpty()) showEmpty("Este curso no tiene actividades registradas")
            }
            is TabData.Failed -> {
                showEmpty("No se pudieron cargar las actividades.\n${data.reason}")
                if (shownActivitiesError != data.reason) Toast.makeText(this, "Error: ${data.reason}", Toast.LENGTH_SHORT).show()
                shownActivitiesError = data.reason
            }
            else -> Unit
        }
    }

    private fun showEmpty(text: String) {
        layoutEmpty.visibility = View.VISIBLE
        txtEmpty.text = text
    }

    private fun renderSection(state: CourseDetailUiState) {
        progressActivities.visibility = View.GONE
        layoutEmpty.visibility = View.GONE
        when (val data = state.dataOf(state.tab)) {
            TabData.NotLoaded -> Unit
            TabData.Loading -> if (!swipeRefresh.isRefreshing) sections.showLoading()
            is TabData.Failed -> sections.showMessage("${failureTitle(state.tab)}\n${data.reason}")
            is TabData.Loaded -> when (state.tab) {
                CourseTab.CONTENT -> (state.content as? TabData.Loaded)?.let { sections.showContent(it.data) }
                CourseTab.MEMBERS -> (state.members as? TabData.Loaded)?.let { sections.showMembers(it.data) }
                CourseTab.TRACKING -> (state.tracking as? TabData.Loaded)?.let { sections.showTracking(it.data) }
                CourseTab.ACTIVITIES -> Unit
            }
        }
    }

    private fun failureTitle(tab: CourseTab) = when (tab) {
        CourseTab.CONTENT -> "No se pudo cargar el contenido."
        CourseTab.MEMBERS -> "No se pudieron cargar los miembros."
        else -> "No se pudo cargar tu seguimiento."
    }
}
