package com.pako.eminus4.notificaciones

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.pako.eminus4.data.model.ScheduledAlarm

/**
 * Programa y cancela las alarmas en Android. El PendingIntent (acción, data, código, extras)
 * es idéntico al de antes del refactor: así se pueden cancelar las alarmas ya programadas.
 */
class ReminderScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    /** Si Android deja usar alarmas exactas (si no, suenan con unos minutos de margen). */
    val canUseExact: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun schedule(alarms: List<ScheduledAlarm>) = alarms.forEach { set(it.at, pendingIntent(it.code, it.key, it.moment, it.minutesBefore)) }

    fun cancel(alarms: List<ScheduledAlarm>) = alarms.forEach {
        alarmManager.cancel(pendingIntent(it.code, it.key, it.moment, it.minutesBefore))
    }

    /** "Recordar en 1 h": alarma suelta (no se guarda; al sonar se revisa que siga pendiente). */
    fun scheduleOnce(code: Int, key: String, moment: String, at: Long) = set(at, pendingIntent(code, key, moment, 0))

    private fun set(at: Long, intent: PendingIntent) {
        try {
            if (canUseExact) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun pendingIntent(code: Int, key: String, moment: String, minutesBefore: Int): PendingIntent {
        val intent = Intent(appContext, RecordatorioReceiver::class.java).apply {
            action = Recordatorios.ACCION
            data = Uri.parse("bodrio://recordatorio/$code") // hace única cada alarma
            putExtra("clave", key)
            putExtra("momento", moment)
            putExtra("minutos", minutesBefore)
        }
        return PendingIntent.getBroadcast(appContext, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
