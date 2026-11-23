package com.pako.eminus4.notificaciones

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pako.eminus4.R
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.ui.courses.ActivityDetailActivity
import com.pako.eminus4.ui.exams.ExamsActivity
import com.pako.eminus4.ui.forums.ForumDetailActivity
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.domain.ReminderPlanner
import com.pako.eminus4.data.local.ReminderStore
import com.pako.eminus4.data.model.ReminderSource
import com.pako.eminus4.data.model.ReminderType
import com.pako.eminus4.data.model.ScheduledAlarm

/**
 * Recordatorios LOCALES: al cargar Eminus se guardan las fechas (tareas, exámenes, foros) y se
 * programan alarmas en Android. Suenan aunque la sesión caduque, no haya internet o la app esté
 * cerrada. Cambiar los ajustes reprograma todo sin volver a consultar Eminus.
 *
 * - Fuente (ReminderSource): algo con fecha (una tarea pendiente, un examen, un foro abierto).
 * - Alarma (ScheduledAlarm): fuente × momento (24 h antes, 5 h antes, al vencer…), según [NotifPrefs].
 */
object Recordatorios {

    private const val TAG = "RECORDATORIOS"
    const val ACCION = "com.pako.eminus4.RECORDATORIO"

    const val CANAL_TAREAS = "tareas"
    const val CANAL_EXAMENES = "examenes"
    const val CANAL_FOROS = "foros"
    const val CANAL_VENCIDAS = "vencidas"

    private fun store(ctx: Context) = ReminderStore(ctx)

    // ===================== CANALES =====================

    fun crearCanales(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CANAL_TAREAS, "Tareas por vencer", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Recordatorios antes de que cierre una actividad" },
            NotificationChannel(CANAL_VENCIDAS, "Tareas vencidas", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Aviso cuando una actividad cerró y no aparece entregada" },
            NotificationChannel(CANAL_EXAMENES, "Exámenes", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Cuando abre un examen y antes de que cierre" },
            NotificationChannel(CANAL_FOROS, "Foros", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Antes de que cierre un foro" }
        ).forEach { nm.createNotificationChannel(it) }
    }

    // ===================== FUENTES (se actualizan al cargar Eminus) =====================

    fun actualizarTareas(ctx: Context, actividades: List<CourseActivityItem>) =
        guardarFuentes(ctx, ReminderType.TASK, ReminderPlanner.fromActivities(actividades))

    fun actualizarExamenes(ctx: Context, examenes: List<ExamItem>) =
        guardarFuentes(ctx, ReminderType.EXAM, ReminderPlanner.fromExams(examenes))

    fun actualizarForos(ctx: Context, foros: List<ForumItem>) =
        guardarFuentes(ctx, ReminderType.FORUM, ReminderPlanner.fromForums(foros))

    /** Al entregar desde la app: dejar de recordar esa tarea al instante. */
    fun quitarTarea(ctx: Context, idCurso: Long, idActividad: Long) {
        val actuales = store(ctx).sources(ReminderType.TASK).filterNot { it.courseId == idCurso && it.refId == idActividad }
        guardarFuentes(ctx, ReminderType.TASK, actuales)
    }

    private fun guardarFuentes(ctx: Context, tipo: ReminderType, fuentes: List<ReminderSource>) {
        store(ctx).saveSources(tipo, fuentes, owner = SessionStore(ctx).getSessionStudentId())
        reprogramar(ctx)
    }

    fun buscarFuente(ctx: Context, clave: String): ReminderSource? = store(ctx).findSource(clave)

    /** Al cerrar sesión o cambiar de cuenta: no recordar cosas de otra persona. */
    fun borrarTodo(ctx: Context) {
        val s = store(ctx)
        ReminderScheduler(ctx).cancel(s.alarms())
        s.clear()
    }

    // ===================== ALARMAS =====================

    /** Cancela todo y vuelve a programar con las fuentes guardadas y los ajustes actuales. */
    fun reprogramar(ctx: Context) {
        val s = store(ctx)
        val scheduler = ReminderScheduler(ctx)
        scheduler.cancel(s.alarms())
        val alarmas = ReminderPlanner.plan(
            s.allSources(), NotifPrefs(ctx).toSettings(), System.currentTimeMillis(),
            sourcesOwner = s.owner, currentUser = SessionStore(ctx).getSessionStudentId()
        )
        scheduler.schedule(alarmas)
        s.saveAlarms(alarmas)
        Log.i(TAG, "Programadas ${alarmas.size} alarmas (exactas=${scheduler.canUseExact})")
    }

    fun alarmasProgramadas(ctx: Context): List<ScheduledAlarm> = store(ctx).alarms()

    // ===================== MOSTRAR =====================

    const val ACCION_POSPONER = "com.pako.eminus4.RECORDATORIO_POSPONER"
    const val ACCION_ENTREGADA = "com.pako.eminus4.RECORDATORIO_ENTREGADA"

    /**
     * Muestra el aviso de una alarma. Antes revisa la fuente guardada: si la tarea ya no está
     * pendiente (se entregó y la app lo supo), no avisa.
     */
    fun mostrar(ctx: Context, clave: String, momento: String) {
        val p = NotifPrefs(ctx)
        if (!p.activadas) return
        val f = buscarFuente(ctx, clave) ?: run { Log.i(TAG, "Ya no aplica: $clave"); return }
        notificarFuente(ctx, f, momento, idNotificacion(clave))
    }

    /** Una sola notificación por tarea/examen/foro: cada recordatorio nuevo reemplaza al anterior. */
    private fun idNotificacion(clave: String) = clave.hashCode()

    /** Arma el aviso con diseño: ícono con el color de la materia, cuenta regresiva y botones. */
    private fun notificarFuente(ctx: Context, f: ReminderSource, momento: String, id: Int, prueba: Boolean = false) {
        crearCanales(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val ahora = System.currentTimeMillis()
        val cierre = f.end
        val falta = cierre?.minus(ahora)
        val vencida = momento == ReminderPlanner.MOMENT_OVERDUE
        val abre = momento == ReminderPlanner.MOMENT_OPENS

        val canal = when {
            vencida -> CANAL_VENCIDAS
            f.type == ReminderType.EXAM -> CANAL_EXAMENES
            f.type == ReminderType.FORUM -> CANAL_FOROS
            else -> CANAL_TAREAS
        }
        // Urgency: rojo ≤1 h o vencida, naranja <24 h, verde lo demás
        val urgencyColor = ContextCompat.getColor(ctx, when {
            vencida || (falta != null && falta <= 60 * 60_000L) -> R.color.badge_error_text
            falta != null && falta < 24 * 60 * 60_000L -> R.color.badge_warning_text
            else -> R.color.brand_uv_green
        })
        val icono = when {
            vencida -> R.drawable.ic_warning
            f.type == ReminderType.EXAM -> R.drawable.ic_quiz
            f.type == ReminderType.FORUM -> R.drawable.ic_forum
            else -> R.drawable.ic_assignment
        }
        val cuandoCierra = cierre?.let { DueDates.dueDate(it, ahora).text }.orEmpty()
        val verboCierre = if (f.type == ReminderType.TASK) cuandoCierra else cuandoCierra.replace("Vence", "Cierra").replace("Venció", "Cerró")

        // Emojis opcionales (Perfil → Notificaciones): sin ellos, el texto queda limpio
        val emo = NotifPrefs(ctx).conEmojis
        fun e(emoji: String) = if (emo) "$emoji " else ""
        val titulo = when {
            vencida -> "${e("⚠️")}Venció: ${f.name}"
            abre -> "${e("📝")}Ya abrió el examen: ${f.name}"
            f.type == ReminderType.EXAM -> "${e("📝")}${f.name}"
            f.type == ReminderType.FORUM -> "${e("💬")}${f.name}"
            else -> "${e("⏰")}${f.name}"
        }
        val resumen = when {
            vencida -> "Si no la entregaste, revísala en Eminus."
            else -> verboCierre
        }

        // Expandida: renglones de detalle
        val fechaCierre = cierre?.let {
            java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale("es", "MX")).format(java.util.Date(it))
        }
        val detalle = NotificationCompat.InboxStyle()
            .setBigContentTitle(titulo)
            .setSummaryText(f.course)
        detalle.addLine("${e("📚")}${f.course}")
        if (fechaCierre != null) detalle.addLine("${e("📅")}${if (vencida) "Cerró" else "Cierra"}: $fechaCierre")
        if (!vencida && falta != null && falta > 0) detalle.addLine("${e("⏳")}Faltan ${ReminderPlanner.duration(falta)}")
        if (abre && f.start != null) detalle.addLine("${e("🟢")}Abrió a las ${java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "MX")).format(java.util.Date(f.start))}")
        val extras = listOfNotNull(
            f.points?.takeIf { it > 0 }?.let { "${e("⭐")}${if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)} pts" },
            if (f.inTeam) "${e("👥")}En equipo" else null
        )
        if (extras.isNotEmpty()) detalle.addLine(extras.joinToString("   "))
        if (vencida) detalle.addLine("Si no la entregaste, revísala en Eminus.")

        val abrir = if (prueba) null else intentAbrir(ctx, f).let {
            it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            PendingIntent.getActivity(ctx, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val b = NotificationCompat.Builder(ctx, canal)
            .setSmallIcon(icono)
            .setLargeIcon(iconoMateria(ctx, f, icono))
            .setColor(urgencyColor)
            .setContentTitle(titulo)
            .setContentText(resumen)
            .setSubText(f.course)
            .setStyle(detalle)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply { abrir?.let { setContentIntent(it) } }

        // Cuenta regresiva en vivo hasta el cierre (en el encabezado del aviso)
        if (!vencida && cierre != null && cierre > ahora) {
            b.setWhen(cierre).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            b.setTimeoutAfter(cierre - ahora + 60_000L)
        }

        // Botones
        if (!prueba && !vencida && cierre != null && cierre - ahora > 65 * 60_000L) {
            b.addAction(R.drawable.ic_schedule, "Recordar en 1 h", accion(ctx, ACCION_POSPONER, f, momento, id))
        }
        if (!prueba && f.type == ReminderType.TASK) {
            b.addAction(R.drawable.ic_check_circle, "Ya la entregué", accion(ctx, ACCION_ENTREGADA, f, momento, id))
        }
        if (prueba) {
            b.addAction(R.drawable.ic_schedule, "Recordar en 1 h", null)
            b.addAction(R.drawable.ic_check_circle, "Ya la entregué", null)
        }

        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso de notificaciones")
        }
    }

    /** Ícono grande: círculo con el color de la materia y el ícono del tipo en blanco. */
    private fun iconoMateria(ctx: Context, f: ReminderSource, icono: Int): android.graphics.Bitmap {
        val colorHex = try {
            SessionStore(ctx).getCachedCourses().firstOrNull { it.idCurso == f.courseId }?.getColorHexaLimpio()
        } catch (_: Exception) { null } ?: "#00897B"
        val tam = (64 * ctx.resources.displayMetrics.density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(tam, tam, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val pincel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = try { android.graphics.Color.parseColor(colorHex) } catch (_: Exception) { ContextCompat.getColor(ctx, R.color.brand_uv_green) }
        }
        canvas.drawCircle(tam / 2f, tam / 2f, tam / 2f, pincel)
        ContextCompat.getDrawable(ctx, icono)?.mutate()?.let { d ->
            val m = tam / 4
            d.setBounds(m, m, tam - m, tam - m)
            d.setTint(android.graphics.Color.WHITE)
            d.draw(canvas)
        }
        return bmp
    }

    private fun accion(ctx: Context, accion: String, f: ReminderSource, momento: String, idNotif: Int): PendingIntent {
        val i = Intent(ctx, RecordatorioReceiver::class.java).apply {
            action = accion
            data = Uri.parse("bodrio://accion/$accion/${f.key}")
            putExtra("clave", f.key)
            putExtra("momento", momento)
            putExtra("idNotificacion", idNotif)
        }
        return PendingIntent.getBroadcast(ctx, (accion + f.key).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "Recordar en 1 h": una alarma suelta (no se guarda; al sonar revisa que siga pendiente). */
    fun posponer(ctx: Context, clave: String, momento: String, idNotif: Int) {
        NotificationManagerCompat.from(ctx).cancel(idNotif)
        val cuando = System.currentTimeMillis() + 60 * 60_000L
        val codigo = ("$clave|pospuesto").hashCode()
        ReminderScheduler(ctx).scheduleOnce(codigo, clave, momento, cuando)
    }

    /** "Ya la entregué": dejar de recordarla (hasta que Eminus diga lo contrario al recargar). */
    fun marcarEntregada(ctx: Context, clave: String, idNotif: Int) {
        NotificationManagerCompat.from(ctx).cancel(idNotif)
        val f = buscarFuente(ctx, clave) ?: return
        if (f.type == ReminderType.TASK) quitarTarea(ctx, f.courseId, f.refId)
    }

    private fun intentAbrir(ctx: Context, f: ReminderSource): Intent = when (f.type) {
        ReminderType.TASK -> Intent(ctx, ActivityDetailActivity::class.java).apply {
            putExtra(ActivityDetailActivity.EXTRA_COURSE_ID, f.courseId)
            putExtra(ActivityDetailActivity.EXTRA_COURSE_NAME, f.course)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_ID, f.refId)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLE, f.name)
        }
        ReminderType.EXAM -> Intent(ctx, ExamsActivity::class.java)
        ReminderType.FORUM -> Intent(ctx, ForumDetailActivity::class.java).apply {
            putExtra("idCurso", f.courseId); putExtra("idForo", f.refId)
            putExtra("titulo", f.name); putExtra("nombreCurso", f.course)
        }
    }

    /** Aviso de prueba con el diseño real (pantalla de ajustes). */
    fun probar(ctx: Context) {
        val ahora = System.currentTimeMillis()
        val curso = SessionStore(ctx).getCachedCourses().firstOrNull { it.isActivo() }
        val ejemplo = ReminderSource(
            ReminderType.TASK, curso?.idCurso ?: 0, 0, "Proyecto integrador (ejemplo)",
            curso?.getNombreLimpio() ?: "Diseño de Software",
            start = null, end = ahora + 5 * 60 * 60_000L, points = 10.0, inTeam = true
        )
        notificarFuente(ctx, ejemplo, "antes_300", 999_001, prueba = true)
    }
}
