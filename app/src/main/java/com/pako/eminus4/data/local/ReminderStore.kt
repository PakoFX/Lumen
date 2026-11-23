package com.pako.eminus4.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pako.eminus4.data.model.ReminderSource
import com.pako.eminus4.data.model.ReminderType
import com.pako.eminus4.data.model.ScheduledAlarm

/** Fuentes y alarmas de recordatorios en el teléfono. Mismo archivo y llaves que antes (`recordatorios`). */
class ReminderStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun sources(type: ReminderType): List<ReminderSource> =
        read(prefs.getString(sourcesKey(type), null), object : TypeToken<List<ReminderSource>>() {})

    fun allSources(): Map<ReminderType, List<ReminderSource>> = ReminderType.values().associateWith(::sources)

    fun findSource(key: String): ReminderSource? =
        ReminderType.values().asSequence().flatMap { sources(it).asSequence() }.firstOrNull { it.key == key }

    /** Guarda las fuentes de un tipo y de quién son (para no recordar cosas de otra cuenta). */
    fun saveSources(type: ReminderType, sources: List<ReminderSource>, owner: String?) {
        prefs.edit().putString(sourcesKey(type), gson.toJson(sources)).putString(KEY_OWNER, owner).apply()
    }

    val owner: String? get() = prefs.getString(KEY_OWNER, null)

    fun alarms(): List<ScheduledAlarm> = read(prefs.getString(KEY_ALARMS, null), object : TypeToken<List<ScheduledAlarm>>() {})

    fun saveAlarms(alarms: List<ScheduledAlarm>) = prefs.edit().putString(KEY_ALARMS, gson.toJson(alarms)).apply()

    fun clear() = prefs.edit().clear().apply()

    private fun <T> read(json: String?, type: TypeToken<List<T>>): List<T> {
        if (json == null) return emptyList()
        return try {
            // Gson puede dejar nulos en campos no nulos si el JSON es viejo o está dañado: se descartan
            gson.fromJson<List<T>>(json, type.type).orEmpty().filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sourcesKey(type: ReminderType) = "fuentes_${type.keyPrefix}"

    private companion object {
        const val FILE = "recordatorios"
        const val KEY_OWNER = "matricula"
        const val KEY_ALARMS = "alarmas"
    }
}
