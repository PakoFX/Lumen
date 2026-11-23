package com.pako.eminus4.ui.courses

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.pako.eminus4.R
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.domain.StudentStatus
import com.pako.eminus4.ui.common.icon
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.util.iconoInicio

/**
 * Pinta una tarjeta `item_all_activity.xml` con el criterio único de [StudentStatus].
 * Se usa en la agenda de Avisos y en "Todas las actividades".
 */
object ActividadCardBinder {

    fun statusColor(context: Context, estado: StudentStatus): Int = ContextCompat.getColor(
        context,
        when (estado) {
            StudentStatus.TO_DO, StudentStatus.DRAFT -> R.color.badge_warning_text
            StudentStatus.AWAITING_GRADE -> R.color.badge_info_text
            StudentStatus.GRADED -> R.color.badge_success_text
            StudentStatus.NOT_SUBMITTED -> R.color.badge_error_text
        }
    )

    fun urgencyColor(context: Context, urgencia: DueDates.Urgency): Int = ContextCompat.getColor(
        context,
        when (urgencia) {
            DueDates.Urgency.CRITICAL, DueDates.Urgency.OVERDUE -> R.color.badge_error_text
            DueDates.Urgency.SOON -> R.color.badge_warning_text
            else -> R.color.text_secondary
        }
    )

    /**
     * @param compacto en listas agrupadas por curso y filtradas por estado: oculta el nombre
     *                 del curso y la píldora de estado, que ya se ven en el encabezado/pestaña.
     */
    fun bind(view: View, item: CourseActivityItem, compacto: Boolean = false, onClick: (CourseActivityItem) -> Unit) {
        val ctx = view.context
        val act = item.activity
        val estado = act.studentStatus

        val stripe = view.findViewById<View>(R.id.viewCourseStripe)
        val txtCourse = view.findViewById<TextView>(R.id.txtCourseBadge)
        val txtTeam = view.findViewById<TextView>(R.id.txtTeamTag)
        val txtTitle = view.findViewById<TextView>(R.id.txtActivityTitle)
        val badge = view.findViewById<TextView>(R.id.badgeStatus)
        val txtWhen = view.findViewById<TextView>(R.id.txtDueDate)
        val txtPoints = view.findViewById<TextView>(R.id.txtPoints)

        val colorCurso = try {
            Color.parseColor(item.courseColor)
        } catch (_: Exception) {
            ContextCompat.getColor(ctx, R.color.brand_uv_green)
        }
        stripe.setBackgroundColor(colorCurso)

        txtCourse.text = item.courseName
        txtTeam.visibility = if (act.esPorEquipos) View.VISIBLE else View.GONE
        txtTeam.iconoInicio(R.drawable.ic_group, tamanoDp = 14, separacionDp = 4)
        txtTitle.text = act.nombre

        val colorEst = statusColor(ctx, estado)
        badge.text = estado.label
        badge.setTextColor(colorEst)
        badge.iconoInicio(estado.icon, colorEst, tamanoDp = 14, separacionDp = 4)

        txtCourse.visibility = if (compacto) View.GONE else View.VISIBLE
        badge.visibility = if (compacto) View.GONE else View.VISIBLE
        // Si no queda nada en la fila superior (ni curso ni equipo), se oculta entera
        (txtCourse.parent as View).visibility =
            if (compacto && !act.esPorEquipos) View.GONE else View.VISIBLE
        (txtTeam.layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginStart =
            if (compacto) 0 else (8 * ctx.resources.displayMetrics.density).toInt()

        // Línea de tiempo según la situación
        when (estado) {
            StudentStatus.TO_DO, StudentStatus.DRAFT -> {
                val p = DueDates.dueDate(act.terminoMillis)
                txtWhen.text = p.text
                txtWhen.setTextColor(urgencyColor(ctx, p.urgency))
            }
            StudentStatus.AWAITING_GRADE -> {
                txtWhen.text = "Enviada el ${DueDates.shortDate(DueDates.parseEminusDate(act.fechaEntrega))}"
                txtWhen.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            StudentStatus.GRADED -> {
                val rev = DueDates.parseEminusDate(act.fechaRevision)
                txtWhen.text = if (rev != null) "Revisada el ${DueDates.shortDate(rev)}" else "Revisada por el docente"
                txtWhen.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
            StudentStatus.NOT_SUBMITTED -> {
                txtWhen.text = "Cerró el ${DueDates.shortDate(act.terminoMillis)}"
                txtWhen.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
        }

        // Puntos o calificación
        val max = act.calificacionMax
        val cal = act.calificacion
        if (cal != null) {
            txtPoints.text = "${formatoNota(cal)}/${formatoNota(max)}"
            txtPoints.textSize = 16f
            txtPoints.setTextColor(
                ContextCompat.getColor(ctx, if (cal >= max * 0.6) R.color.badge_success_text else R.color.badge_error_text)
            )
        } else {
            txtPoints.text = if ((act.valor ?: 0.0) > 0) "${formatoNota(act.valor!!)} pts" else ""
            txtPoints.textSize = 12f
            txtPoints.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }

        view.setOnClickListener { onClick(item) }
    }

    /** 9.0 → "9", 9.5 → "9.5" */
    fun formatoNota(n: Double): String =
        if (n % 1.0 == 0.0) "%.0f".format(n) else "%.1f".format(n)

    fun abrirDetalle(context: Context, item: CourseActivityItem) {
        context.startActivity(Intent(context, ActivityDetailActivity::class.java).apply {
            putExtra(ActivityDetailActivity.EXTRA_COURSE_ID, item.courseId)
            putExtra(ActivityDetailActivity.EXTRA_COURSE_NAME, item.courseName)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_ID, item.activity.idActividad)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLE, item.activity.nombre)
        })
    }
}
