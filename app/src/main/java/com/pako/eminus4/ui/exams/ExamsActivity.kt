package com.pako.eminus4.ui.exams

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
import com.pako.eminus4.data.repository.ExamRepository
import com.pako.eminus4.ui.common.SubjectBlocksState
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.domain.ExamRules
import com.pako.eminus4.util.iconoInicio
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Exámenes agrupados por materia: un bloque por materia (activas o pasadas) que se despliega al tocarlo.
 * Solo dibuja el estado; cargar y agrupar vive en [ExamsViewModel] / [ExamBlocks].
 */
class ExamsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ExamsActivity::class.java))
        }
    }

    private val viewModel: ExamsViewModel by viewModels {
        viewModelFactory { initializer { ExamsViewModel(appContainer.examRepository) } }
    }

    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipActive: Chip
    private lateinit var chipPast: Chip
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerExams: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtEmptyTitle: TextView
    private lateinit var txtEmptySubtitle: TextView
    private lateinit var adapter: ExamsAdapter
    private var renderedPast: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exams)
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
        recyclerExams = findViewById(R.id.recyclerExams)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { viewModel.load() }

        adapter = ExamsAdapter(::showDetail, viewModel::toggle)
        recyclerExams.layoutManager = LinearLayoutManager(this)
        recyclerExams.adapter = adapter

        chipGroupFilter.setOnCheckedStateChangeListener { _, _ ->
            val past = chipPast.isChecked
            if (past != viewModel.state.value.showPast) viewModel.showPast(past)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.errors.collect { Toast.makeText(this@ExamsActivity, it, Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    private fun render(state: SubjectBlocksState<ExamRepository.Loaded>) {
        swipeRefresh.isRefreshing = state.loading
        val blocks = ExamsViewModel.blocksOf(state)
        chipActive.text = "Materias activas · ${blocks.activeSubjects}"
        chipPast.text = "Materias pasadas · ${blocks.pastSubjects}"
        if (chipPast.isChecked != state.showPast) (if (state.showPast) chipPast else chipActive).isChecked = true

        val sectionChanged = renderedPast != state.showPast
        renderedPast = state.showPast
        adapter.submitList(blocks.rows) { if (sectionChanged) recyclerExams.scrollToPosition(0) }

        layoutEmpty.visibility = if (blocks.rows.isEmpty() && !state.loading) View.VISIBLE else View.GONE
        txtEmptyTitle.text = if (state.showPast) "Sin exámenes de materias pasadas" else "No hay exámenes"
        txtEmptySubtitle.text = if (state.showPast) "Tus materias anteriores no tienen exámenes registrados."
        else "Tus materias de este periodo no tienen exámenes."
    }

    /** Detalle con diseño: estado en color, fechas con íconos y puntaje destacado. */
    private fun showDetail(exam: ExamItem) {
        val valor = "%.0f".format(exam.valor ?: 10.0)
        val sheet = com.pako.eminus4.ui.common.DetalleSheet(this)
            .icono(R.drawable.ic_quiz)
            .titulo(exam.titulo)
            .subtitulo(exam.nombreCurso)
        when {
            exam.estaCalificado -> sheet.estado("Calificado", R.color.badge_success_text)
            exam.estaVencido -> sheet.estado("Concluido", R.color.badge_neutral_text)
            ExamRules.isUpcoming(exam) -> sheet.estado("Próximo", R.color.badge_warning_text)
            else -> sheet.estado("Disponible", R.color.badge_info_text)
        }
        sheet.fila(R.drawable.ic_event, "Abre", formatDate(exam.fechaInicio))
            .fila(R.drawable.ic_schedule, "Cierra", formatDate(exam.fechaTermino))
        if (exam.calificacion != null) {
            sheet.puntaje("Calificación", "${"%.1f".format(exam.calificacion)} / $valor")
        } else {
            sheet.puntaje("Valor del examen", "$valor pts")
        }
        sheet.nota(
            if (exam.estaVencido || exam.estaCalificado) "Puedes revisar este examen en Eminus."
            else "Los exámenes se presentan en Eminus desde el navegador."
        ).mostrar()
    }

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "Sin fecha"
        return try {
            val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val outputFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("es"))
            val date = inputFmt.parse(iso.substring(0, 19)) ?: return iso
            outputFmt.format(date)
        } catch (_: Exception) { iso }
    }
}

/** Fila de la lista de exámenes: encabezado de materia o examen. */
sealed class FilaExamen {
    data class Encabezado(val bloque: com.pako.eminus4.ui.common.DatosBloque) : FilaExamen()
    data class Examen(val examen: ExamItem) : FilaExamen()
}

class ExamsAdapter(
    private val onItemClick: (ExamItem) -> Unit,
    private val onBloque: (Long) -> Unit
) : ListAdapter<FilaExamen, RecyclerView.ViewHolder>(DiffCallback) {

    private companion object {
        const val TIPO_ENCABEZADO = 0
        const val TIPO_EXAMEN = 1

        val DiffCallback = object : DiffUtil.ItemCallback<FilaExamen>() {
            override fun areItemsTheSame(a: FilaExamen, b: FilaExamen) = when {
                a is FilaExamen.Encabezado && b is FilaExamen.Encabezado -> a.bloque.idCurso == b.bloque.idCurso
                a is FilaExamen.Examen && b is FilaExamen.Examen -> a.examen.idExamen == b.examen.idExamen
                else -> false
            }
            override fun areContentsTheSame(a: FilaExamen, b: FilaExamen) = a == b
        }
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position) is FilaExamen.Encabezado) TIPO_ENCABEZADO else TIPO_EXAMEN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TIPO_ENCABEZADO) {
            com.pako.eminus4.ui.common.BloqueHolder.crear(parent, onBloque)
        } else {
            ExamenHolder(inflater.inflate(R.layout.item_exam, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val fila = getItem(position)) {
            is FilaExamen.Encabezado -> (holder as com.pako.eminus4.ui.common.BloqueHolder).bind(fila.bloque)
            is FilaExamen.Examen -> (holder as ExamenHolder).bind(fila.examen)
        }
    }

    inner class ExamenHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtCourseBadge: TextView = itemView.findViewById(R.id.txtCourseBadge)
        private val badgeStatus: TextView = itemView.findViewById(R.id.badgeStatus)
        private val txtExamTitle: TextView = itemView.findViewById(R.id.txtExamTitle)
        private val txtExamDates: TextView = itemView.findViewById(R.id.txtExamDates)
        private val txtPoints: TextView = itemView.findViewById(R.id.txtPoints)

        fun bind(item: ExamItem) {
            val ctx = itemView.context
            txtExamTitle.text = item.titulo

            // La materia ya va en el encabezado: aquí se muestra cuándo cierra / abre
            val termino = DueDates.parseEminusDate(item.fechaTermino)
            txtCourseBadge.text = when {
                item.estaCalificado -> "CALIFICADO"
                ExamRules.isUpcoming(item) -> "Abre el ${DueDates.shortDate(DueDates.parseEminusDate(item.fechaInicio))}".uppercase()
                item.estaVencido -> "CERRADO"
                else -> DueDates.dueDate(termino).text.uppercase()
            }

            val inicio = formatearFecha(item.fechaInicio)
            val fin = formatearFecha(item.fechaTermino)
            txtExamDates.text = "$inicio — $fin"
            txtExamDates.iconoInicio(R.drawable.ic_event, tamanoDp = 14, separacionDp = 4)

            val valor = item.valor ?: 10.0
            val cal = item.calificacion
            when {
                cal != null -> {
                    badgeStatus.text = "Calificado (${"%.1f".format(cal)})"
                    badgeStatus.setBackgroundResource(R.drawable.bg_pill_active)
                    badgeStatus.setTextColor(android.graphics.Color.WHITE)
                    txtPoints.text = "${"%.1f".format(cal)}/${"%.0f".format(valor)} pts"
                }
                item.estaVencido -> {
                    badgeStatus.text = "Concluido"
                    badgeStatus.setBackgroundResource(R.drawable.bg_pill_closed)
                    badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.badge_neutral_text))
                    txtPoints.text = "${"%.0f".format(valor)} pts"
                }
                ExamRules.isUpcoming(item) -> {
                    badgeStatus.text = "Próximo"
                    badgeStatus.setBackgroundResource(R.drawable.bg_pill_badge)
                    badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.badge_warning_text))
                    txtPoints.text = "${"%.0f".format(valor)} pts"
                }
                else -> {
                    badgeStatus.text = "Disponible"
                    badgeStatus.setBackgroundResource(R.drawable.bg_pill_badge)
                    badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.badge_info_text))
                    txtPoints.text = "${"%.0f".format(valor)} pts"
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun formatearFecha(iso: String?): String {
            if (iso.isNullOrBlank()) return "Sin fecha"
            return try {
                val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val outputFmt = SimpleDateFormat("dd MMM, HH:mm", Locale("es"))
                val date = inputFmt.parse(iso.substring(0, 19)) ?: return iso
                outputFmt.format(date)
            } catch (_: Exception) {
                iso
            }
        }
    }
}
