package com.pako.eminus4.data.local

import android.content.Context
import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase

/**
 * Preferencias del horario que pone la persona: horas de término a mano (por cuenta, materia y día),
 * si se muestra el tiempo libre, y cuándo se intentó por última vez traer el PDF de MiUV.
 * Mismo archivo y llaves que antes (`horario_fines`) para no perder lo ya guardado.
 */
class SchedulePrefs(context: Context, private val studentId: () -> String?) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun manualEnd(subject: ClaseHorario, session: SesionClase): Int? =
        prefs.getInt(key(subject, session), -1).takeIf { it >= 0 }

    fun setManualEnd(subject: ClaseHorario, session: SesionClase, minutes: Int) =
        prefs.edit().putInt(key(subject, session), minutes).apply()

    fun removeManualEnd(subject: ClaseHorario, session: SesionClase) =
        prefs.edit().remove(key(subject, session)).apply()

    var showFreeTime: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FREE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_FREE, value).apply()

    var lastPdfAttemptAt: Long
        get() = prefs.getLong(KEY_LAST_PDF, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PDF, value).apply()

    private fun key(subject: ClaseHorario, session: SesionClase) =
        "${studentId()}|${subject.nrc ?: subject.materia}|${session.numeroDia}"

    private companion object {
        const val FILE = "horario_fines"
        const val KEY_SHOW_FREE = "mostrar_libre"
        const val KEY_LAST_PDF = "ultimo_intento_pdf"
    }
}
