package com.pako.eminus4.ui.horario

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase
import com.pako.eminus4.ui.common.DetalleSheet
import com.pako.eminus4.util.NombreMateria
import com.pako.eminus4.util.Plazos
import java.text.Normalizer
import java.util.Calendar

/**
 * Horario de clases leído de MiUV al iniciar sesión (ver `MiuvSync`).
 * Días de la semana arriba (abre en hoy), "En clase / Siguiente" y las clases del día.
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

    /** `fin`: la que indicó la persona o la del PDF de MiUV; null = desconocida. */
    private data class Bloque(val clase: ClaseHorario, val sesion: SesionClase, val fin: Int?)

    /** Horas de término que indicó la persona, por materia y día (minutos desde medianoche). */
    private val prefsFines by lazy { getSharedPreferences("horario_fines", MODE_PRIVATE) }
    private fun claveFin(c: ClaseHorario, s: SesionClase) =
        "${SessionManager(this).getMatriculaSesion()}|${c.nrc ?: c.materia}|${s.numeroDia}"
    private fun finGuardado(c: ClaseHorario, s: SesionClase): Int? =
        prefsFines.getInt(claveFin(c, s), -1).takeIf { it >= 0 }

    private lateinit var clases: List<ClaseHorario>
    private val colores = mutableMapOf<String, Int>()
    private var diaSel = 1
    private var mostrarLibre: Boolean
        get() = prefsFines.getBoolean("mostrar_libre", false)
        set(v) { prefsFines.edit().putBoolean("mostrar_libre", v).apply() }
    /** Días que se muestran (lunes a viernes siempre; sábado/domingo solo si hay clases). */
    private var dias: List<Int> = emptyList()
    private lateinit var pager: androidx.viewpager2.widget.ViewPager2
    private lateinit var chips: ChipGroup
    private val paginas = PaginasAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_horario)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarHorario)
        toolbar.setNavigationOnClickListener { finish() }
        // Botón de tiempo libre: recuadros negros entre clases (se recuerda)
        toolbar.inflateMenu(R.menu.menu_horario)
        val itemLibre = toolbar.menu.findItem(R.id.action_tiempo_libre)
        fun pintarBotonLibre() { itemLibre.icon?.alpha = if (mostrarLibre) 255 else 110 }
        pintarBotonLibre()
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_recargar_horario) { recargarDeMiuv(); return@setOnMenuItemClickListener true }
            if (item.itemId != R.id.action_tiempo_libre) return@setOnMenuItemClickListener false
            mostrarLibre = !mostrarLibre
            prefsFines.edit().putBoolean("mostrar_libre", mostrarLibre).apply()
            pintarBotonLibre()
            android.widget.Toast.makeText(this, if (mostrarLibre) "Mostrando tiempo libre entre clases" else "Tiempo libre oculto",
                android.widget.Toast.LENGTH_SHORT).show()
            refrescar()
            true
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootHorario)) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); ins
        }

        val sm = SessionManager(this)
        clases = sm.getHorario().orEmpty()
        toolbar.subtitle = sm.getHorarioFecha().takeIf { it > 0 }
            ?.let { "De MiUV · actualizado ${Plazos.haceCuanto(it)}" } ?: "De MiUV"
        asignarColores(sm)

        pager = findViewById(R.id.pagerDias)
        chips = findViewById(R.id.chipsDias)
        if (clases.isEmpty()) {
            chips.visibility = View.GONE
            pager.visibility = View.GONE
            findViewById<TextView>(R.id.txtHorarioSinDatos).visibility = View.VISIBLE
            findViewById<TextView>(R.id.txtHorarioSinDatos).text = (if (sm.getHorario() == null)
                "Tu horario no se pudo traer de MiUV al iniciar sesión."
            else "MiUV no tiene clases registradas en tu horario de este periodo.")
            findViewById<View>(R.id.btnHorarioCargar).apply {
                visibility = View.VISIBLE
                setOnClickListener { recargarDeMiuv() }
            }
            return
        }
        armarDias()
        // Si falta alguna hora de término, reintentar el PDF de MiUV (sirve mientras siga viva la sesión de MiUV)
        val faltan = clases.any { c -> c.sesiones.any { it.fin.isNullOrBlank() } }
        val prefs = getSharedPreferences("horario_fines", MODE_PRIVATE)
        if (faltan && System.currentTimeMillis() - prefs.getLong("ultimo_intento_pdf", 0) > 10 * 60_000L) {
            prefs.edit().putLong("ultimo_intento_pdf", System.currentTimeMillis()).apply()
            com.pako.eminus4.data.HorarioPdf.completarEnSegundoPlano(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Las horas de término llegan después (PDF de MiUV en segundo plano): recargar al volver y al llegar
        com.pako.eminus4.data.HorarioPdf.alActualizar = { recargar() }
        recargar()
    }

    override fun onPause() {
        super.onPause()
        com.pako.eminus4.data.HorarioPdf.alActualizar = null
    }

    private var recargando = false

    /** "Volver a cargar horario": lo trae de nuevo de MiUV (y sus horas de término) y repinta todo. */
    private fun recargarDeMiuv() {
        if (recargando) return
        recargando = true
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarHorario)
        val subtituloAntes = toolbar.subtitle
        toolbar.subtitle = "Cargando de MiUV…"
        findViewById<View>(R.id.btnHorarioCargar).isEnabled = false
        com.pako.eminus4.data.HorarioPdf.recargar(this) { r ->
            recargando = false
            if (isFinishing || isDestroyed) return@recargar
            findViewById<View>(R.id.btnHorarioCargar).isEnabled = true
            toolbar.subtitle = subtituloAntes
            when (r) {
                com.pako.eminus4.data.HorarioPdf.Resultado.OK -> {
                    android.widget.Toast.makeText(this, "Horario actualizado", android.widget.Toast.LENGTH_SHORT).show()
                    recreate()   // cubre también el caso de que antes no hubiera horario
                }
                com.pako.eminus4.data.HorarioPdf.Resultado.SIN_CLASES ->
                    android.widget.Toast.makeText(this, "MiUV no tiene clases registradas en tu horario", android.widget.Toast.LENGTH_LONG).show()
                else -> androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Entrar otra vez a MiUV")
                    .setMessage("Para traer tu horario hay que iniciar sesión de nuevo en MiUV (con tu mismo usuario y contraseña). Puede tardar hasta un minuto.")
                    .setPositiveButton("Iniciar sesión") { _, _ ->
                        startActivity(android.content.Intent(this, com.pako.eminus4.ui.auth.LoginWebViewActivity::class.java).apply {
                            putExtra("RENEWAL_ONLY", true)
                            putExtra("FORZAR_MIUV", true)
                        })
                        finish()
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
            }
        }
    }

    private fun recargar() {
        if (!::clases.isInitialized || clases.isEmpty()) return
        clases = SessionManager(this).getHorario().orEmpty().ifEmpty { clases }
        refrescar()
    }

    /** Vuelve a pintar las páginas (al llegar horas de término o al cambiar una a mano). */
    private fun refrescar() {
        if (::pager.isInitialized) paginas.notifyDataSetChanged()
    }

    /** Una página por día; se desliza entre días. */
    private inner class PaginasAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
        override fun getItemCount() = dias.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                layoutInflater.inflate(R.layout.item_pagina_dia, parent, false)) {}
        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) =
            pintarDia(holder.itemView, dias[position])
    }

    // ===================== días =====================

    private fun armarDias() {
        // Se adapta a quien tiene clases en fin de semana: sábado/domingo aparecen solo si hay clases
        val conClase = clases.flatMap { it.sesiones }.map { it.numeroDia }.filter { it > 0 }.toSet()
        dias = (1..5) + listOf(6, 7).filter { it in conClase }
        val h = hoy()
        diaSel = when {
            h in dias -> h
            else -> dias.firstOrNull { it in conClase } ?: 1   // fin de semana sin clases → lunes
        }
        dias.forEach { d ->
            val n = clases.sumOf { c -> c.sesiones.count { it.numeroDia == d } }
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
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                diaSel = dias[position]
                val chip = (0 until chips.childCount).map { chips.getChildAt(it) as Chip }.firstOrNull { it.tag == diaSel } ?: return
                if (!chip.isChecked) chip.isChecked = true
                (chips.parent as? android.widget.HorizontalScrollView)?.smoothScrollTo(chip.left - 40, 0)
            }
        })
    }

    private fun bloquesDelDia(d: Int): List<Bloque> =
        clases.flatMap { c -> c.sesiones.filter { it.numeroDia == d }.map { s -> Bloque(c, s, finGuardado(c, s) ?: s.finMin) } }
            .sortedBy { it.sesion.inicioMin ?: Int.MAX_VALUE }

    /** Ya pasó: terminó (si se sabe a qué hora) o ya empezó una clase posterior. */
    private fun yaPaso(b: Bloque, todos: List<Bloque>, ahora: Int): Boolean {
        val ini = b.sesion.inicioMin ?: return false
        if (b.fin != null) return ahora > b.fin   // termina a las ##:59: ese minuto aún cuenta
        return todos.any { o -> (o.sesion.inicioMin ?: -1) in (ini + 1)..ahora }
    }

    private fun pintarDia(pagina: View, dia: Int) {
        val lista = pagina.findViewById<LinearLayout>(R.id.listaClases)
        lista.removeAllViews()
        val bloques = bloquesDelDia(dia)
        val esHoy = dia == hoy()
        val ahora = minutoActual()

        pintarAhora(pagina, if (esHoy) bloques else emptyList(), ahora)

        val vacio = pagina.findViewById<TextView>(R.id.txtHorarioVacio)
        if (bloques.isEmpty()) {
            vacio.text = "No tienes clases el ${NOMBRES_DIA[dia]?.lowercase()}."
            vacio.visibility = View.VISIBLE
            return
        }
        vacio.visibility = View.GONE
        bloques.forEachIndexed { idx, b ->
            // Tiempo libre desde que terminó la clase anterior hasta que empieza esta
            if (mostrarLibre && idx > 0) bloques[idx - 1].fin?.let { finPrev ->
                val libreDesde = if (finPrev % 60 == 59) finPrev + 1 else finPrev   // "12:59" → libre desde 13:00
                val ini = b.sesion.inicioMin
                if (ini != null && ini - libreDesde >= 10) lista.addView(recuadroLibre(libreDesde, ini))
            }
            val v = layoutInflater.inflate(R.layout.item_clase, lista, false)
            val s = b.sesion
            val ini = s.inicioMin
            val fin = b.fin
            v.findViewById<TextView>(R.id.txtClaseInicio).text = ini?.let { hhmm(it) } ?: s.hora
            v.findViewById<TextView>(R.id.txtClaseFin).apply {
                text = fin?.let { hhmm(it) }.orEmpty()
                visibility = if (fin == null) View.GONE else View.VISIBLE
            }
            v.findViewById<TextView>(R.id.txtClaseMateria).text = NombreMateria.bonito(b.clase.materia)
            v.findViewById<TextView>(R.id.txtClaseSalon).text = lugar(b.clase, s)
            v.findViewById<TextView>(R.id.txtClaseProfesor).apply {
                text = b.clase.profesor?.let { NombreMateria.bonito(it) }.orEmpty()
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            v.findViewById<View>(R.id.viewClaseColor).setBackgroundColor(colorDe(b.clase))
            val estado = v.findViewById<TextView>(R.id.txtClaseEstado)
            if (esHoy && ini != null) {
                when {
                    fin != null && ahora in ini..fin -> { estado.visibility = View.VISIBLE; estado.text = "Ahora" }
                    yaPaso(b, bloques, ahora) -> v.alpha = 0.55f
                }
            }
            v.setOnClickListener { detalle(b) }
            lista.addView(v)
        }
    }

    /** Tarjeta "En clase" o "Siguiente" (solo hoy). */
    private fun pintarAhora(pagina: View, hoyBloques: List<Bloque>, ahora: Int) {
        val card = pagina.findViewById<MaterialCardView>(R.id.cardAhora)
        val actual = hoyBloques.firstOrNull { b ->
            val i = b.sesion.inicioMin; val f = b.fin
            i != null && f != null && ahora in i..f
        }
        // Sin hora de término: la última que ya empezó (y que no ha sido "rebasada" por otra)
        val empezo = if (actual != null) null else hoyBloques.lastOrNull { b ->
            val i = b.sesion.inicioMin
            i != null && i <= ahora && b.fin == null && ahora - i < 180 && !yaPaso(b, hoyBloques, ahora)
        }
        val siguiente = hoyBloques.firstOrNull { (it.sesion.inicioMin ?: -1) > ahora }
        val b = actual ?: empezo ?: siguiente
        if (b == null) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        val etiqueta = pagina.findViewById<TextView>(R.id.txtAhoraEtiqueta)
        val detalle = pagina.findViewById<TextView>(R.id.txtAhoraDetalle)
        pagina.findViewById<TextView>(R.id.txtAhoraMateria).text = NombreMateria.bonito(b.clase.materia)
        if (actual != null) {
            etiqueta.text = "EN CLASE AHORA"
            detalle.text = listOfNotNull(b.fin?.let { "Termina a las ${hhmm(it)}" }, lugar(b.clase, b.sesion).ifBlank { null })
                .joinToString(" · ")
        } else if (b === empezo) {
            etiqueta.text = "EMPEZÓ A LAS ${hhmm(b.sesion.inicioMin ?: 0)}"
            detalle.text = listOfNotNull(lugar(b.clase, b.sesion).ifBlank { null },
                siguiente?.let { "Después: ${NombreMateria.corto(it.clase.materia)} a las ${hhmm(it.sesion.inicioMin ?: 0)}" })
                .joinToString(" · ")
        } else {
            val falta = (b.sesion.inicioMin ?: ahora) - ahora
            etiqueta.text = if (falta <= 60) "SIGUIENTE · EN $falta MIN" else "SIGUIENTE CLASE"
            detalle.text = listOfNotNull("A las ${hhmm(b.sesion.inicioMin ?: 0)}", lugar(b.clase, b.sesion).ifBlank { null })
                .joinToString(" · ")
        }
        card.setOnClickListener { detalle(b) }
    }

    private fun recuadroLibre(desde: Int, hasta: Int): View {
        val v = layoutInflater.inflate(R.layout.item_tiempo_libre, null, false)
        val min = hasta - desde
        val h = min / 60; val m = min % 60
        v.findViewById<TextView>(R.id.txtLibreDuracion).text = "Tiempo libre · " + when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
        v.findViewById<TextView>(R.id.txtLibreRango).text = "${hhmm(desde)} – ${hhmm(hasta)}"
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
        return v
    }

    // ===================== detalle de una materia =====================

    private fun detalle(b: Bloque) {
        val c = b.clase
        val sheet = DetalleSheet(this)
            .icono(R.drawable.ic_schedule)
            .titulo(NombreMateria.bonito(c.materia))
            .subtitulo(c.profesor?.let { NombreMateria.bonito(it) })
        c.sesiones.sortedWith(compareBy<SesionClase> { it.numeroDia }.thenBy { it.inicioMin ?: 0 }).forEach { s ->
            val fin = finGuardado(c, s) ?: s.finMin
            val rango = s.inicioMin?.let { i -> if (fin != null) "${hhmm(i)} – ${hhmm(fin)}" else "Desde las ${hhmm(i)}" } ?: s.hora
            sheet.fila(R.drawable.ic_event, NOMBRES_DIA[s.numeroDia] ?: s.dia,
                listOfNotNull(rango, s.salon?.takeIf { it.isNotBlank() }?.let { "Salón $it".replace("Salón Salón", "Salón") })
                    .joinToString(" · "))
        }
        c.edificio?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_school, "Edificio", it) }
        c.nrc?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_assignment, "NRC", it) }
        c.creditos?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_star, "Créditos", it) }
        c.modalidad?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_library_books, "Modalidad", NombreMateria.bonito(it)) }
        val dia = NOMBRES_DIA[b.sesion.numeroDia]?.lowercase() ?: "ese día"
        if (b.fin == null) sheet.nota("No se pudo saber a qué hora termina esta clase. Si lo indicas, la app sabrá cuándo estás en clase.")
        sheet.accion(if (b.fin == null) "Indicar hora de término ($dia)" else "Cambiar hora de término ($dia)") { elegirFin(b) }
        if (finGuardado(c, b.sesion) != null) {
            sheet.accionSecundaria("Quitar hora de término") {
                prefsFines.edit().remove(claveFin(c, b.sesion)).apply()
                refrescar()
            }
        }
        sheet.mostrar()
    }

    private fun elegirFin(b: Bloque) {
        val ini = b.sesion.inicioMin ?: 8 * 60
        val propuesta = b.fin ?: (ini + 60)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
            .setHour((propuesta / 60) % 24).setMinute(propuesta % 60)
            .setTitleText("¿A qué hora termina ${NombreMateria.corto(b.clase.materia)}?")
            .build()
        picker.addOnPositiveButtonClickListener {
            val m = picker.hour * 60 + picker.minute
            if (m <= ini) {
                android.widget.Toast.makeText(this, "Debe ser después de las ${hhmm(ini)}", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                prefsFines.edit().putInt(claveFin(b.clase, b.sesion), m).apply()
                refrescar()
            }
        }
        picker.show(supportFragmentManager, "fin")
    }

    // ===================== utilidades =====================

    private fun hhmm(m: Int) = "%d:%02d".format(m / 60, m % 60)

    private fun lugar(c: ClaseHorario, s: SesionClase): String {
        val salon = s.salon?.takeIf { it.isNotBlank() } ?: c.salon?.takeIf { it.isNotBlank() }
        val edif = c.edificio?.takeIf { it.isNotBlank() }?.let { "Edif. $it" }   // código tal cual (A-TEEZ)
        return listOfNotNull(salon?.let { if (it.contains("sal", true)) it else "Salón $it" }, edif).joinToString(" · ")
    }

    private fun clave(t: String) = Normalizer.normalize(t, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Mismo color que el curso en Eminus si se encuentra por nombre; si no, uno de la paleta. */
    private fun asignarColores(sm: SessionManager) {
        val cursos = sm.getCachedCourses()
        clases.forEachIndexed { i, c ->
            val k = clave(c.materia)
            val curso = cursos.firstOrNull { cu ->
                val kc = clave(cu.getNombreLimpio())
                kc.isNotBlank() && k.isNotBlank() && (kc.startsWith(k.take(18)) || k.startsWith(kc.take(18)))
            }
            val color = try { Color.parseColor(curso?.getColorHexaLimpio() ?: PALETA[i % PALETA.size]) }
                        catch (_: Exception) { Color.parseColor(PALETA[i % PALETA.size]) }
            colores[c.materia] = color
        }
    }

    private fun colorDe(c: ClaseHorario) = colores[c.materia] ?: Color.parseColor(PALETA[0])

}
