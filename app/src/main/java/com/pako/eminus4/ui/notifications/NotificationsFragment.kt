package com.pako.eminus4.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.repository.AgendaRepository.Agenda
import com.pako.eminus4.domain.ExamRules
import com.pako.eminus4.notificaciones.AlarmReminderUpdater
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.domain.StudentStatus
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.ui.courses.ActividadCardBinder
import com.pako.eminus4.ui.exams.ExamsActivity
import com.pako.eminus4.ui.forums.ForumsActivity
import com.pako.eminus4.ui.main.MainActivity
import com.pako.eminus4.ui.messages.MessageDetailActivity
import com.pako.eminus4.ui.messages.MessagesActivity
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.util.iconoInicio
import java.util.concurrent.TimeUnit

/**
 * Pestaña "Avisos" como agenda:
 *  1. Resumen (cuántas tienes por hacer y qué vence pronto).
 *  2. Por hacer: las actividades mismas, ordenadas por fecha límite.
 *  3. Novedades: calificaciones recientes, mensajes sin leer, exámenes disponibles, foros con aportes.
 *  Las secciones vacías no se muestran.
 */
class NotificationsFragment : Fragment() {

    companion object {
        private const val MAX_POR_HACER = 5
        private const val MAX_FILAS = 3
        private const val MAX_NOVEDADES = 8
        private val DIAS_CALIFICACION_RECIENTE = TimeUnit.DAYS.toMillis(14)
        private val HORAS_URGENTE = TimeUnit.HOURS.toMillis(96)  // "vence en 3 días" también cuenta como pronto
    }

    private val viewModel: NotificationsViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = requireContext().appContainer
                NotificationsViewModel(container.agendaRepository, AlarmReminderUpdater(requireContext().applicationContext))
            }
        }
    }

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var txtHoy: TextView
    private lateinit var cardResumen: MaterialCardView
    private lateinit var imgResumen: ImageView
    private lateinit var txtResumenTitulo: TextView
    private lateinit var txtResumenDetalle: TextView

    private lateinit var seccionPorHacer: View
    private lateinit var txtTituloPorHacer: TextView
    private lateinit var layoutPorHacer: LinearLayout
    private lateinit var btnVerMasPorHacer: MaterialButton

    private lateinit var seccionCalificaciones: View
    private lateinit var layoutCalificaciones: LinearLayout
    private lateinit var seccionMensajes: View
    private lateinit var txtTituloMensajes: TextView
    private lateinit var layoutMensajes: LinearLayout
    private lateinit var seccionExamenes: View
    private lateinit var layoutExamenes: LinearLayout
    private lateinit var seccionForos: View
    private lateinit var layoutForos: LinearLayout

    private lateinit var statPorHacer: View
    private lateinit var statUrgentes: View
    private lateinit var statRevision: View
    private lateinit var layoutResumenStats: View
    private lateinit var layoutResumenEstado: View
    private lateinit var txtResumenProximo: TextView
    private lateinit var seccionPorMateria: View
    private lateinit var layoutPorMateria: LinearLayout

    /** Datos cargados (los pone [render] desde el ViewModel). */
    private var agenda: Agenda? = null
    private var examenesDatos: List<ExamItem> = emptyList()
    private var forosDatos: List<ForumItem> = emptyList()
    private var mensajesDatos: List<MessageItem> = emptyList()

    /** Qué fuentes cargaron completas desde Eminus (solo con esas se calculan novedades). */
    private var agendaOk = false
    private var examenesOk = false
    private var forosOk = false
    private var mensajesOk = false

    private lateinit var seccionNovedades: View
    private lateinit var txtTituloNovedades: TextView
    private lateinit var layoutNovedades: LinearLayout


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_notifications, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        inicializarVistas(view)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver de un detalle (p. ej. tras entregar) o en la primera carga
        txtHoy.text = DueDates.todayLong()
        if (!isHidden) viewModel.load() else viewModel.refreshIfStale()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.refreshIfStale()
    }

    /** Copia el estado del ViewModel a lo que usan las funciones de pintado y repinta. */
    private fun render(state: NoticesUiState) {
        swipeRefreshLayout.isRefreshing = state.loading
        val r = state.agenda ?: return
        agenda = r
        agendaOk = state.agendaComplete
        examenesDatos = state.exams?.items.orEmpty()
        examenesOk = state.examsComplete
        forosDatos = state.forums?.items.orEmpty()
        forosOk = state.forumsComplete
        mensajesDatos = state.unreadMessages?.items.orEmpty()
        mensajesOk = state.messagesComplete
        // Contadores del menú: siempre globales
        ponerContador(MainActivity.Contador.ACTIVIDADES, r.activities.count { it.activity.studentStatus.isPending })
        state.unreadMessages?.let { ponerContador(MainActivity.Contador.MENSAJES, it.items.size) }
        state.exams?.let { ponerContador(MainActivity.Contador.EXAMENES, state.availableExams.size) }
        state.forums?.let { ponerContador(MainActivity.Contador.FOROS, state.forumsWithNews.size) }
        repintar()
    }

    private fun inicializarVistas(view: View) {
        // La barra de estado la cubre la barra superior de MainActivity
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        txtHoy = view.findViewById(R.id.txtHoy)
        cardResumen = view.findViewById(R.id.cardResumen)
        imgResumen = view.findViewById(R.id.imgResumen)
        txtResumenTitulo = view.findViewById(R.id.txtResumenTitulo)
        txtResumenDetalle = view.findViewById(R.id.txtResumenDetalle)

        seccionPorHacer = view.findViewById(R.id.seccionPorHacer)
        txtTituloPorHacer = view.findViewById(R.id.txtTituloPorHacer)
        layoutPorHacer = view.findViewById(R.id.layoutPorHacer)
        btnVerMasPorHacer = view.findViewById(R.id.btnVerMasPorHacer)

        seccionCalificaciones = view.findViewById(R.id.seccionCalificaciones)
        layoutCalificaciones = view.findViewById(R.id.layoutCalificaciones)
        seccionMensajes = view.findViewById(R.id.seccionMensajes)
        txtTituloMensajes = view.findViewById(R.id.txtTituloMensajes)
        layoutMensajes = view.findViewById(R.id.layoutMensajes)
        seccionExamenes = view.findViewById(R.id.seccionExamenes)
        layoutExamenes = view.findViewById(R.id.layoutExamenes)
        seccionForos = view.findViewById(R.id.seccionForos)
        layoutForos = view.findViewById(R.id.layoutForos)
        seccionPorMateria = view.findViewById(R.id.seccionPorMateria)
        layoutPorMateria = view.findViewById(R.id.layoutPorMateria)
        seccionNovedades = view.findViewById(R.id.seccionNovedades)
        txtTituloNovedades = view.findViewById(R.id.txtTituloNovedades)
        layoutNovedades = view.findViewById(R.id.layoutNovedades)
        view.findViewById<View>(R.id.btnMarcarVisto).setOnClickListener { marcarNovedadesVistas() }

        txtHoy.text = DueDates.todayLong()

        btnVerMasPorHacer.setOnClickListener { PendientesActivity.start(requireContext()) }
        // Resumen: tres números que abren el desglose
        fun stat(id: Int, etiqueta: String, enfoque: PendientesActivity.Enfoque) =
            view.findViewById<View>(id).apply {
                findViewById<TextView>(R.id.txtStatEtiqueta).text = etiqueta
                setOnClickListener { PendientesActivity.start(requireContext(), enfoque = enfoque) }
            }
        statPorHacer = stat(R.id.statPorHacer, "Por hacer", PendientesActivity.Enfoque.TODO)
        statUrgentes = stat(R.id.statUrgentes, "Vencen pronto", PendientesActivity.Enfoque.URGENTE)
        statRevision = stat(R.id.statRevision, "En revisión", PendientesActivity.Enfoque.REVISION)
        layoutResumenStats = view.findViewById(R.id.layoutResumenStats)
        layoutResumenEstado = view.findViewById(R.id.layoutResumenEstado)
        txtResumenProximo = view.findViewById(R.id.txtResumenProximo)
        // Los accesos a Actividades/Foros/Exámenes viven en el menú lateral y Mensajes en la
        // barra superior (MainActivity); aquí solo se calculan sus contadores.

        swipeRefreshLayout.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.brand_uv_green))
        swipeRefreshLayout.setOnRefreshListener { viewModel.load(force = true) }
    }

    /** Repinta todas las secciones aplicando el filtro de materia. */
    private fun repintar() {
        val r = agenda ?: return
        if (!isAdded || view == null) return
        // Tocar una materia no filtra Avisos: abre Pendientes (pantalla aparte)
        val curso: Course? = null

        pintarNovedades(r, curso)
        val acts = if (curso == null) r.activities else r.activities.filter { it.courseId == curso.idCurso }
        pintarSeccionesAgenda(r, acts, curso)
        pintarPorMateria(r, visible = curso == null)
        pintarSeccionMensajes(if (curso == null) mensajesDatos else mensajesDatos.filter { esDeMateria(it, curso) })
        pintarSeccionExamenes(if (curso == null) examenesDatos else examenesDatos.filter { it.idCurso == curso.idCurso })
        pintarSeccionForos(if (curso == null) forosDatos else forosDatos.filter { it.idCurso == curso.idCurso })
    }

    private fun esDeMateria(m: MessageItem, c: Course) =
        m.curso != null && m.getCursoLimpio().equals(c.getNombreLimpio(), ignoreCase = true)

    // ===================== NOVEDADES DESDE LA ÚLTIMA VISITA =====================

    /** Las fuentes que no cargaron completas se pasan como null (no se comparan). */
    private fun calcularNovedades(r: Agenda) = com.pako.eminus4.notificaciones.Novedades.calcular(
        requireContext(),
        acts = r.activities.takeIf { agendaOk },
        exams = examenesDatos.takeIf { examenesOk },
        foros = forosDatos.takeIf { forosOk },
        msgs = mensajesDatos.takeIf { mensajesOk }
    )

    private fun pintarNovedades(r: Agenda, curso: Course?) {
        val todas = calcularNovedades(r)
        ponerContador(MainActivity.Contador.NOVEDADES, todas.size)
        val lista = if (curso == null) todas else todas.filter {
            it.idCurso == curso.idCurso || (it.mensaje != null && esDeMateria(it.mensaje, curso))
        }
        layoutNovedades.removeAllViews()
        seccionNovedades.visibility = if (lista.isEmpty()) View.GONE else View.VISIBLE
        txtTituloNovedades.text = "NOVEDADES DESDE TU ÚLTIMA VISITA · ${lista.size}"
        val ctx = requireContext()
        lista.take(MAX_NOVEDADES).forEach { n ->
            val a = n.actividad
            when (n.tipo) {
                com.pako.eminus4.notificaciones.Novedades.Tipo.CALIFICADA,
                com.pako.eminus4.notificaciones.Novedades.Tipo.RECALIFICADA -> {
                    val act = a!!.activity
                    val cal = act.calificacion ?: 0.0
                    val max = act.calificacionMax
                    val nota = "${ActividadCardBinder.formatoNota(cal)}/${ActividadCardBinder.formatoNota(max)}"
                    val nueva = n.tipo == com.pako.eminus4.notificaciones.Novedades.Tipo.CALIFICADA
                    agregarFila(layoutNovedades, R.drawable.ic_star, R.color.badge_success_text,
                        if (nueva) "Te calificaron: ${act.nombre}" else "Cambió tu calificación: ${act.nombre}",
                        if (nueva) a.courseName else "${a.courseName} · antes ${ActividadCardBinder.formatoNota(n.antes ?: 0.0)}",
                        nota, ContextCompat.getColor(ctx, if (cal >= max * 0.6) R.color.badge_success_text else R.color.badge_error_text)
                    ) { ActividadCardBinder.abrirDetalle(ctx, a) }
                }
                com.pako.eminus4.notificaciones.Novedades.Tipo.TAREA_NUEVA -> {
                    val act = a!!.activity
                    agregarFila(layoutNovedades, R.drawable.ic_assignment, R.color.brand_uv_green,
                        "Nueva actividad: ${act.nombre}",
                        "${a.courseName} · ${DueDates.dueDate(act.terminoMillis).text.replaceFirstChar { it.lowercase() }}",
                        null, null
                    ) { ActividadCardBinder.abrirDetalle(ctx, a) }
                }
                com.pako.eminus4.notificaciones.Novedades.Tipo.EXAMEN_NUEVO -> {
                    val e = n.examen!!
                    agregarFila(layoutNovedades, R.drawable.ic_quiz, R.color.brand_uv_green,
                        "Nuevo examen: ${e.titulo}",
                        "${e.nombreCurso} · ${DueDates.dueDate(DueDates.parseEminusDate(e.fechaTermino)).text.replace("Vence", "cierra").replace("Venció", "cerró")}",
                        null, null
                    ) { ExamsActivity.start(ctx) }
                }
                com.pako.eminus4.notificaciones.Novedades.Tipo.FORO_NUEVO,
                com.pako.eminus4.notificaciones.Novedades.Tipo.FORO_COMENTARIOS -> {
                    val f = n.foro!!
                    val esNuevo = n.tipo == com.pako.eminus4.notificaciones.Novedades.Tipo.FORO_NUEVO
                    agregarFila(layoutNovedades, R.drawable.ic_forum, R.color.badge_info_text,
                        if (esNuevo) "Nuevo foro: ${f.titulo}" else f.titulo,
                        f.nombreCurso,
                        if (esNuevo) null else "+${n.cantidad}",
                        ContextCompat.getColor(ctx, R.color.badge_info_text)
                    ) { com.pako.eminus4.ui.forums.ForumDetailActivity.start(ctx, f.idCurso, f.idForo, f.titulo, f.nombreCurso) }
                }
                com.pako.eminus4.notificaciones.Novedades.Tipo.MENSAJE -> {
                    val m = n.mensaje!!
                    agregarFila(layoutNovedades, R.drawable.ic_mail, R.color.badge_info_text,
                        "Mensaje: ${m.titulo?.takeIf { it.isNotBlank() } ?: "(Sin asunto)"}",
                        m.getNombreRemitente(), null, null
                    ) { MessageDetailActivity.start(ctx, m.idMensaje) }
                }
            }
        }
        if (lista.size > MAX_NOVEDADES) {
            agregarFila(layoutNovedades, R.drawable.ic_inbox, R.color.text_secondary,
                "y ${lista.size - MAX_NOVEDADES} más", "Marca como visto para ver lo siguiente", null, null) { }
        }
    }

    private fun marcarNovedadesVistas() {
        val r = agenda ?: return
        com.pako.eminus4.notificaciones.Novedades.marcarVisto(
            requireContext(),
            acts = r.activities.takeIf { agendaOk },
            exams = examenesDatos.takeIf { examenesOk },
            foros = forosDatos.takeIf { forosOk },
            msgs = mensajesDatos.takeIf { mensajesOk }
        )
        repintar()
    }

    // ===================== POR MATERIA =====================

    private data class ResumenMateria(
        val curso: Course,
        val porHacer: List<CourseActivityItem>,
        val examenes: Int,
        val foros: Int,
        val mensajes: Int,
        val esperando: Int
    ) {
        val total get() = porHacer.size + examenes + foros + mensajes
    }

    private fun resumenes(r: Agenda): List<ResumenMateria> {
        val examenesDisp = ExamRules.availableByClosing(examenesDatos)
        val forosNuevos = forumsWithNews(forosDatos)
        return r.courses.map { c ->
            val acts = r.activities.filter { it.courseId == c.idCurso }
            ResumenMateria(
                curso = c,
                porHacer = acts.filter { it.activity.studentStatus.isPending }
                    .sortedBy { it.activity.terminoMillis ?: Long.MAX_VALUE },
                examenes = examenesDisp.count { it.idCurso == c.idCurso },
                foros = forosNuevos.count { it.idCurso == c.idCurso },
                mensajes = mensajesDatos.count { esDeMateria(it, c) },
                esperando = acts.count { it.activity.studentStatus == StudentStatus.AWAITING_GRADE }
            )
        }.sortedWith(compareByDescending<ResumenMateria> { it.total }
            .thenBy { it.porHacer.firstOrNull()?.activity?.terminoMillis ?: Long.MAX_VALUE }
            .thenBy { it.curso.getNombreLimpio() })
    }

    /** Tarjetas por materia (solo con "Todas"): color, contadores y lo próximo que vence. */
    private fun pintarPorMateria(r: Agenda, visible: Boolean) {
        layoutPorMateria.removeAllViews()
        val lista = resumenes(r)
        seccionPorMateria.visibility = if (visible && lista.size > 1) View.VISIBLE else View.GONE
        if (!visible) return
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        lista.forEach { m ->
            val card = inflater.inflate(R.layout.item_materia_resumen, layoutPorMateria, false)
            try {
                card.findViewById<View>(R.id.viewColorMateria)
                    .setBackgroundColor(android.graphics.Color.parseColor(m.curso.getColorHexaLimpio()))
            } catch (_: Exception) { }
            card.findViewById<TextView>(R.id.txtMateria).text = com.pako.eminus4.domain.SubjectName.displayName(m.curso.getNombreLimpio())

            val grupo = card.findViewById<ViewGroup>(R.id.grupoContadores)
            fun pildora(texto: String, @ColorRes color: Int, @DrawableRes icono: Int) {
                grupo.addView(TextView(ctx).apply {
                    text = texto
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(ctx, color))
                    setBackgroundResource(R.drawable.bg_status_pill)
                    val d = resources.displayMetrics.density
                    setPadding((10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt())
                    iconoInicio(icono, ContextCompat.getColor(ctx, color), tamanoDp = 13, separacionDp = 4)
                })
            }
            fun plural(n: Int, uno: String, varios: String) = "$n ${if (n == 1) uno else varios}"
            if (m.porHacer.isNotEmpty()) {
                val urgente = m.porHacer.any { a -> a.activity.terminoMillis?.let { it - System.currentTimeMillis() in 0..HORAS_URGENTE } == true }
                pildora("${m.porHacer.size} por hacer", if (urgente) R.color.badge_error_text else R.color.badge_warning_text, R.drawable.ic_pending_actions)
            }
            if (m.examenes > 0) pildora(plural(m.examenes, "examen", "exámenes"), R.color.brand_uv_green, R.drawable.ic_quiz)
            if (m.foros > 0) pildora(plural(m.foros, "foro nuevo", "foros nuevos"), R.color.badge_info_text, R.drawable.ic_forum)
            if (m.mensajes > 0) pildora(plural(m.mensajes, "mensaje", "mensajes"), R.color.badge_info_text, R.drawable.ic_mail)
            if (m.esperando > 0) pildora("${m.esperando} en revisión", R.color.text_secondary, R.drawable.ic_hourglass_top)
            if (m.total == 0 && m.esperando == 0) pildora("Al día", R.color.badge_success_text, R.drawable.ic_task_alt)

            m.porHacer.firstOrNull()?.let { prox ->
                card.findViewById<TextView>(R.id.txtProximo).apply {
                    visibility = View.VISIBLE
                    text = "Próximo: ${prox.activity.nombre} · ${DueDates.dueDate(prox.activity.terminoMillis).text.replaceFirstChar { it.lowercase() }}"
                }
            }
            card.setOnClickListener { PendientesActivity.start(ctx, m.curso.idCurso) }
            layoutPorMateria.addView(card)
        }
    }

    // ===================== AGENDA (filtrada) =====================

    private fun pintarSeccionesAgenda(r: Agenda, actividades: List<CourseActivityItem>, curso: Course?) {
        val ctx = requireContext()
        val ahora = System.currentTimeMillis()
        val porHacer = actividades
            .filter { it.activity.studentStatus.isPending }
            .sortedBy { it.activity.terminoMillis ?: Long.MAX_VALUE }
        val urgentes = porHacer.count { a ->
            a.activity.terminoMillis?.let { it - ahora in 0..HORAS_URGENTE } == true
        }
        val enEquipo = porHacer.count { it.activity.esPorEquipos }
        val esperando = actividades.count { it.activity.studentStatus == StudentStatus.AWAITING_GRADE }
        val enMateria = curso?.let { " en ${com.pako.eminus4.domain.SubjectName.displayName(it.getNombreLimpio())}" } ?: ""

        // ---- Resumen ----
        val sinDatos = r.courses.isEmpty() && r.activities.isEmpty()
        layoutResumenStats.visibility = if (sinDatos) View.GONE else View.VISIBLE
        layoutResumenEstado.visibility = if (sinDatos) View.VISIBLE else View.GONE
        txtResumenProximo.visibility = if (sinDatos) View.GONE else View.VISIBLE
        if (!sinDatos) {
            fun numero(v: View, n: Int, colorSiHay: Int) {
                v.findViewById<TextView>(R.id.txtStatNumero).apply {
                    text = "$n"
                    setTextColor(ContextCompat.getColor(ctx, if (n > 0) colorSiHay else R.color.text_tertiary))
                }
            }
            numero(statPorHacer, porHacer.size, R.color.badge_warning_text)
            numero(statUrgentes, urgentes, R.color.badge_error_text)
            numero(statRevision, esperando, R.color.badge_info_text)
            val prox = porHacer.firstOrNull()
            txtResumenProximo.text = when {
                prox != null -> "Lo próximo: ${prox.activity.nombre} · ${DueDates.dueDate(prox.activity.terminoMillis).text.replaceFirstChar { it.lowercase() }}"
                else -> "Estás al día: no tienes entregas pendientes."
            } + when {
                r.sessionExpired -> "\nSesión caducada: puede faltar información."
                r.coursesWithErrors > 0 || r.fromCache -> "\nSin conexión: puede faltar información."
                else -> ""
            }
            txtResumenProximo.setOnClickListener {
                if (prox != null) ActividadCardBinder.abrirDetalle(ctx, prox) else PendientesActivity.start(ctx)
            }
            cardResumen.strokeColor = ContextCompat.getColor(ctx, when {
                urgentes > 0 -> R.color.badge_error_text
                porHacer.isNotEmpty() -> R.color.badge_warning_text
                else -> R.color.badge_success_text
            })
        }
        when {
            sinDatos && (r.sessionExpired || r.fromCache) -> {
                ponerIconoResumen(R.drawable.ic_cloud_off, R.color.text_secondary)
                txtResumenTitulo.text = "No se pudo cargar tu agenda"
                txtResumenDetalle.text = if (r.sessionExpired)
                    "Tu sesión de Eminus caducó. Renuévala desde el aviso superior."
                else "Sin conexión con Eminus. Desliza hacia abajo para reintentar."
                cardResumen.strokeColor = ContextCompat.getColor(ctx, R.color.stroke_ok)
            }
            porHacer.isEmpty() -> {
                ponerIconoResumen(R.drawable.ic_task_alt, R.color.badge_success_text)
                txtResumenTitulo.text = "Estás al corriente"
                txtResumenDetalle.text = if (curso == null) "No tienes entregas pendientes en tus cursos activos."
                                         else "No tienes entregas pendientes$enMateria."
                cardResumen.strokeColor = ContextCompat.getColor(ctx, R.color.badge_success_text)
            }
            else -> {
                val n = porHacer.size
                if (urgentes > 0) ponerIconoResumen(R.drawable.ic_local_fire_department, R.color.badge_error_text)
                else ponerIconoResumen(R.drawable.ic_pending_actions, R.color.badge_warning_text)
                txtResumenTitulo.text = "Tienes $n por hacer$enMateria"
                val partes = mutableListOf<String>()
                if (urgentes > 0) {
                    partes += "$urgentes vence${if (urgentes > 1) "n" else ""} en los próximos 3 días"
                } else {
                    val prox = porHacer.first()
                    partes += "Lo próximo: ${prox.activity.nombre} (${DueDates.dueDate(prox.activity.terminoMillis).text.replaceFirstChar { it.lowercase() }})"
                }
                if (enEquipo > 0) partes += "$enEquipo en equipo"
                txtResumenDetalle.text = partes.joinToString(" · ")
                cardResumen.strokeColor = ContextCompat.getColor(
                    ctx, if (urgentes > 0) R.color.badge_error_text else R.color.badge_warning_text
                )
            }
        }
        if (sinDatos && (r.sessionExpired || r.coursesWithErrors > 0 || r.fromCache)) {
            txtResumenDetalle.append(
                if (r.sessionExpired) "\nSesión caducada: puede faltar información."
                else "\nSin conexión: puede faltar información."
            )
        }

        // ---- Por hacer ----
        layoutPorHacer.removeAllViews()
        seccionPorHacer.visibility = if (porHacer.isEmpty()) View.GONE else View.VISIBLE
        txtTituloPorHacer.text = "POR HACER · ${porHacer.size}"
        val inflater = LayoutInflater.from(ctx)
        porHacer.take(MAX_POR_HACER).forEach { item ->
            val card = inflater.inflate(R.layout.item_all_activity, layoutPorHacer, false)
            ActividadCardBinder.bind(card, item) { ActividadCardBinder.abrirDetalle(ctx, it) }
            layoutPorHacer.addView(card)
        }
        if (porHacer.size > MAX_POR_HACER) {
            btnVerMasPorHacer.visibility = View.VISIBLE
            btnVerMasPorHacer.text = "Ver las ${porHacer.size} pendientes"
        } else {
            btnVerMasPorHacer.visibility = View.GONE
        }

        // ---- Calificaciones recientes ----
        val recientes = actividades
            .filter { it.activity.calificacion != null }
            .mapNotNull { a -> DueDates.parseEminusDate(a.activity.fechaRevision)?.let { a to it } }
            .filter { ahora - it.second <= DIAS_CALIFICACION_RECIENTE }
            .sortedByDescending { it.second }
            .take(MAX_FILAS)
        layoutCalificaciones.removeAllViews()
        seccionCalificaciones.visibility = if (recientes.isEmpty()) View.GONE else View.VISIBLE
        recientes.forEach { (item, revision) ->
            val act = item.activity
            val cal = act.calificacion ?: 0.0
            val max = act.calificacionMax
            agregarFila(
                layoutCalificaciones, R.drawable.ic_star, R.color.badge_success_text, act.nombre,
                "${DueDates.timeAgo(revision).replaceFirstChar { it.uppercase() }} · ${item.courseName}",
                "${ActividadCardBinder.formatoNota(cal)}/${ActividadCardBinder.formatoNota(max)}",
                ContextCompat.getColor(ctx, if (cal >= max * 0.6) R.color.badge_success_text else R.color.badge_error_text)
            ) { ActividadCardBinder.abrirDetalle(ctx, item) }
        }
    }

    // ===================== NOVEDADES =====================

    private fun pintarSeccionMensajes(noLeidos: List<MessageItem>) {
        layoutMensajes.removeAllViews()
        seccionMensajes.visibility = if (noLeidos.isEmpty()) View.GONE else View.VISIBLE
        txtTituloMensajes.text = "MENSAJES SIN LEER · ${noLeidos.size}"

        val ctx = requireContext()
        noLeidos.take(MAX_FILAS).forEach { m ->
            val cuando = DueDates.timeAgo(DueDates.parseEminusDate(m.fechaEnviado))
            agregarFila(
                layoutMensajes, R.drawable.ic_mail, R.color.badge_info_text, m.titulo?.takeIf { it.isNotBlank() } ?: "(Sin asunto)",
                listOf(m.getNombreRemitente(), cuando).filter { it.isNotBlank() }.joinToString(" · "),
                null, null
            ) { MessageDetailActivity.start(ctx, m.idMensaje) }
        }
        if (noLeidos.size > MAX_FILAS) {
            agregarFila(layoutMensajes, R.drawable.ic_inbox, R.color.text_secondary, "Ver los ${noLeidos.size} mensajes sin leer", "", null, null) {
                MessagesActivity.start(ctx)
            }
        }
    }

    private fun pintarSeccionExamenes(examenes: List<ExamItem>) {
        val disponibles = ExamRules.availableByClosing(examenes)
        layoutExamenes.removeAllViews()
        seccionExamenes.visibility = if (disponibles.isEmpty()) View.GONE else View.VISIBLE

        val ctx = requireContext()
        disponibles.take(MAX_FILAS).forEach { ex ->
            val p = DueDates.dueDate(DueDates.parseEminusDate(ex.fechaTermino))
            agregarFila(
                layoutExamenes, R.drawable.ic_quiz, R.color.brand_uv_green, ex.titulo, "${ex.nombreCurso} · ${p.text}",
                null, null
            ) { ExamsActivity.start(ctx) }
        }
    }

    private fun pintarSeccionForos(foros: List<ForumItem>) {
        val conNovedades = forumsWithNews(foros)
        layoutForos.removeAllViews()
        seccionForos.visibility = if (conNovedades.isEmpty()) View.GONE else View.VISIBLE

        val ctx = requireContext()
        conNovedades.take(MAX_FILAS).forEach { f ->
            agregarFila(
                layoutForos, R.drawable.ic_forum, R.color.badge_info_text, f.titulo, f.nombreCurso,
                "${f.nuevosComentarios} nuevo${if (f.nuevosComentarios == 1) "" else "s"}",
                ContextCompat.getColor(ctx, R.color.badge_info_text)
            ) { com.pako.eminus4.ui.forums.ForumDetailActivity.start(ctx, f.idCurso, f.idForo, f.titulo, f.nombreCurso) }
        }
    }

    private fun agregarFila(
        contenedor: LinearLayout,
        @DrawableRes icono: Int,
        @ColorRes colorIcono: Int,
        titulo: String,
        subtitulo: String,
        valor: String?,
        colorValor: Int?,
        onClick: () -> Unit
    ) {
        val fila = LayoutInflater.from(requireContext()).inflate(R.layout.item_aviso_fila, contenedor, false)
        fila.findViewById<ImageView>(R.id.imgFilaIcono).apply {
            setImageResource(icono)
            imageTintList = ContextCompat.getColorStateList(requireContext(), colorIcono)
        }
        fila.findViewById<TextView>(R.id.txtFilaTitulo).text = titulo
        fila.findViewById<TextView>(R.id.txtFilaSubtitulo).apply {
            text = subtitulo
            visibility = if (subtitulo.isBlank()) View.GONE else View.VISIBLE
        }
        fila.findViewById<TextView>(R.id.txtFilaValor).apply {
            text = valor ?: ""
            visibility = if (valor.isNullOrBlank()) View.GONE else View.VISIBLE
            colorValor?.let { setTextColor(it) }
        }
        fila.setOnClickListener { onClick() }
        contenedor.addView(fila)
    }

    private fun ponerIconoResumen(@DrawableRes icono: Int, @ColorRes color: Int) {
        imgResumen.setImageResource(icono)
        imgResumen.imageTintList = ContextCompat.getColorStateList(requireContext(), color)
    }

    /** Publica un contador en la navegación (menú lateral / ícono de Mensajes). */
    private fun ponerContador(tipo: MainActivity.Contador, n: Int) {
        (activity as? MainActivity)?.ponerContador(tipo, n)
    }
}
