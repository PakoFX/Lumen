package com.pako.eminus4.ui.horario

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.local.SchedulePrefs
import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase
import com.pako.eminus4.domain.ClassBlock
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.domain.NowCard
import com.pako.eminus4.domain.ScheduleDay
import com.pako.eminus4.domain.ScheduleDay.hhmm
import com.pako.eminus4.domain.SubjectName
import com.pako.eminus4.ui.auth.LoginWebViewActivity
import com.pako.eminus4.ui.common.DetalleSheet
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Calendar

/**
 * Horario de clases leído de MiUV al iniciar sesión (ver `MiuvSync`).
 * Días de la semana arriba (abre en hoy), "En clase / Siguiente" y las clases del día.
 * Las reglas están en [ScheduleDay]; los datos y preferencias en [ScheduleViewModel].
 */
class HorarioActivity : AppCompatActivity() {

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, HorarioActivity::class.java))

        private val NOMBRES_DIA = mapOf(1 to "Lunes", 2 to "Martes", 3 to "Miércoles", 4 to "Jueves", 5 to "Viernes", 6 to "Sábado", 7 to "Domingo")
        private val CORTO_DIA = mapOf(1 to "Lun", 2 to "Mar", 3 to "Mié", 4 to "Jue", 5 to "Vie", 6 to "Sáb", 7 to "Dom")

        /** Colores de apoyo si la materia no se encuentra entre los cursos de Eminus. */
        private val PALETA = listOf("#00897B", "#0284C7", "#7C3AED", "#D97706", "#DB2777", "#16A34A", "#DC2626", "#4F46E5")

        /** Hoy en formato 1 = lunes … 7 = domingo. */
        fun hoy(): Int = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3; Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6; else -> 7
        }

        fun minutoActual(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    }

    private val viewModel: ScheduleViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = appContainer
                val prefs = SchedulePrefs(applicationContext) { container.sessionStore.getSessionStudentId() }
                ScheduleViewModel(container.scheduleRepository, prefs, updatedAt = { container.sessionStore.getScheduleUpdatedAt() })
            }
        }
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pager: ViewPager2
    private lateinit var chips: ChipGroup
    private val paginas = PaginasAdapter()

    private var clases: List<ClaseHorario> = emptyList()
    private var dias: List<Int> = emptyList()
    private var diaSel = 1
    private var mostrarLibre = false
    private val colores = mutableMapOf<String, Int>()
    /** Las páginas se arman una sola vez; luego solo se repintan. */
    private var armado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_horario)
        toolbar = findViewById(R.id.toolbarHorario)
        pager = findViewById(R.id.pagerDias)
        chips = findViewById(R.id.chipsDias)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_horario)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_recargar_horario -> { viewModel.reloadFromMiuv(); true }
                R.id.action_tiempo_libre -> { viewModel.toggleFreeTime(); true }
                else -> false
            }
        }
        findViewById<View>(R.id.btnHorarioCargar).setOnClickListener { viewModel.reloadFromMiuv() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootHorario)) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); ins
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    // ===================== estado =====================

    private fun render(state: ScheduleUiState) {
        mostrarLibre = state.showFreeTime
        toolbar.menu.findItem(R.id.action_tiempo_libre)?.icon?.alpha = if (mostrarLibre) 255 else 110
        toolbar.subtitle = when {
            state.reloading -> "Cargando de MiUV…"
            state.updatedAt > 0 -> "De MiUV · actualizado ${DueDates.timeAgo(state.updatedAt)}"
            else -> "De MiUV"
        }
        findViewById<View>(R.id.btnHorarioCargar).isEnabled = !state.reloading

        val nuevas = state.classes.orEmpty()
        if (nuevas.isEmpty()) {
            if (!armado) pintarSinHorario(nunca = state.classes == null)
            return
        }
        clases = nuevas
        if (!armado) {
            armado = true
            asignarColores()
            armarDias()
        } else {
            paginas.notifyDataSetChanged()
        }
    }

    private fun onEvent(event: ScheduleEvent) {
        when (event) {
            ScheduleEvent.Reloaded -> {
                Toast.makeText(this, "Horario actualizado", Toast.LENGTH_SHORT).show()
                recreate()   // cubre también el caso de que antes no hubiera horario
            }
            ScheduleEvent.NoClasses ->
                Toast.makeText(this, "MiUV no tiene clases registradas en tu horario", Toast.LENGTH_LONG).show()
            ScheduleEvent.NeedsLogin -> pedirEntrarAMiuv()
            is ScheduleEvent.FreeTimeToggled -> Toast.makeText(this,
                if (event.visible) "Mostrando tiempo libre entre clases" else "Tiempo libre oculto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pintarSinHorario(nunca: Boolean) {
        chips.visibility = View.GONE
        pager.visibility = View.GONE
        findViewById<TextView>(R.id.txtHorarioSinDatos).apply {
            visibility = View.VISIBLE
            text = if (nunca) "Tu horario no se pudo traer de MiUV al iniciar sesión."
                   else "MiUV no tiene clases registradas en tu horario de este periodo."
        }
        findViewById<View>(R.id.btnHorarioCargar).visibility = View.VISIBLE
    }

    private fun pedirEntrarAMiuv() {
        AlertDialog.Builder(this)
            .setTitle("Entrar otra vez a MiUV")
            .setMessage("Para traer tu horario hay que iniciar sesión de nuevo en MiUV (con tu mismo usuario y contraseña). Puede tardar hasta un minuto.")
            .setPositiveButton("Iniciar sesión") { _, _ ->
                startActivity(Intent(this, LoginWebViewActivity::class.java).apply {
                    putExtra("RENEWAL_ONLY", true)
                    putExtra("FORZAR_MIUV", true)
                })
                finish()
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    // ===================== días =====================

    /** Una página por día; se desliza entre días. */
    private inner class PaginasAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = dias.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.item_pagina_dia, parent, false)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = pintarDia(holder.itemView, dias[position])
    }

    private fun armarDias() {
        val h = hoy()
        dias = ScheduleDay.visibleDays(clases)
        diaSel = ScheduleDay.initialDay(clases, h)
        dias.forEach { d ->
            val n = ScheduleDay.sessionCount(clases, d)
            chips.addView((layoutInflater.inflate(R.layout.item_chip_filtro, chips, false) as Chip).apply {
                id = View.generateViewId()
                tag = d
                text = (if (d == h) "Hoy" else CORTO_DIA[d]) + if (n > 0) " · $n" else ""
                isChecked = d == diaSel
            })
        }
        pager.adapter = paginas
        pager.setCurrentItem(dias.indexOf(diaSel).coerceAtLeast(0), false)
        // Tocar un chip → ir a esa página
        chips.setOnCheckedStateChangeListener { g, ids ->
            val chip = ids.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            diaSel = chip.tag as Int
            val pos = dias.indexOf(diaSel)
            if (pos >= 0 && pager.currentItem != pos) pager.currentItem = pos
        }
        // Deslizar → marcar el chip de ese día (y traerlo a la vista)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                diaSel = dias[position]
                val chip = (0 until chips.childCount).map { chips.getChildAt(it) as Chip }.firstOrNull { it.tag == diaSel } ?: return
                if (!chip.isChecked) chip.isChecked = true
                (chips.parent as? HorizontalScrollView)?.smoothScrollTo(chip.left - 40, 0)
            }
        })
    }

    private fun bloquesDelDia(d: Int) = ScheduleDay.blocksOf(clases, d, viewModel::manualEnd)

    private fun pintarDia(pagina: View, dia: Int) {
        val lista = pagina.findViewById<LinearLayout>(R.id.listaClases)
        lista.removeAllViews()
        val bloques = bloquesDelDia(dia)
        val esHoy = dia == hoy()
        val ahora = minutoActual()

        pintarAhora(pagina, if (esHoy) ScheduleDay.nowCard(bloques, ahora) else null)

        val vacio = pagina.findViewById<TextView>(R.id.txtHorarioVacio)
        if (bloques.isEmpty()) {
            vacio.text = "No tienes clases el ${NOMBRES_DIA[dia]?.lowercase()}."
            vacio.visibility = View.VISIBLE
            return
        }
        vacio.visibility = View.GONE
        bloques.forEachIndexed { idx, b ->
            // Tiempo libre desde que terminó la clase anterior hasta que empieza esta
            if (mostrarLibre && idx > 0) ScheduleDay.freeGap(bloques[idx - 1], b)?.let { lista.addView(recuadroLibre(it)) }
            lista.addView(filaClase(lista, b, bloques, esHoy, ahora))
        }
    }

    private fun filaClase(lista: LinearLayout, b: ClassBlock, bloques: List<ClassBlock>, esHoy: Boolean, ahora: Int): View {
        val v = layoutInflater.inflate(R.layout.item_clase, lista, false)
        val s = b.session
        v.findViewById<TextView>(R.id.txtClaseInicio).text = b.start?.let { hhmm(it) } ?: s.hora
        v.findViewById<TextView>(R.id.txtClaseFin).apply {
            text = b.end?.let { hhmm(it) }.orEmpty()
            visibility = if (b.end == null) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.txtClaseMateria).text = SubjectName.displayName(b.subject.materia)
        v.findViewById<TextView>(R.id.txtClaseSalon).text = ScheduleDay.place(b.subject, s)
        v.findViewById<TextView>(R.id.txtClaseProfesor).apply {
            text = b.subject.profesor?.let { SubjectName.displayName(it) }.orEmpty()
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        v.findViewById<View>(R.id.viewClaseColor).setBackgroundColor(colorDe(b.subject))
        val estado = v.findViewById<TextView>(R.id.txtClaseEstado)
        if (esHoy && b.start != null) {
            when {
                ScheduleDay.isNow(b, ahora) -> { estado.visibility = View.VISIBLE; estado.text = "Ahora" }
                ScheduleDay.hasPassed(b, bloques, ahora) -> v.alpha = 0.55f
            }
        }
        v.setOnClickListener { detalle(b) }
        return v
    }

    /** Tarjeta "En clase" o "Siguiente" (solo hoy). */
    private fun pintarAhora(pagina: View, tarjeta: NowCard?) {
        val card = pagina.findViewById<MaterialCardView>(R.id.cardAhora)
        if (tarjeta == null) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        val b = tarjeta.block
        val lugar = ScheduleDay.place(b.subject, b.session).ifBlank { null }
        val etiqueta = pagina.findViewById<TextView>(R.id.txtAhoraEtiqueta)
        val detalle = pagina.findViewById<TextView>(R.id.txtAhoraDetalle)
        pagina.findViewById<TextView>(R.id.txtAhoraMateria).text = SubjectName.displayName(b.subject.materia)
        when (tarjeta) {
            is NowCard.InClass -> {
                etiqueta.text = "EN CLASE AHORA"
                detalle.text = listOfNotNull(b.end?.let { "Termina a las ${hhmm(it)}" }, lugar).joinToString(" · ")
            }
            is NowCard.Started -> {
                etiqueta.text = "EMPEZÓ A LAS ${hhmm(b.start ?: 0)}"
                detalle.text = listOfNotNull(lugar,
                    tarjeta.next?.let { "Después: ${SubjectName.shortName(it.subject.materia)} a las ${hhmm(it.start ?: 0)}" })
                    .joinToString(" · ")
            }
            is NowCard.Next -> {
                etiqueta.text = if (tarjeta.minutesLeft <= 60) "SIGUIENTE · EN ${tarjeta.minutesLeft} MIN" else "SIGUIENTE CLASE"
                detalle.text = listOfNotNull("A las ${hhmm(b.start ?: 0)}", lugar).joinToString(" · ")
            }
        }
        card.setOnClickListener { detalle(b) }
    }

    private fun recuadroLibre(rango: IntRange): View {
        val v = layoutInflater.inflate(R.layout.item_tiempo_libre, null, false)
        v.findViewById<TextView>(R.id.txtLibreDuracion).text = "Tiempo libre · " + ScheduleDay.duration(rango.last + 1 - rango.first)
        v.findViewById<TextView>(R.id.txtLibreRango).text = "${hhmm(rango.first)} – ${hhmm(rango.last + 1)}"
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
        return v
    }

    // ===================== detalle de una materia =====================

    private fun detalle(b: ClassBlock) {
        val c = b.subject
        val sheet = DetalleSheet(this)
            .icono(R.drawable.ic_schedule)
            .titulo(SubjectName.displayName(c.materia))
            .subtitulo(c.profesor?.let { SubjectName.displayName(it) })
        c.sesiones.sortedWith(compareBy<SesionClase> { it.numeroDia }.thenBy { it.inicioMin ?: 0 }).forEach { s ->
            val fin = viewModel.manualEnd(c, s) ?: s.finMin
            val rango = s.inicioMin?.let { i -> if (fin != null) "${hhmm(i)} – ${hhmm(fin)}" else "Desde las ${hhmm(i)}" } ?: s.hora
            sheet.fila(R.drawable.ic_event, NOMBRES_DIA[s.numeroDia] ?: s.dia,
                listOfNotNull(rango, s.salon?.takeIf { it.isNotBlank() }?.let { "Salón $it".replace("Salón Salón", "Salón") })
                    .joinToString(" · "))
        }
        c.edificio?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_school, "Edificio", it) }
        c.nrc?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_assignment, "NRC", it) }
        c.creditos?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_star, "Créditos", it) }
        c.modalidad?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_library_books, "Modalidad", SubjectName.displayName(it)) }
        val dia = NOMBRES_DIA[b.session.numeroDia]?.lowercase() ?: "ese día"
        if (b.end == null) sheet.nota("No se pudo saber a qué hora termina esta clase. Si lo indicas, la app sabrá cuándo estás en clase.")
        sheet.accion(if (b.end == null) "Indicar hora de término ($dia)" else "Cambiar hora de término ($dia)") { elegirFin(b) }
        if (viewModel.manualEnd(c, b.session) != null) {
            sheet.accionSecundaria("Quitar hora de término") { viewModel.removeManualEnd(c, b.session) }
        }
        sheet.mostrar()
    }

    private fun elegirFin(b: ClassBlock) {
        val ini = b.start ?: 8 * 60
        val propuesta = b.end ?: (ini + 60)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour((propuesta / 60) % 24).setMinute(propuesta % 60)
            .setTitleText("¿A qué hora termina ${SubjectName.shortName(b.subject.materia)}?")
            .build()
        picker.addOnPositiveButtonClickListener {
            val m = picker.hour * 60 + picker.minute
            if (m <= ini) Toast.makeText(this, "Debe ser después de las ${hhmm(ini)}", Toast.LENGTH_SHORT).show()
            else viewModel.setManualEnd(b.subject, b.session, m)
        }
        picker.show(supportFragmentManager, "fin")
    }

    // ===================== colores =====================

    private fun clave(t: String) = Normalizer.normalize(t, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Mismo color que el curso en Eminus si se encuentra por nombre; si no, uno de la paleta. */
    private fun asignarColores() {
        val cursos = appContainer.sessionStore.getCachedCourses()
        clases.forEachIndexed { i, c ->
            val k = clave(c.materia)
            val curso = cursos.firstOrNull { cu ->
                val kc = clave(cu.getNombreLimpio())
                kc.isNotBlank() && k.isNotBlank() && (kc.startsWith(k.take(18)) || k.startsWith(kc.take(18)))
            }
            colores[c.materia] = try { Color.parseColor(curso?.getColorHexaLimpio() ?: PALETA[i % PALETA.size]) }
                                 catch (_: Exception) { Color.parseColor(PALETA[i % PALETA.size]) }
        }
    }

    private fun colorDe(c: ClaseHorario) = colores[c.materia] ?: Color.parseColor(PALETA[0])
}
