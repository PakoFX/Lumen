package com.pako.eminus4.notificaciones

import android.app.AlarmManager
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.ui.courses.ActivityDetailActivity
import com.pako.eminus4.ui.exams.ExamsActivity
import com.pako.eminus4.ui.forums.ForumDetailActivity
import com.pako.eminus4.util.Plazos

/**
 * Recordatorios LOCALES: al cargar Eminus se guardan las fechas (tareas, exámenes, foros) y se
 * programan alarmas en Android. Suenan aunque la sesión caduque, no haya internet o la app esté
 * cerrada. Cambiar los ajustes reprograma todo sin volver a consultar Eminus.
 *
 * - Fuente: algo con fecha (una tarea pendiente, un examen, un foro abierto).
 * - Alarma: fuente × momento (24 h antes, 5 h antes, al vencer…), según [NotifPrefs].
 */
object Recordatorios {

    private const val TAG = "RECORDATORIOS"
    private const val PREFS = "recordatorios"
    private const val MAX_ALARMAS = 400
    const val ACCION = "com.pako.eminus4.RECORDATORIO"

    const val CANAL_TAREAS = "tareas"
    const val CANAL_EXAMENES = "examenes"
    const val CANAL_FOROS = "foros"
    const val CANAL_VENCIDAS = "vencidas"

    enum class Tipo { TAREA, EXAMEN, FORO }

    /** Algo con fecha que puede generar recordatorios. */
    data class Fuente(
        val tipo: Tipo,
        val idCurso: Long,
        val idRef: Long,
        val nombre: String,
        val curso: String,
        val inicio: Long?,
        val cierre: Long?,
        /** Valor de la actividad/examen (para el detalle del aviso). */
        val puntos: Double? = null,
        val enEquipo: Boolean = false
    ) {
        val clave get() = "${tipo.name}:$idCurso:$idRef"
    }

    /** Alarma concreta (se guarda para cancelarla o mostrarla después). */
    data class Alarma(val codigo: Int, val clave: String, val cuando: Long, val momento: String, val minutosAntes: Int)

    private val gson = Gson()
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    /** Tareas por hacer (solo pendientes: lo entregado deja de recordarse). */
    fun actualizarTareas(ctx: Context, actividades: List<CourseActivityItem>) {
        val fuentes = actividades.filter { it.activity.estadoAlumno.esPendiente }.map {
            Fuente(Tipo.TAREA, it.courseId, it.activity.idActividad, it.activity.nombre, it.courseName,
                Plazos.parseIso(it.activity.fechaInicio), it.activity.terminoMillis,
                puntos = it.activity.calificacionMax, enEquipo = it.activity.esPorEquipos)
        }
        guardarFuentes(ctx, Tipo.TAREA, fuentes)
    }

    /** Exámenes sin calificar que aún no cierran. */
    fun actualizarExamenes(ctx: Context, examenes: List<ExamItem>) {
        val fuentes = examenes.filter { !it.estaCalificado && !it.estaVencido }.map {
            Fuente(Tipo.EXAMEN, it.idCurso, it.idExamen, it.titulo, it.nombreCurso,
                Plazos.parseIso(it.fechaInicio), Plazos.parseIso(it.fechaTermino), puntos = it.valor)
        }
        guardarFuentes(ctx, Tipo.EXAMEN, fuentes)
    }

    /** Foros abiertos (para avisar antes de que cierren). */
    fun actualizarForos(ctx: Context, foros: List<ForumItem>) {
        val fuentes = foros.filter { it.estaAbierto() }.map {
            Fuente(Tipo.FORO, it.idCurso, it.idForo, it.titulo, it.nombreCurso,
                Plazos.parseIso(it.fechaInicio), Plazos.parseIso(it.fechaProrroga) ?: Plazos.parseIso(it.fechaTermino))
        }
        guardarFuentes(ctx, Tipo.FORO, fuentes)
    }

    /** Al entregar desde la app: dejar de recordar esa tarea al instante. */
    fun quitarTarea(ctx: Context, idCurso: Long, idActividad: Long) {
        val actuales = fuentes(ctx, Tipo.TAREA).filterNot { it.idCurso == idCurso && it.idRef == idActividad }
        guardarFuentes(ctx, Tipo.TAREA, actuales)
    }

    private fun guardarFuentes(ctx: Context, tipo: Tipo, fuentes: List<Fuente>) {
        prefs(ctx).edit()
            .putString("fuentes_${tipo.name}", gson.toJson(fuentes))
            .putString("matricula", SessionManager(ctx).getMatriculaSesion())
            .apply()
        reprogramar(ctx)
    }

    fun fuentes(ctx: Context, tipo: Tipo): List<Fuente> {
        val json = prefs(ctx).getString("fuentes_${tipo.name}", null) ?: return emptyList()
        return try { gson.fromJson(json, object : TypeToken<List<Fuente>>() {}.type) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun buscarFuente(ctx: Context, clave: String): Fuente? =
        Tipo.values().asSequence().flatMap { fuentes(ctx, it).asSequence() }.firstOrNull { it.clave == clave }

    /** Al cerrar sesión o cambiar de cuenta: no recordar cosas de otra persona. */
    fun borrarTodo(ctx: Context) {
        cancelarAlarmas(ctx)
        prefs(ctx).edit().clear().apply()
    }

    // ===================== ALARMAS =====================

    /** Cancela todo y vuelve a programar con las fuentes guardadas y los ajustes actuales. */
    fun reprogramar(ctx: Context) {
        cancelarAlarmas(ctx)
        val p = NotifPrefs(ctx)
        // Fuentes de otra cuenta: no se programan
        val dueno = prefs(ctx).getString("matricula", null)
        val actual = SessionManager(ctx).getMatriculaSesion()
        if (!p.activadas || (dueno != null && actual != null && dueno != actual)) {
            guardarAlarmas(ctx, emptyList()); return
        }

        val ahora = System.currentTimeMillis()
        val minutos = p.minutosAntes.sortedDescending()
        val alarmas = mutableListOf<Alarma>()
        fun agregar(f: Fuente, cuando: Long, momento: String, minAntes: Int) {
            if (cuando <= ahora + 30_000) return
            alarmas += Alarma(("${f.clave}|$momento").hashCode(), f.clave, cuando, momento, minAntes)
        }

        if (p.tareas) fuentes(ctx, Tipo.TAREA).forEach { f ->
            val cierre = f.cierre ?: return@forEach
            minutos.forEach { m -> agregar(f, cierre - m * 60_000L, "antes_$m", m) }
            if (p.avisarVencida) agregar(f, cierre + 60_000L, "vencida", 0)
        }
        if (p.examenes) fuentes(ctx, Tipo.EXAMEN).forEach { f ->
            if (p.examenAlAbrir) f.inicio?.let { agregar(f, it, "abre", 0) }
            val cierre = f.cierre ?: return@forEach
            minutos.forEach { m -> agregar(f, cierre - m * 60_000L, "antes_$m", m) }
        }
        if (p.foros) fuentes(ctx, Tipo.FORO).forEach { f ->
            val cierre = f.cierre ?: return@forEach
            minutos.forEach { m -> agregar(f, cierre - m * 60_000L, "antes_$m", m) }
        }

        val finales = alarmas.sortedBy { it.cuando }.take(MAX_ALARMAS)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exactas = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        finales.forEach { a ->
            val pi = pendingAlarma(ctx, a.codigo, a.clave, a.momento, a.minutosAntes)
            try {
                if (exactas) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.cuando, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.cuando, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.cuando, pi)
            }
        }
        guardarAlarmas(ctx, finales)
        Log.i(TAG, "Programadas ${finales.size} alarmas (exactas=$exactas)")
    }

    fun alarmasProgramadas(ctx: Context): List<Alarma> {
        val json = prefs(ctx).getString("alarmas", null) ?: return emptyList()
        return try { gson.fromJson(json, object : TypeToken<List<Alarma>>() {}.type) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun guardarAlarmas(ctx: Context, alarmas: List<Alarma>) {
        prefs(ctx).edit().putString("alarmas", gson.toJson(alarmas)).apply()
    }

    private fun cancelarAlarmas(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        alarmasProgramadas(ctx).forEach { a ->
            am.cancel(pendingAlarma(ctx, a.codigo, a.clave, a.momento, a.minutosAntes))
        }
    }

    private fun pendingAlarma(ctx: Context, codigo: Int, clave: String, momento: String, minAntes: Int): PendingIntent {
        val i = Intent(ctx, RecordatorioReceiver::class.java).apply {
            action = ACCION
            data = Uri.parse("bodrio://recordatorio/$codigo") // hace única cada alarma
            putExtra("clave", clave)
            putExtra("momento", momento)
            putExtra("minutos", minAntes)
        }
        return PendingIntent.getBroadcast(ctx, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

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
    private fun notificarFuente(ctx: Context, f: Fuente, momento: String, id: Int, prueba: Boolean = false) {
        crearCanales(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val ahora = System.currentTimeMillis()
        val cierre = f.cierre
        val falta = cierre?.minus(ahora)
        val vencida = momento == "vencida"
        val abre = momento == "abre"

        val canal = when {
            vencida -> CANAL_VENCIDAS
            f.tipo == Tipo.EXAMEN -> CANAL_EXAMENES
            f.tipo == Tipo.FORO -> CANAL_FOROS
            else -> CANAL_TAREAS
        }
        // Urgencia: rojo ≤1 h o vencida, naranja <24 h, verde lo demás
        val colorUrgencia = ContextCompat.getColor(ctx, when {
            vencida || (falta != null && falta <= 60 * 60_000L) -> R.color.badge_error_text
            falta != null && falta < 24 * 60 * 60_000L -> R.color.badge_warning_text
            else -> R.color.brand_uv_green
        })
        val icono = when {
            vencida -> R.drawable.ic_warning
            f.tipo == Tipo.EXAMEN -> R.drawable.ic_quiz
            f.tipo == Tipo.FORO -> R.drawable.ic_forum
            else -> R.drawable.ic_assignment
        }
        val cuandoCierra = cierre?.let { Plazos.plazo(it, ahora).texto }.orEmpty()
        val verboCierre = if (f.tipo == Tipo.TAREA) cuandoCierra else cuandoCierra.replace("Vence", "Cierra").replace("Venció", "Cerró")

        // Emojis opcionales (Perfil → Notificaciones): sin ellos, el texto queda limpio
        val emo = NotifPrefs(ctx).conEmojis
        fun e(emoji: String) = if (emo) "$emoji " else ""
        val titulo = when {
            vencida -> "${e("⚠️")}Venció: ${f.nombre}"
            abre -> "${e("📝")}Ya abrió el examen: ${f.nombre}"
            f.tipo == Tipo.EXAMEN -> "${e("📝")}${f.nombre}"
            f.tipo == Tipo.FORO -> "${e("💬")}${f.nombre}"
            else -> "${e("⏰")}${f.nombre}"
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
            .setSummaryText(f.curso)
        detalle.addLine("${e("📚")}${f.curso}")
        if (fechaCierre != null) detalle.addLine("${e("📅")}${if (vencida) "Cerró" else "Cierra"}: $fechaCierre")
        if (!vencida && falta != null && falta > 0) detalle.addLine("${e("⏳")}Faltan ${duracion(falta)}")
        if (abre && f.inicio != null) detalle.addLine("${e("🟢")}Abrió a las ${java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "MX")).format(java.util.Date(f.inicio))}")
        val extras = listOfNotNull(
            f.puntos?.takeIf { it > 0 }?.let { "${e("⭐")}${if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)} pts" },
            if (f.enEquipo) "${e("👥")}En equipo" else null
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
            .setColor(colorUrgencia)
            .setContentTitle(titulo)
            .setContentText(resumen)
            .setSubText(f.curso)
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
        if (!prueba && f.tipo == Tipo.TAREA) {
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

    /** "2 días 3 h", "5 h 20 min", "45 min". */
    private fun duracion(ms: Long): String {
        val min = ms / 60_000L
        val d = min / (24 * 60); val h = (min % (24 * 60)) / 60; val m = min % 60
        return when {
            d > 0 -> "$d día${if (d == 1L) "" else "s"}" + if (h > 0) " $h h" else ""
            h > 0 -> "$h h" + if (m > 0) " $m min" else ""
            else -> "${m.coerceAtLeast(1)} min"
        }
    }

    /** Ícono grande: círculo con el color de la materia y el ícono del tipo en blanco. */
    private fun iconoMateria(ctx: Context, f: Fuente, icono: Int): android.graphics.Bitmap {
        val colorHex = try {
            SessionManager(ctx).getCachedCourses().firstOrNull { it.idCurso == f.idCurso }?.getColorHexaLimpio()
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

    private fun accion(ctx: Context, accion: String, f: Fuente, momento: String, idNotif: Int): PendingIntent {
        val i = Intent(ctx, RecordatorioReceiver::class.java).apply {
            action = accion
            data = Uri.parse("bodrio://accion/$accion/${f.clave}")
            putExtra("clave", f.clave)
            putExtra("momento", momento)
            putExtra("idNotificacion", idNotif)
        }
        return PendingIntent.getBroadcast(ctx, (accion + f.clave).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "Recordar en 1 h": una alarma suelta (no se guarda; al sonar revisa que siga pendiente). */
    fun posponer(ctx: Context, clave: String, momento: String, idNotif: Int) {
        NotificationManagerCompat.from(ctx).cancel(idNotif)
        val cuando = System.currentTimeMillis() + 60 * 60_000L
        val codigo = ("$clave|pospuesto").hashCode()
        val pi = pendingAlarma(ctx, codigo, clave, momento, 0)
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
        }
    }

    /** "Ya la entregué": dejar de recordarla (hasta que Eminus diga lo contrario al recargar). */
    fun marcarEntregada(ctx: Context, clave: String, idNotif: Int) {
        NotificationManagerCompat.from(ctx).cancel(idNotif)
        val f = buscarFuente(ctx, clave) ?: return
        if (f.tipo == Tipo.TAREA) quitarTarea(ctx, f.idCurso, f.idRef)
    }

    private fun intentAbrir(ctx: Context, f: Fuente): Intent = when (f.tipo) {
        Tipo.TAREA -> Intent(ctx, ActivityDetailActivity::class.java).apply {
            putExtra(ActivityDetailActivity.EXTRA_COURSE_ID, f.idCurso)
            putExtra(ActivityDetailActivity.EXTRA_COURSE_NAME, f.curso)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_ID, f.idRef)
            putExtra(ActivityDetailActivity.EXTRA_ACTIVITY_TITLE, f.nombre)
        }
        Tipo.EXAMEN -> Intent(ctx, ExamsActivity::class.java)
        Tipo.FORO -> Intent(ctx, ForumDetailActivity::class.java).apply {
            putExtra("idCurso", f.idCurso); putExtra("idForo", f.idRef)
            putExtra("titulo", f.nombre); putExtra("nombreCurso", f.curso)
        }
    }

    /** Aviso de prueba con el diseño real (pantalla de ajustes). */
    fun probar(ctx: Context) {
        val ahora = System.currentTimeMillis()
        val curso = SessionManager(ctx).getCachedCourses().firstOrNull { it.isActivo() }
        val ejemplo = Fuente(
            Tipo.TAREA, curso?.idCurso ?: 0, 0, "Proyecto integrador (ejemplo)",
            curso?.getNombreLimpio() ?: "Diseño de Software",
            inicio = null, cierre = ahora + 5 * 60 * 60_000L, puntos = 10.0, enEquipo = true
        )
        notificarFuente(ctx, ejemplo, "antes_300", 999_001, prueba = true)
    }
}
