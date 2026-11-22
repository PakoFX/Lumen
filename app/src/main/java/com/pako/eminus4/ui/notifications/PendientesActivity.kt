package com.pako.eminus4.ui.notifications

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.pako.eminus4.R
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.EstadoAlumno
import com.pako.eminus4.ui.courses.ActividadCardBinder
import com.pako.eminus4.ui.exams.ExamsActivity
import com.pako.eminus4.ui.forums.ForumDetailActivity
import com.pako.eminus4.ui.messages.MessageDetailActivity
import com.pako.eminus4.util.NombreMateria
import com.pako.eminus4.util.Plazos
import java.util.concurrent.TimeUnit

/**
 * Pendientes desglosados (pantalla aparte, no un filtro de Avisos):
 *  - Sin materia: cada materia con lo que tiene pendiente (tareas, exámenes, foros, mensajes, en revisión).
 *  - Con materia: solo esa, sección por sección.
 *  - Enfoque: todo / solo lo que vence pronto / solo lo que está en revisión.
 */
class PendientesActivity : AppCompatActivity() {

    enum class Enfoque { TODO, URGENTE, REVISION }

    companion object {
        private const val EXTRA_CURSO = "idCurso"
        private const val EXTRA_ENFOQUE = "enfoque"
        val HORAS_URGENTE = TimeUnit.HOURS.toMillis(96)

        fun start(ctx: Context, idCurso: Long? = null, enfoque: Enfoque = Enfoque.TODO) {
            ctx.startActivity(Intent(ctx, PendientesActivity::class.java).apply {
                putExtra(EXTRA_CURSO, idCurso ?: -1L)
                putExtra(EXTRA_ENFOQUE, enfoque.name)
            })
        }

        fun esUrgente(a: CourseActivityItem, ahora: Long = System.currentTimeMillis()) =
            a.activity.terminoMillis?.let { it - ahora in 0..HORAS_URGENTE } == true
    }

    private lateinit var contenedor: LinearLayout
    private lateinit var enfoque: Enfoque
    private var idCurso: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pendientes)
        contenedor = findViewById(R.id.contenedorPendientes)
        idCurso = intent.getLongExtra(EXTRA_CURSO, -1L).takeIf { it != -1L }
        enfoque = runCatching { Enfoque.valueOf(intent.getStringExtra(EXTRA_ENFOQUE) ?: "") }.getOrDefault(Enfoque.TODO)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarPendientes)
        toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootPendientes)) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); ins
        }
        val curso = AvisosDatos.cursos.firstOrNull { it.idCurso == idCurso }
        toolbar.title = when {
            curso != null -> NombreMateria.bonito(curso.getNombreLimpio())
            enfoque == Enfoque.URGENTE -> "Vencen pronto"
            enfoque == Enfoque.REVISION -> "En revisión"
            else -> "Pendientes"
        }
        toolbar.subtitle = if (curso != null) "Pendientes de la materia" else "Por materia"
    }

    override fun onResume() {
        super.onResume()
        pintar()   // al volver de entregar algo, refleja lo último que cargó Avisos
    }

    private fun pintar() {
        contenedor.removeAllViews()
        if (!AvisosDatos.hayDatos) {
            vacio("Abre Avisos para cargar tus pendientes.")
            return
        }
        val cursos = AvisosDatos.cursos.filter { idCurso == null || it.idCurso == idCurso }
        var algo = false
        cursos.sortedBy { it.getNombreLimpio() }.forEach { c -> if (pintarMateria(c, mostrarNombre = idCurso == null)) algo = true }
        if (!algo) vacio(when (enfoque) {
            Enfoque.URGENTE -> "Nada vence en los próximos 3 días."
            Enfoque.REVISION -> "No tienes entregas esperando calificación."
            Enfoque.TODO -> if (idCurso != null) "Estás al día en esta materia." else "Estás al día en todas tus materias."
        })
    }

    /** Devuelve false si la materia no tiene nada que mostrar con el enfoque actual. */
    private fun pintarMateria(c: Course, mostrarNombre: Boolean): Boolean {
        val ahora = System.currentTimeMillis()
        val acts = AvisosDatos.actividades.filter { it.courseId == c.idCurso }
        val porHacer = acts.filter { it.activity.estadoAlumno.esPendiente }
            .filter { enfoque != Enfoque.URGENTE || esUrgente(it, ahora) }
            .sortedBy { it.activity.terminoMillis ?: Long.MAX_VALUE }
        val revision = acts.filter { it.activity.estadoAlumno == EstadoAlumno.ESPERANDO }
        val todo = enfoque == Enfoque.TODO
        val examenes = if (todo) AvisosDatos.examenesDisponibles.filter { it.idCurso == c.idCurso } else emptyList()
        val foros = if (todo) AvisosDatos.forosNuevos.filter { it.idCurso == c.idCurso } else emptyList()
        val mensajes = if (todo) AvisosDatos.mensajesNoLeidos.filter { AvisosDatos.esDeMateria(it, c) } else emptyList()
        val enRevision = if (enfoque != Enfoque.URGENTE) revision else emptyList()
        val listaPorHacer = if (enfoque != Enfoque.REVISION) porHacer else emptyList()

        if (listaPorHacer.isEmpty() && examenes.isEmpty() && foros.isEmpty() && mensajes.isEmpty() && enRevision.isEmpty()) return false

        if (mostrarNombre) encabezadoMateria(c, listaPorHacer.size + examenes.size + foros.size + mensajes.size)

        if (listaPorHacer.isNotEmpty()) {
            seccion(if (enfoque == Enfoque.URGENTE) "VENCEN PRONTO" else "POR HACER", listaPorHacer.size)
            listaPorHacer.forEach { item ->
                val card = layoutInflater.inflate(R.layout.item_all_activity, contenedor, false)
                ActividadCardBinder.bind(card, item) { ActividadCardBinder.abrirDetalle(this, it) }
                contenedor.addView(card)
            }
        }
        if (examenes.isNotEmpty()) {
            seccion("EXÁMENES DISPONIBLES", examenes.size)
            examenes.forEach { ex ->
                fila(R.drawable.ic_quiz, R.color.brand_uv_green, ex.titulo,
                    Plazos.plazo(Plazos.parseIso(ex.fechaTermino)).texto, null) { ExamsActivity.start(this) }
            }
        }
        if (foros.isNotEmpty()) {
            seccion("FOROS CON APORTES NUEVOS", foros.size)
            foros.forEach { f ->
                fila(R.drawable.ic_forum, R.color.badge_info_text, f.titulo, "",
                    "${f.nuevosComentarios} nuevo${if (f.nuevosComentarios == 1) "" else "s"}") {
                    ForumDetailActivity.start(this, f.idCurso, f.idForo, f.titulo, f.nombreCurso)
                }
            }
        }
        if (mensajes.isNotEmpty()) {
            seccion("MENSAJES SIN LEER", mensajes.size)
            mensajes.forEach { m ->
                fila(R.drawable.ic_mail, R.color.badge_info_text, m.titulo?.takeIf { it.isNotBlank() } ?: "(Sin asunto)",
                    m.getNombreRemitente(), null) { MessageDetailActivity.start(this, m.idMensaje) }
            }
        }
        if (enRevision.isNotEmpty()) {
            seccion("ESPERANDO CALIFICACIÓN", enRevision.size)
            enRevision.forEach { item ->
                val card = layoutInflater.inflate(R.layout.item_all_activity, contenedor, false)
                ActividadCardBinder.bind(card, item, compacto = true) { ActividadCardBinder.abrirDetalle(this, it) }
                contenedor.addView(card)
            }
        }
        return true
    }

    // ===================== piezas =====================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun encabezadoMateria(c: Course, total: Int) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_section_header, contenedor, false)
        v.findViewById<TextView>(R.id.txtHeaderTitle).apply {
            text = NombreMateria.bonito(c.getNombreLimpio())
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@PendientesActivity, R.color.text_primary))
        }
        v.findViewById<TextView>(R.id.txtHeaderCount).text = if (total > 0) "$total pendiente${if (total == 1) "" else "s"}" else ""
        v.findViewById<View>(R.id.viewHeaderDot)?.let { dot ->
            try { dot.background?.mutate()?.setTint(Color.parseColor(c.getColorHexaLimpio())) } catch (_: Exception) { }
        }
        (v.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(18)
        contenedor.addView(v)
    }

    private fun seccion(titulo: String, n: Int) {
        contenedor.addView(TextView(this).apply {
            text = "$titulo · $n"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@PendientesActivity, R.color.text_secondary))
            setPadding(dp(4), dp(12), 0, dp(4))
        })
    }

    private fun fila(@DrawableRes icono: Int, @ColorRes color: Int, titulo: String, subtitulo: String, valor: String?, onClick: () -> Unit) {
        val f = LayoutInflater.from(this).inflate(R.layout.item_aviso_fila, contenedor, false)
        f.findViewById<ImageView>(R.id.imgFilaIcono).apply {
            setImageResource(icono)
            imageTintList = ContextCompat.getColorStateList(this@PendientesActivity, color)
        }
        f.findViewById<TextView>(R.id.txtFilaTitulo).text = titulo
        f.findViewById<TextView>(R.id.txtFilaSubtitulo).apply {
            text = subtitulo; visibility = if (subtitulo.isBlank()) View.GONE else View.VISIBLE
        }
        f.findViewById<TextView>(R.id.txtFilaValor).apply {
            text = valor.orEmpty(); visibility = if (valor.isNullOrBlank()) View.GONE else View.VISIBLE
            setTextColor(ContextCompat.getColor(this@PendientesActivity, color))
        }
        f.setOnClickListener { onClick() }
        contenedor.addView(f)
    }

    private fun vacio(texto: String) {
        contenedor.addView(TextView(this).apply {
            text = texto
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@PendientesActivity, R.color.text_secondary))
            setPadding(dp(16), dp(64), dp(16), dp(16))
        })
    }
}
