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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.util.Plazos
import com.pako.eminus4.util.iconoInicio
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exámenes agrupados por materia (solo materias que tienen exámenes), con filtros:
 *  - Estado: Disponibles (predeterminado) / Próximos / Calificados / Todos.
 *  - Materia: "Todas las materias" o una en particular (con su número de exámenes).
 */
class ExamsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ExamsActivity::class.java))
        }

        /** Ya abrió, no ha cerrado y no tiene calificación: se puede presentar. */
        fun estaDisponible(e: ExamItem, ahora: Long = System.currentTimeMillis()) =
            !e.estaCalificado && !e.estaVencido && (Plazos.parseIso(e.fechaInicio) ?: 0L) <= ahora

        /** Todavía no abre. */
        fun esProximo(e: ExamItem, ahora: Long = System.currentTimeMillis()) =
            !e.estaCalificado && (Plazos.parseIso(e.fechaInicio) ?: 0L) > ahora
    }


    private lateinit var apiClient: EminusApiClient

    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerExams: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtEmptyTitle: TextView
    private lateinit var txtEmptySubtitle: TextView

    private val allExams = mutableListOf<ExamItem>()
    private lateinit var adapter: ExamsAdapter
    /** Materias desplegadas (bloques abiertos). */
    private val abiertos = mutableSetOf<Long>()
    private var eligioFiltro = false
    private var cursosPorId: Map<Long, com.pako.eminus4.data.model.Course> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exams)

        apiClient = EminusApiClient(SessionManager(this))

        inicializarVistas()
        cargarExamenes()
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
        recyclerExams = findViewById(R.id.recyclerExams)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { cargarExamenes() }

        adapter = ExamsAdapter({ exam -> mostrarDetalle(exam) }, { id -> alternarBloque(id) })
        recyclerExams.layoutManager = LinearLayoutManager(this)
        recyclerExams.adapter = adapter

        chipGroupFilter.setOnCheckedStateChangeListener { _, _ -> aplicarFiltro(alInicio = true) }
    }

    private fun cargarExamenes() {
        swipeRefresh.isRefreshing = true

        apiClient.getAllCourses { result ->
            result.onSuccess { allCourses ->
                // Buscar en cursos activos y en cursos que tengan exámenes registrados
                val courses = allCourses.filter { it.isActivo() || it.examenes > 0 }
                cursosPorId = allCourses.associateBy { it.idCurso }
                if (courses.isEmpty()) {
                    swipeRefresh.isRefreshing = false
                    allExams.clear()
                    aplicarFiltro()
                    return@onSuccess
                }

                val nuevos = mutableListOf<ExamItem>()
                val pendingRequests = AtomicInteger(courses.size)
                for (c in courses) {
                    apiClient.getExams(c.idCurso) { examResult ->
                        examResult.onSuccess { exams ->
                            for (ex in exams) {
                                ex.idCurso = c.idCurso
                                ex.nombreCurso = c.getNombreLimpio()
                            }
                            synchronized(nuevos) { nuevos.addAll(exams) }
                        }
                        if (pendingRequests.decrementAndGet() == 0) {
                            swipeRefresh.isRefreshing = false
                            allExams.clear()
                            allExams.addAll(nuevos)
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

    /** Bloques por materia (activas o pasadas); al tocar uno se despliegan sus exámenes. */
    private fun aplicarFiltro(alInicio: Boolean = false) {
        val pasadas = chipGroupFilter.checkedChipId == R.id.chipPasadas
        val porMateria = allExams.groupBy { it.idCurso }
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
            .sortedWith(compareByDescending<Pair<Long, List<ExamItem>>> { g -> g.second.count { estaDisponible(it) } }
                .thenBy { it.second.first().nombreCurso })
        val filas = mutableListOf<FilaExamen>()
        grupos.forEach { (idCurso, examenes) ->
            val disp = examenes.count { estaDisponible(it) }
            val prox = examenes.count { esProximo(it) }
            val calif = examenes.count { it.estaCalificado }
            val abierto = idCurso in abiertos || grupos.size == 1
            val resumen = listOfNotNull(
                if (examenes.size == 1) "1 examen" else "${examenes.size} exámenes",
                if (calif > 0) "$calif calificado${if (calif == 1) "" else "s"}" else null,
                if (prox > 0) "$prox próximo${if (prox == 1) "" else "s"}" else null
            ).joinToString(" · ")
            filas += FilaExamen.Encabezado(com.pako.eminus4.ui.common.DatosBloque(
                idCurso, examenes.first().nombreCurso, resumen, cursosPorId[idCurso]?.getColorHexaLimpio(), abierto,
                aviso = if (disp > 0) "$disp disponible${if (disp == 1) "" else "s"}" else null,
                avisoColor = R.color.badge_info_text))
            if (abierto) examenes.sortedWith(compareByDescending<ExamItem> { estaDisponible(it) }
                .thenByDescending { esProximo(it) }
                .thenBy { Plazos.parseIso(it.fechaTermino) ?: Long.MAX_VALUE })
                .forEach { filas += FilaExamen.Examen(it) }
        }

        adapter.submitList(filas) { if (alInicio) recyclerExams.scrollToPosition(0) }
        layoutEmpty.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
        if (pasadas) {
            txtEmptyTitle.text = "Sin exámenes de materias pasadas"
            txtEmptySubtitle.text = "Tus materias anteriores no tienen exámenes registrados."
        } else {
            txtEmptyTitle.text = "No hay exámenes"
            txtEmptySubtitle.text = "Tus materias de este periodo no tienen exámenes."
        }
    }

    /** Detalle con diseño: estado en color, fechas con íconos y puntaje destacado. */
    private fun mostrarDetalle(exam: ExamItem) {
        val valor = "%.0f".format(exam.valor ?: 10.0)
        val sheet = com.pako.eminus4.ui.common.DetalleSheet(this)
            .icono(R.drawable.ic_quiz)
            .titulo(exam.titulo)
            .subtitulo(exam.nombreCurso)
        when {
            exam.estaCalificado -> sheet.estado("Calificado", R.color.badge_success_text)
            exam.estaVencido -> sheet.estado("Concluido", R.color.badge_neutral_text)
            esProximo(exam) -> sheet.estado("Próximo", R.color.badge_warning_text)
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
            val termino = Plazos.parseIso(item.fechaTermino)
            txtCourseBadge.text = when {
                item.estaCalificado -> "CALIFICADO"
                ExamsActivity.esProximo(item) -> "Abre el ${Plazos.fechaCorta(Plazos.parseIso(item.fechaInicio))}".uppercase()
                item.estaVencido -> "CERRADO"
                else -> Plazos.plazo(termino).texto.uppercase()
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
                ExamsActivity.esProximo(item) -> {
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
