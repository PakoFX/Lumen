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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.repository.ForumRepository
import com.pako.eminus4.ui.common.SubjectBlocksState
import com.pako.eminus4.util.iconoInicio
import kotlinx.coroutines.launch

/**
 * Foros agrupados por materia: un bloque por materia (activas o pasadas) que se despliega al tocarlo.
 * Solo dibuja el estado; cargar y agrupar vive en [ForumsViewModel] / [ForumBlocks].
 */
class ForumsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ForumsActivity::class.java))
        }
    }

    private val viewModel: ForumsViewModel by viewModels {
        viewModelFactory { initializer { ForumsViewModel(appContainer.forumRepository) } }
    }

    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipActive: Chip
    private lateinit var chipPast: Chip
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerForums: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtEmptyTitle: TextView
    private lateinit var txtEmptySubtitle: TextView
    private lateinit var adapter: ForumsAdapter
    private var renderedPast: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forums)
        bindViews()
        observe()
    }

    private fun bindViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBarLayout)) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
            insets
        }
        toolbar.setNavigationOnClickListener { finish() }

        chipGroupFilter = findViewById(R.id.chipGroupFilter)
        chipActive = findViewById(R.id.chipActivas)
        chipPast = findViewById(R.id.chipPasadas)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerForums = findViewById(R.id.recyclerForums)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { viewModel.load() }

        // El foro se lee y se participa dentro de la app
        adapter = ForumsAdapter({ forum ->
            ForumDetailActivity.start(this, forum.idCurso, forum.idForo, forum.titulo, forum.nombreCurso)
        }, viewModel::toggle)
        recyclerForums.layoutManager = LinearLayoutManager(this)
        recyclerForums.adapter = adapter

        chipGroupFilter.setOnCheckedStateChangeListener { _, _ ->
            val past = chipPast.isChecked
            if (past != viewModel.state.value.showPast) viewModel.showPast(past)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.errors.collect { Toast.makeText(this@ForumsActivity, it, Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    private fun render(state: SubjectBlocksState<ForumRepository.Loaded>) {
        swipeRefresh.isRefreshing = state.loading
        val blocks = ForumsViewModel.blocksOf(state)
        chipActive.text = "Materias activas · ${blocks.activeSubjects}"
        chipPast.text = "Materias pasadas · ${blocks.pastSubjects}"
        if (chipPast.isChecked != state.showPast) (if (state.showPast) chipPast else chipActive).isChecked = true

        val sectionChanged = renderedPast != state.showPast
        renderedPast = state.showPast
        adapter.submitList(blocks.rows) { if (sectionChanged) recyclerForums.scrollToPosition(0) }

        layoutEmpty.visibility = if (blocks.rows.isEmpty() && !state.loading) View.VISIBLE else View.GONE
        txtEmptyTitle.text = if (state.showPast) "Sin foros de materias pasadas" else "No hay foros"
        txtEmptySubtitle.text = if (state.showPast) "Tus materias anteriores no tienen foros."
        else "Tus materias de este periodo no tienen foros de discusión."
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
