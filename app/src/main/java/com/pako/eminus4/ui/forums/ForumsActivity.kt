package com.pako.eminus4.ui.forums

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.util.iconoInicio
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foros agrupados por materia (solo materias que tienen foros), con filtros:
 *  - Estado: Abiertos (predeterminado) / Con aportes nuevos / Todos.
 *  - Materia: "Todas las materias" o una en particular (con su número de foros).
 */
class ForumsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ForumsActivity::class.java))
        }
    }


    private lateinit var apiClient: EminusApiClient

    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerForums: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtEmptyTitle: TextView
    private lateinit var txtEmptySubtitle: TextView

    private val allForums = mutableListOf<ForumItem>()
    private lateinit var adapter: ForumsAdapter
    /** Materias desplegadas (bloques abiertos). */
    private val abiertos = mutableSetOf<Long>()
    private var eligioFiltro = false
    private var cursosPorId: Map<Long, com.pako.eminus4.data.model.Course> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forums)

        apiClient = EminusApiClient(SessionManager(this))

        inicializarVistas()
        cargarForos()
    }

    private fun inicializarVistas() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }
        toolbar.setNavigationOnClickListener { finish() }

        chipGroupFilter = findViewById(R.id.chipGroupFilter)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerForums = findViewById(R.id.recyclerForums)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { cargarForos() }

        // El foro se lee y se participa dentro de la app
        adapter = ForumsAdapter({ forum ->
            ForumDetailActivity.start(this, forum.idCurso, forum.idForo, forum.titulo, forum.nombreCurso)
        }, { id -> alternarBloque(id) })
        recyclerForums.layoutManager = LinearLayoutManager(this)
        recyclerForums.adapter = adapter

        chipGroupFilter.setOnCheckedStateChangeListener { _, _ -> aplicarFiltro(alInicio = true) }
    }

    private fun cargarForos() {
        swipeRefresh.isRefreshing = true

        apiClient.getAllCourses { result ->
            result.onSuccess { allCourses ->
                // Solo cursos que pueden tener foros (vigentes o con foros registrados)
                val courses = allCourses.filter { it.isActivo() || it.foros > 0 }
                cursosPorId = allCourses.associateBy { it.idCurso }
                if (courses.isEmpty()) {
                    swipeRefresh.isRefreshing = false
                    allForums.clear()
                    aplicarFiltro()
                    return@onSuccess
                }

                val nuevos = mutableListOf<ForumItem>()
                val pendingRequests = AtomicInteger(courses.size)
                for (c in courses) {
                    apiClient.getForums(c.idCurso) { forumResult ->
                        forumResult.onSuccess { forums ->
                            for (f in forums) {
                                f.idCurso = c.idCurso
                                f.nombreCurso = c.getNombreLimpio()
                                f.cursoActivo = c.isActivo()
                            }
                            synchronized(nuevos) { nuevos.addAll(forums) }
                        }
                        if (pendingRequests.decrementAndGet() == 0) {
                            swipeRefresh.isRefreshing = false
                            allForums.clear()
                            allForums.addAll(nuevos)
                            aplicarFiltro()
                        }
                    }
                }
            }.onFailure { error ->
                swipeRefresh.isRefreshing = false
                Toast.makeText(this, "Error al cargar cursos: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun alternarBloque(idCurso: Long) {
        if (!abiertos.add(idCurso)) abiertos.remove(idCurso)
        aplicarFiltro()
    }

    private fun esActiva(idCurso: Long) = cursosPorId[idCurso]?.isActivo() ?: true

    /** Bloques por materia (activas o pasadas); al tocar uno se despliegan sus foros. */
    private fun aplicarFiltro(alInicio: Boolean = false) {
        val pasadas = chipGroupFilter.checkedChipId == R.id.chipPasadas
        val porMateria = allForums.groupBy { it.idCurso }
        val nActivas = porMateria.keys.count { esActiva(it) }
        // La primera vez: si no hay nada en materias activas pero sí en pasadas, abrir en pasadas
        if (!eligioFiltro && !pasadas && nActivas == 0 && porMateria.isNotEmpty()) {
            eligioFiltro = true
            findViewById<Chip>(R.id.chipPasadas).isChecked = true   // vuelve a llamar a aplicarFiltro
            return
        }
        if (porMateria.isNotEmpty()) eligioFiltro = true
        findViewById<Chip>(R.id.chipActivas).text = "Materias activas · $nActivas"
        findViewById<Chip>(R.id.chipPasadas).text = "Materias pasadas · ${porMateria.size - nActivas}"

        val grupos = porMateria.filter { (id, _) -> esActiva(id) != pasadas }.toList()
            .sortedWith(compareByDescending<Pair<Long, List<ForumItem>>> { g -> g.second.sumOf { it.nuevosComentarios } }
                .thenByDescending { g -> g.second.count { it.estaAbierto() } }
                .thenBy { it.second.first().nombreCurso })
        val filas = mutableListOf<FilaForo>()
        grupos.forEach { (idCurso, foros) ->
            val nuevos = foros.count { it.nuevosComentarios > 0 }
            val abiertosForo = foros.count { it.estaAbierto() }
            val abierto = idCurso in abiertos || grupos.size == 1
            val resumen = listOfNotNull(
                "${foros.size} foro${if (foros.size == 1) "" else "s"}",
                if (abiertosForo > 0) "$abiertosForo abierto${if (abiertosForo == 1) "" else "s"}" else null
            ).joinToString(" · ")
            filas += FilaForo.Encabezado(com.pako.eminus4.ui.common.DatosBloque(
                idCurso, foros.first().nombreCurso, resumen, cursosPorId[idCurso]?.getColorHexaLimpio(), abierto,
                aviso = if (nuevos > 0) "$nuevos con novedades" else null,
                avisoColor = R.color.badge_info_text))
            if (abierto) foros.sortedWith(compareByDescending<ForumItem> { it.nuevosComentarios }
                .thenByDescending { it.estaAbierto() }
                .thenBy { it.titulo })
                .forEach { filas += FilaForo.Foro(it) }
        }

        adapter.submitList(filas) { if (alInicio) recyclerForums.scrollToPosition(0) }
        layoutEmpty.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        if (pasadas) {
            txtEmptyTitle.text = "Sin foros de materias pasadas"
            txtEmptySubtitle.text = "Tus materias anteriores no tienen foros."
        } else {
            txtEmptyTitle.text = "No hay foros"
            txtEmptySubtitle.text = "Tus materias de este periodo no tienen foros de discusión."
        }
    }
}

/** Fila de la lista de foros: encabezado de materia o foro. */
sealed class FilaForo {
    data class Encabezado(val bloque: com.pako.eminus4.ui.common.DatosBloque) : FilaForo()
    data class Foro(val foro: ForumItem) : FilaForo()
}

class ForumsAdapter(
    private val onItemClick: (ForumItem) -> Unit,
    private val onBloque: (Long) -> Unit
) : ListAdapter<FilaForo, RecyclerView.ViewHolder>(DiffCallback) {

    private companion object {
        const val TIPO_ENCABEZADO = 0
        const val TIPO_FORO = 1

        val DiffCallback = object : DiffUtil.ItemCallback<FilaForo>() {
            override fun areItemsTheSame(a: FilaForo, b: FilaForo) = when {
                a is FilaForo.Encabezado && b is FilaForo.Encabezado -> a.bloque.idCurso == b.bloque.idCurso
                a is FilaForo.Foro && b is FilaForo.Foro -> a.foro.idForo == b.foro.idForo
                else -> false
            }
            override fun areContentsTheSame(a: FilaForo, b: FilaForo) = a == b
        }
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is FilaForo.Encabezado) TIPO_ENCABEZADO else TIPO_FORO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TIPO_ENCABEZADO) {
            com.pako.eminus4.ui.common.BloqueHolder.crear(parent, onBloque)
        } else {
            ForoHolder(inflater.inflate(R.layout.item_forum, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val fila = getItem(position)) {
            is FilaForo.Encabezado -> (holder as com.pako.eminus4.ui.common.BloqueHolder).bind(fila.bloque)
            is FilaForo.Foro -> (holder as ForoHolder).bind(fila.foro)
        }
    }

    inner class ForoHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtCourseBadge: TextView = itemView.findViewById(R.id.txtCourseBadge)
        private val badgeNewComments: TextView = itemView.findViewById(R.id.badgeNewComments)
        private val txtForumTitle: TextView = itemView.findViewById(R.id.txtForumTitle)
        private val txtForumSnippet: TextView = itemView.findViewById(R.id.txtForumSnippet)
        private val txtTotalComments: TextView = itemView.findViewById(R.id.txtTotalComments)
        private val txtPoints: TextView = itemView.findViewById(R.id.txtPoints)

        fun bind(item: ForumItem) {
            val ctx = itemView.context
            // La materia ya va en el encabezado: aquí se muestra si está abierto o cerrado
            val abierto = item.estaAbierto()
            txtCourseBadge.text = if (abierto) "ABIERTO" else "CERRADO"
            txtCourseBadge.setTextColor(ContextCompat.getColor(ctx, if (abierto) R.color.badge_success_text else R.color.badge_neutral_text))
            txtForumTitle.text = item.titulo

            val cleanDesc = HtmlCompat.fromHtml(item.descripcion ?: "", HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            txtForumSnippet.text = if (cleanDesc.isNotBlank()) cleanDesc else "Sin descripción adicional."

            txtTotalComments.text = "${item.totalComentarios} comentario${if (item.totalComentarios == 1) "" else "s"}"
            txtTotalComments.iconoInicio(R.drawable.ic_forum, tamanoDp = 14, separacionDp = 4)

            val valor = item.valor ?: 10.0
            val cal = item.calificacion
            if (cal != null) {
                txtPoints.text = "Calificación: ${"%.1f".format(cal)}"
                txtPoints.setTextColor(ContextCompat.getColor(ctx, R.color.badge_success_text))
            } else {
                txtPoints.text = "${"%.0f".format(valor)} pts"
                txtPoints.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            }

            if (item.nuevosComentarios > 0) {
                badgeNewComments.visibility = View.VISIBLE
                badgeNewComments.text = "${item.nuevosComentarios} nuevo${if (item.nuevosComentarios == 1) "" else "s"}"
            } else {
                badgeNewComments.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
