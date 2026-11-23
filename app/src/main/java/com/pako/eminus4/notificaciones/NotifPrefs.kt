package com.pako.eminus4.notificaciones

import android.content.Context
import com.pako.eminus4.domain.ReminderSettings

/**
 * Configuración de recordatorios que elige el usuario (Perfil → Notificaciones).
 * Valores de inicio: tareas y exámenes encendidos, foros apagados; recordar 24 h, 5 h y 1 h antes
 * y avisar si una tarea venció sin entregar.
 */
class NotifPrefs(context: Context) {

    companion object {
        /** Opciones de "cuánto antes recordar", en minutos. */
        val OPCIONES_MINUTOS = listOf(7 * 24 * 60, 3 * 24 * 60, 24 * 60, 12 * 60, 5 * 60, 3 * 60, 60, 30)
        private val PREDETERMINADOS = setOf(24 * 60, 5 * 60, 60)

        fun etiqueta(min: Int): String = when {
            min % (24 * 60) == 0 -> (min / (24 * 60)).let { if (it == 7) "1 semana" else if (it == 1) "1 día" else "$it días" }
            min % 60 == 0 -> "${min / 60} h"
            else -> "$min min"
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE)

    var activadas: Boolean
        get() = prefs.getBoolean("activadas", true)
        set(v) = prefs.edit().putBoolean("activadas", v).apply()

    var tareas: Boolean
        get() = prefs.getBoolean("tareas", true)
        set(v) = prefs.edit().putBoolean("tareas", v).apply()

    var examenes: Boolean
        get() = prefs.getBoolean("examenes", true)
        set(v) = prefs.edit().putBoolean("examenes", v).apply()

    /** "Ya abrió el examen" al momento de abrir. */
    var examenAlAbrir: Boolean
        get() = prefs.getBoolean("examen_al_abrir", true)
        set(v) = prefs.edit().putBoolean("examen_al_abrir", v).apply()

    var foros: Boolean
        get() = prefs.getBoolean("foros", false)
        set(v) = prefs.edit().putBoolean("foros", v).apply()

    var avisarVencida: Boolean
        get() = prefs.getBoolean("vencida", true)
        set(v) = prefs.edit().putBoolean("vencida", v).apply()

    /** Emojis en el texto de las notificaciones (⏰ 📅 …). A no todos les gustan. */
    var conEmojis: Boolean
        get() = prefs.getBoolean("con_emojis", true)
        set(v) = prefs.edit().putBoolean("con_emojis", v).apply()

    /** Minutos antes del cierre en los que se recuerda. */
    var minutosAntes: Set<Int>
        get() = prefs.getStringSet("minutos", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: PREDETERMINADOS
        set(v) = prefs.edit().putStringSet("minutos", v.map { it.toString() }.toSet()).apply()

    /** Ya se le preguntó al usuario si quiere activar las notificaciones (primera vez). */
    var yaSePregunto: Boolean
        get() = prefs.getBoolean("ya_pregunto", false)
        set(v) = prefs.edit().putBoolean("ya_pregunto", v).apply()

    /** Los ajustes como datos simples para [com.pako.eminus4.domain.ReminderPlanner]. */
    fun toSettings() = ReminderSettings(
        enabled = activadas, tasks = tareas, exams = examenes, examOnOpen = examenAlAbrir,
        forums = foros, overdue = avisarVencida, minutesBefore = minutosAntes
    )
}
