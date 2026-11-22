package com.pako.eminus4.ui.courses

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.ContentElement
import com.pako.eminus4.data.model.ContentUnit
import com.pako.eminus4.data.model.CourseMember
import com.pako.eminus4.data.model.TrackingDetail
import com.pako.eminus4.data.model.TrackingItem
import com.pako.eminus4.data.model.fraccion
import com.pako.eminus4.ui.common.DetalleSheet
import com.pako.eminus4.ui.messages.ComposeMessageActivity
import com.pako.eminus4.util.Plazos

/**
 * Pestañas Contenido, Miembros y Seguimiento dentro de una materia (pinta en `contenedor`).
 * Cada sección se carga la primera vez que se abre y se guarda; deslizar para recargar la vuelve a pedir.
 */
class SeccionesCurso(
    private val act: AppCompatActivity,
    private val api: EminusApiClient,
    private val idCurso: Long,
    private val contenedor: LinearLayout,
    private val alTerminar: () -> Unit,
    private val abrirElemento: (ContentElement, String?) -> Unit,
    /** Abre el detalle de una actividad del seguimiento (con la lista para pasar a la anterior/siguiente). */
    private val abrirActividad: (TrackingItem, List<TrackingItem>) -> Unit
) {
    enum class Seccion { CONTENIDO, MIEMBROS, SEGUIMIENTO }

    private val inflater = LayoutInflater.from(act)
    private var actual: Seccion? = null

    // Caché por sección
    private var unidades: List<Pair<ContentUnit, List<ContentElement>>>? = null
    private var miembros: List<CourseMember>? = null
    private var seguimiento: Pair<TrackingDetail?, String?>? = null

    fun mostrar(s: Seccion, recargar: Boolean = false) {
        actual = s
        if (recargar) when (s) {
            Seccion.CONTENIDO -> unidades = null
            Seccion.MIEMBROS -> miembros = null
            Seccion.SEGUIMIENTO -> seguimiento = null
        }
        when (s) {
            Seccion.CONTENIDO -> unidades?.let { pintarContenido(it) } ?: cargarContenido()
            Seccion.MIEMBROS -> miembros?.let { pintarMiembros(it) } ?: cargarMiembros()
            Seccion.SEGUIMIENTO -> seguimiento?.let { pintarSeguimiento(it.first, it.second) } ?: cargarSeguimiento()
        }
    }

    /** Tras marcar un elemento como leído / me gusta, se vuelve a pedir el contenido. */
    fun invalidarContenido() { unidades = null; if (actual == Seccion.CONTENIDO) cargarContenido() }

    // ===================== utilidades de pintado =====================

    private fun cargando() {
        contenedor.removeAllViews()
        contenedor.addView(ProgressBar(act).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(48) }
        })
    }

    private fun mensaje(texto: String) {
        contenedor.removeAllViews()
        contenedor.addView(TextView(act).apply {
            text = texto
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(act, R.color.text_secondary))
            setPadding(dp(16), dp(48), dp(16), dp(16))
        })
        alTerminar()
    }

    private fun encabezado(titulo: String, cuenta: String? = null) {
        val v = inflater.inflate(R.layout.item_section_header, contenedor, false)
        v.findViewById<TextView>(R.id.txtHeaderTitle).text = titulo
        v.findViewById<TextView>(R.id.txtHeaderCount).text = cuenta.orEmpty()
        v.findViewById<View>(R.id.viewHeaderDot)?.visibility = View.GONE
        contenedor.addView(v)
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private fun vigente() = !act.isFinishing && !act.isDestroyed

    // ===================== CONTENIDO =====================

    private fun cargarContenido() {
        cargando()
        api.getContentUnits(idCurso) { r ->
            if (!vigente()) return@getContentUnits
            val lista = r.getOrElse { mensaje("No se pudo cargar el contenido.\n${it.message}"); return@getContentUnits }
            // Muchos profes publican directo en la materia, sin unidad: esos elementos solo llegan con
            // getElementos/{idCurso}/0 (aunque la materia también tenga unidades). Van primero, como "General".
            val todas = listOf(ContentUnit(idUnidad = 0, nombre = "General")) + lista
            val resultado = arrayOfNulls<List<ContentElement>>(todas.size)
            var faltan = todas.size
            todas.forEachIndexed { i, u ->
                api.getContentElements(idCurso, u.idUnidad) { re ->
                    resultado[i] = re.getOrDefault(emptyList())
                    if (--faltan == 0 && vigente()) {
                        val datos = todas.mapIndexed { k, un -> un to resultado[k].orEmpty() }
                        unidades = datos
                        if (actual == Seccion.CONTENIDO) pintarContenido(datos)
                    }
                }
            }
        }
    }

    private fun pintarContenido(datos: List<Pair<ContentUnit, List<ContentElement>>>) {
        contenedor.removeAllViews()
        if (datos.all { it.second.isEmpty() }) { mensaje("Tu profe aún no ha publicado contenido en esta materia."); return }
        val conElementos = datos.filter { it.second.isNotEmpty() }
        conElementos.forEach { (u, elementos) ->
            val porLeer = elementos.count { !it.leido }
            // Si todo está en "General" (sin unidades), no hace falta el nombre
            val titulo = if (u.idUnidad == 0L && conElementos.size == 1) "Publicado en la materia" else u.nombre ?: "Unidad"
            encabezado(titulo, if (porLeer > 0) "$porLeer por leer" else "Todo leído")
            elementos.forEach { e -> contenedor.addView(filaElemento(u, e)) }
        }
        alTerminar()
    }

    private fun filaElemento(u: ContentUnit, e: ContentElement): View {
        val v = inflater.inflate(R.layout.item_elemento, contenedor, false)
        v.findViewById<TextView>(R.id.txtElementoTitulo).text = e.nombre ?: "Elemento"
        v.findViewById<TextView>(R.id.txtElementoEstado).apply {
            when {
                e.leido -> { text = "Leído"; setTextColor(color(R.color.badge_success_text)) }
                e.lecturaObligatoria == 1 -> { text = "Obligatoria"; setTextColor(color(R.color.badge_error_text)) }
                else -> { text = "Por leer"; setTextColor(color(R.color.text_secondary)) }
            }
        }
        val resumen = HtmlCompat.fromHtml(e.texto.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            .replace(Regex("\\s+"), " ").trim()
        v.findViewById<TextView>(R.id.txtElementoResumen).apply {
            text = resumen
            visibility = if (resumen.isBlank()) View.GONE else View.VISIBLE
        }
        val nArch = e.archivos.orEmpty().count { it.visible == 1 }
        val nEnl = e.enlacesExternos().size
        val nCom = e.comentariosVisibles().size
        fun plural(n: Int, uno: String, varios: String) = "$n ${if (n == 1) uno else varios}"
        v.findViewById<TextView>(R.id.txtElementoPie).text = listOfNotNull(
            when {
                e.esPaginaWeb -> "🌐 Página web"
                e.idTipoElemento == 2 && nArch > 0 -> "🖼 ${plural(nArch, "imagen", "imágenes")}"
                nArch > 0 -> "📎 ${plural(nArch, "archivo", "archivos")}"
                else -> null
            },
            if (nEnl > 0) "🔗 ${plural(nEnl, "enlace", "enlaces")}" else null,
            if (nCom > 0) "💬 $nCom" else null,
            if (e.likes > 0) "👍 ${e.likes}" else null,
            Plazos.parseIso(e.fechaPublicacion)?.let { Plazos.fechaCorta(it) }
        ).joinToString("  ·  ")
        v.setOnClickListener { abrirElemento(e, if (u.idUnidad == 0L) null else u.nombre) }
        return v
    }

    // ===================== MIEMBROS =====================

    private fun cargarMiembros() {
        cargando()
        api.getCourseMembers(idCurso) { r ->
            if (!vigente()) return@getCourseMembers
            val lista = r.getOrElse { mensaje("No se pudieron cargar los miembros.\n${it.message}"); return@getCourseMembers }
            miembros = lista
            if (actual == Seccion.MIEMBROS) pintarMiembros(lista)
        }
    }

    private fun pintarMiembros(lista: List<CourseMember>) {
        contenedor.removeAllViews()
        if (lista.isEmpty()) { mensaje("No hay miembros que mostrar."); return }
        val (profes, alumnos) = lista.partition { it.esProfe }
        if (profes.isNotEmpty()) {
            encabezado(if (profes.size == 1) "Profesor" else "Profesores", "${profes.size}")
            profes.sortedBy { it.nombreCompleto() }.forEach { contenedor.addView(filaMiembro(it)) }
        }
        if (alumnos.isNotEmpty()) {
            encabezado("Estudiantes", "${alumnos.size}")
            alumnos.sortedBy { it.nombreCompleto() }.forEach { contenedor.addView(filaMiembro(it)) }
        }
        alTerminar()
    }

    private fun ultimoAccesoTexto(iso: String?): String? =
        Plazos.parseIso(iso)?.let { "Último acceso ${Plazos.haceCuanto(it)}" }

    private fun filaMiembro(m: CourseMember): View {
        val v = inflater.inflate(R.layout.item_miembro, contenedor, false)
        v.findViewById<TextView>(R.id.txtMiembroNombre).text = m.nombreCompleto()
        v.findViewById<TextView>(R.id.txtMiembroDetalle).text =
            listOfNotNull(if (m.esProfe) m.tipoPerfil else null, ultimoAccesoTexto(m.ultimoAcceso)).joinToString(" · ")
        v.findViewById<ImageView>(R.id.imgMiembro).load(m.fotoUrl()) {
            crossfade(true)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.avatar_vacio)
            error(R.drawable.avatar_vacio)
        }
        v.setOnClickListener { detalleMiembro(m) }
        return v
    }

    private fun detalleMiembro(m: CourseMember) {
        val yo = SessionManager(act).getUserId()
        val sheet = DetalleSheet(act).icono(if (m.esProfe) R.drawable.ic_school else R.drawable.ic_group)
            .titulo(m.nombreCompleto())
            .subtitulo(m.tipoPerfil)
        m.correo?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_mail, "Correo", it) }
        ultimoAccesoTexto(m.ultimoAcceso)?.let { sheet.fila(R.drawable.ic_schedule, "Actividad", it.replaceFirstChar { c -> c.uppercase() }) }
        if (!m.idUsuario.equals(yo, ignoreCase = true) && !m.idUsuario.isNullOrBlank()) {
            sheet.accion("Escribirle un mensaje") { ComposeMessageActivity.startPara(act, idCurso, m.idUsuario) }
        } else {
            sheet.accion(null, null)
        }
        m.correo?.takeIf { it.isNotBlank() }?.let { correo ->
            sheet.accionSecundaria("Copiar correo") {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Correo", correo))
                Toast.makeText(act, "Correo copiado", Toast.LENGTH_SHORT).show()
            }
        }
        sheet.mostrar()
    }

    // ===================== SEGUIMIENTO =====================

    private fun cargarSeguimiento() {
        cargando()
        val yo = SessionManager(act).getUserId().orEmpty()
        var detalle: TrackingDetail? = null
        var acceso: String? = null
        var error: Throwable? = null
        var faltan = 2
        fun listo() {
            if (--faltan > 0 || !vigente()) return
            if (detalle == null && error != null) { mensaje("No se pudo cargar tu seguimiento.\n${error?.message}"); return }
            seguimiento = detalle to acceso
            if (actual == Seccion.SEGUIMIENTO) pintarSeguimiento(detalle, acceso)
        }
        api.getTracking(idCurso, yo) { r -> detalle = r.getOrNull(); error = r.exceptionOrNull(); listo() }
        api.getLastAccess(idCurso, yo) { r -> acceso = r.getOrNull(); listo() }
    }

    private fun pintarSeguimiento(d: TrackingDetail?, acceso: String?) {
        contenedor.removeAllViews()
        if (d == null) { mensaje("Eminus aún no tiene seguimiento tuyo en esta materia."); return }

        val res = inflater.inflate(R.layout.view_seguimiento_resumen, contenedor, false)
        fun barra(txt: Int, prog: Int, valor: String?) {
            val f = fraccion(valor)
            res.findViewById<TextView>(txt).text = valor?.replace("/", " / ") ?: "—"
            res.findViewById<LinearProgressIndicator>(prog).apply {
                max = 1000
                progress = if (f != null && f.second > 0) ((f.first / f.second) * 1000).toInt().coerceIn(0, 1000) else 0
            }
        }
        barra(R.id.txtSegPuntos, R.id.progSegPuntos, d.totalPuntos)
        barra(R.id.txtSegRecursos, R.id.progSegRecursos, d.totalRecursos)
        res.findViewById<TextView>(R.id.txtSegUltimoAcceso).apply {
            val t = ultimoAccesoTexto(acceso)
            text = t?.let { "Tu ${it.replaceFirstChar { c -> c.lowercase() }}" }.orEmpty()
            visibility = if (t == null) View.GONE else View.VISIBLE
        }
        contenedor.addView(res)

        fun grupo(titulo: String, items: List<TrackingItem>?) {
            if (items.isNullOrEmpty()) return
            val calificadas = items.count { !it.revisado.isNullOrBlank() }
            encabezado(titulo, "$calificadas de ${items.size} calificadas")
            items.forEach { t ->
                val fila = filaSeguimiento(t)
                // Solo las actividades tienen detalle en la app
                if (titulo == "Actividades" && t.id > 0) {
                    fila.isClickable = true
                    fila.isFocusable = true
                    fila.setBackgroundResource(android.util.TypedValue().also {
                        act.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                    }.resourceId)
                    fila.setOnClickListener { abrirActividad(t, items) }
                }
                contenedor.addView(fila)
            }
        }
        grupo("Actividades", d.detalle?.actividades)
        grupo("Foros", d.detalle?.foros)
        grupo("Exámenes", d.detalle?.examenes)
        alTerminar()
    }

    private fun filaSeguimiento(t: TrackingItem): View {
        val v = inflater.inflate(R.layout.item_seg_actividad, contenedor, false)
        v.findViewById<TextView>(R.id.txtSegTitulo).text = t.titulo ?: "—"
        val entregada = Plazos.parseIso(t.realizado)
        val revisada = Plazos.parseIso(t.revisado)
        v.findViewById<TextView>(R.id.txtSegFechas).text = when {
            entregada == null -> "Sin entregar"
            revisada == null -> "Entregada ${Plazos.fechaCorta(entregada)} · sin calificar"
            else -> "Entregada ${Plazos.fechaCorta(entregada)} · calificada ${Plazos.fechaCorta(revisada)}"
        }
        v.findViewById<TextView>(R.id.txtSegValor).apply {
            val valor = t.valor?.replace(" pts.", "")?.replace("pts.", "")?.trim()
            text = if (revisada != null && !valor.isNullOrBlank()) valor else if (entregada == null) "—" else "En revisión"
            val f = fraccion(t.valor)
            setTextColor(color(when {
                revisada == null -> R.color.text_secondary
                f != null && f.second > 0 && f.first / f.second < 0.6 -> R.color.badge_error_text
                else -> R.color.badge_success_text
            }))
        }
        return v
    }
}
