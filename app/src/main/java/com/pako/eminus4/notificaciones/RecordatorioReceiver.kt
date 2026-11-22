package com.pako.eminus4.notificaciones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Suena una alarma de recordatorio → muestra la notificación (sin internet ni sesión).
 * También atiende los botones del aviso: "Recordar en 1 h" y "Ya la entregué".
 */
class RecordatorioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clave = intent.getStringExtra("clave") ?: return
        val momento = intent.getStringExtra("momento") ?: return
        val idNotif = intent.getIntExtra("idNotificacion", clave.hashCode())
        when (intent.action) {
            Recordatorios.ACCION -> Recordatorios.mostrar(context, clave, momento)
            Recordatorios.ACCION_POSPONER -> Recordatorios.posponer(context, clave, momento, idNotif)
            Recordatorios.ACCION_ENTREGADA -> Recordatorios.marcarEntregada(context, clave, idNotif)
        }
    }
}

/** Android borra las alarmas al reiniciar o actualizar la app: se vuelven a programar. */
class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> Recordatorios.reprogramar(context)
        }
    }
}
