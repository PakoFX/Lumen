package com.pako.eminus4.ui.courses

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
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
import com.pako.eminus4.data.model.ContentElement
import com.pako.eminus4.data.model.ContentUnit
import com.pako.eminus4.data.model.CourseMember
import com.pako.eminus4.data.model.TrackingItem
import com.pako.eminus4.data.model.fraccion
import com.pako.eminus4.data.repository.CourseSectionsRepository.Tracking
import com.pako.eminus4.data.repository.CourseSectionsRepository.UnitContent
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.ui.common.DetalleSheet
import com.pako.eminus4.ui.messages.ComposeMessageActivity

/**
 * Dibuja las pestañas Contenido, Miembros y Seguimiento de una materia dentro de [container].
 * Solo presentación: los datos llegan ya cargados desde [CourseDetailViewModel].
 */
class SeccionesCurso(
    private val act: AppCompatActivity,
    private val courseId: Long,
    private val myUserId: () -> String?,
    private val container: LinearLayout,
    private val openElement: (ContentElement, String?) -> Unit,
    /** Abre el detalle de una actividad del seguimiento (con la lista para pasar a la anterior/siguiente). */
    private val openActivity: (TrackingItem, List<TrackingItem>) -> Unit
) {
    private val inflater = LayoutInflater.from(act)

    fun showLoading() {
        container.removeAllViews()
        container.addView(ProgressBar(act).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(48) }
        })
    }

    fun showMessage(text: String) {
        container.removeAllViews()
        container.addView(TextView(act).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(16), dp(48), dp(16), dp(16))
        })
    }

    // ===================== CONTENIDO =====================

    fun showContent(units: List<UnitContent>) {
        container.removeAllViews()
        val withElements = units.filter { it.elements.isNotEmpty() }
        if (withElements.isEmpty()) { showMessage("Tu profe aún no ha publicado contenido en esta materia."); return }
        withElements.forEach { (unit, elements) ->
            val unread = elements.count { !it.leido }
            // Si todo está en "General" (sin unidades), no hace falta el nombre
            val title = if (unit.idUnidad == 0L && withElements.size == 1) "Publicado en la materia" else unit.nombre ?: "Unidad"
            header(title, if (unread > 0) "$unread por leer" else "Todo leído")
            elements.forEach { container.addView(elementRow(unit, it)) }
        }
    }

    private fun elementRow(unit: ContentUnit, e: ContentElement): View {
        val v = inflater.inflate(R.layout.item_elemento, container, false)
        v.findViewById<TextView>(R.id.txtElementoTitulo).text = e.nombre ?: "Elemento"
        v.findViewById<TextView>(R.id.txtElementoEstado).apply {
            when {
                e.leido -> { text = "Leído"; setTextColor(color(R.color.badge_success_text)) }
                e.lecturaObligatoria == 1 -> { text = "Obligatoria"; setTextColor(color(R.color.badge_error_text)) }
                else -> { text = "Por leer"; setTextColor(color(R.color.text_secondary)) }
            }
        }
        val summary = HtmlCompat.fromHtml(e.texto.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            .replace(Regex("\\s+"), " ").trim()
        v.findViewById<TextView>(R.id.txtElementoResumen).apply {
            text = summary
            visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.txtElementoPie).text = elementFooter(e)
        v.setOnClickListener { openElement(e, if (unit.idUnidad == 0L) null else unit.nombre) }
        return v
    }

    /** "📎 2 archivos · 🔗 1 enlace · 💬 3 · 👍 5 · 16 nov" */
    private fun elementFooter(e: ContentElement): String {
        val files = e.archivos.orEmpty().count { it.visible == 1 }
        val links = e.enlacesExternos().size
        val comments = e.comentariosVisibles().size
        fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
        return listOfNotNull(
            when {
                e.esPaginaWeb -> "🌐 Página web"
                e.idTipoElemento == 2 && files > 0 -> "🖼 ${plural(files, "imagen", "imágenes")}"
                files > 0 -> "📎 ${plural(files, "archivo", "archivos")}"
                else -> null
            },
            if (links > 0) "🔗 ${plural(links, "enlace", "enlaces")}" else null,
            if (comments > 0) "💬 $comments" else null,
            if (e.likes > 0) "👍 ${e.likes}" else null,
            DueDates.parseEminusDate(e.fechaPublicacion)?.let { DueDates.shortDate(it) }
        ).joinToString("  ·  ")
    }

    // ===================== MIEMBROS =====================

    fun showMembers(members: List<CourseMember>) {
        container.removeAllViews()
        if (members.isEmpty()) { showMessage("No hay miembros que mostrar."); return }
        val (teachers, students) = members.partition { it.esProfe }
        if (teachers.isNotEmpty()) {
            header(if (teachers.size == 1) "Profesor" else "Profesores", "${teachers.size}")
            teachers.sortedBy { it.nombreCompleto() }.forEach { container.addView(memberRow(it)) }
        }
        if (students.isNotEmpty()) {
            header("Estudiantes", "${students.size}")
            students.sortedBy { it.nombreCompleto() }.forEach { container.addView(memberRow(it)) }
        }
    }

    private fun lastAccessText(iso: String?): String? =
        DueDates.parseEminusDate(iso)?.let { "Último acceso ${DueDates.timeAgo(it)}" }

    private fun memberRow(m: CourseMember): View {
        val v = inflater.inflate(R.layout.item_miembro, container, false)
        v.findViewById<TextView>(R.id.txtMiembroNombre).text = m.nombreCompleto()
        v.findViewById<TextView>(R.id.txtMiembroDetalle).text =
            listOfNotNull(if (m.esProfe) m.tipoPerfil else null, lastAccessText(m.ultimoAcceso)).joinToString(" · ")
        v.findViewById<ImageView>(R.id.imgMiembro).load(m.fotoUrl()) {
            crossfade(true)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.avatar_vacio)
            error(R.drawable.avatar_vacio)
        }
        v.setOnClickListener { memberSheet(m) }
        return v
    }

    /** Hoja con correo, último acceso, "Escribirle un mensaje" y "Copiar correo". */
    private fun memberSheet(m: CourseMember) {
        val sheet = DetalleSheet(act).icono(if (m.esProfe) R.drawable.ic_school else R.drawable.ic_group)
            .titulo(m.nombreCompleto())
            .subtitulo(m.tipoPerfil)
        m.correo?.takeIf { it.isNotBlank() }?.let { sheet.fila(R.drawable.ic_mail, "Correo", it) }
        lastAccessText(m.ultimoAcceso)?.let { sheet.fila(R.drawable.ic_schedule, "Actividad", it.replaceFirstChar { c -> c.uppercase() }) }
        if (!m.idUsuario.equals(myUserId(), ignoreCase = true) && !m.idUsuario.isNullOrBlank()) {
            sheet.accion("Escribirle un mensaje") { ComposeMessageActivity.startPara(act, courseId, m.idUsuario) }
        } else {
            sheet.accion(null, null)
        }
        m.correo?.takeIf { it.isNotBlank() }?.let { email ->
            sheet.accionSecundaria("Copiar correo") {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Correo", email))
                Toast.makeText(act, "Correo copiado", Toast.LENGTH_SHORT).show()
            }
        }
        sheet.mostrar()
    }

    // ===================== SEGUIMIENTO =====================

    fun showTracking(tracking: Tracking) {
        container.removeAllViews()
        val detail = tracking.detail ?: run { showMessage("Eminus aún no tiene seguimiento tuyo en esta materia."); return }
        container.addView(trackingSummary(detail.totalPuntos, detail.totalRecursos, tracking.lastAccess))
        trackingGroup("Actividades", detail.detalle?.actividades, clickable = true)
        trackingGroup("Foros", detail.detalle?.foros, clickable = false)
        trackingGroup("Exámenes", detail.detalle?.examenes, clickable = false)
    }

    private fun trackingSummary(points: String?, resources: String?, lastAccess: String?): View {
        val v = inflater.inflate(R.layout.view_seguimiento_resumen, container, false)
        fun bar(textId: Int, progressId: Int, value: String?) {
            val f = fraccion(value)
            v.findViewById<TextView>(textId).text = value?.replace("/", " / ") ?: "—"
            v.findViewById<LinearProgressIndicator>(progressId).apply {
                max = 1000
                progress = if (f != null && f.second > 0) ((f.first / f.second) * 1000).toInt().coerceIn(0, 1000) else 0
            }
        }
        bar(R.id.txtSegPuntos, R.id.progSegPuntos, points)
        bar(R.id.txtSegRecursos, R.id.progSegRecursos, resources)
        v.findViewById<TextView>(R.id.txtSegUltimoAcceso).apply {
            val t = lastAccessText(lastAccess)
            text = t?.let { "Tu ${it.replaceFirstChar { c -> c.lowercase() }}" }.orEmpty()
            visibility = if (t == null) View.GONE else View.VISIBLE
        }
        return v
    }

    /** Solo las actividades tienen detalle en la app. */
    private fun trackingGroup(title: String, items: List<TrackingItem>?, clickable: Boolean) {
        if (items.isNullOrEmpty()) return
        header(title, "${items.count { !it.revisado.isNullOrBlank() }} de ${items.size} calificadas")
        items.forEach { item ->
            val row = trackingRow(item)
            if (clickable && item.id > 0) {
                row.isClickable = true
                row.isFocusable = true
                row.setBackgroundResource(TypedValue().also {
                    act.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
                row.setOnClickListener { openActivity(item, items) }
            }
            container.addView(row)
        }
    }

    private fun trackingRow(t: TrackingItem): View {
        val v = inflater.inflate(R.layout.item_seg_actividad, container, false)
        v.findViewById<TextView>(R.id.txtSegTitulo).text = t.titulo ?: "—"
        val submitted = DueDates.parseEminusDate(t.realizado)
        val graded = DueDates.parseEminusDate(t.revisado)
        v.findViewById<TextView>(R.id.txtSegFechas).text = when {
            submitted == null -> "Sin entregar"
            graded == null -> "Entregada ${DueDates.shortDate(submitted)} · sin calificar"
            else -> "Entregada ${DueDates.shortDate(submitted)} · calificada ${DueDates.shortDate(graded)}"
        }
        v.findViewById<TextView>(R.id.txtSegValor).apply {
            val value = t.valor?.replace(" pts.", "")?.replace("pts.", "")?.trim()
            text = if (graded != null && !value.isNullOrBlank()) value else if (submitted == null) "—" else "En revisión"
            val f = fraccion(t.valor)
            setTextColor(color(when {
                graded == null -> R.color.text_secondary
                f != null && f.second > 0 && f.first / f.second < 0.6 -> R.color.badge_error_text
                else -> R.color.badge_success_text
            }))
        }
        return v
    }

    // ===================== utilidades =====================

    private fun header(title: String, count: String? = null) {
        val v = inflater.inflate(R.layout.item_section_header, container, false)
        v.findViewById<TextView>(R.id.txtHeaderTitle).text = title
        v.findViewById<TextView>(R.id.txtHeaderCount).text = count.orEmpty()
        v.findViewById<View>(R.id.viewHeaderDot)?.visibility = View.GONE
        container.addView(v)
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
}
