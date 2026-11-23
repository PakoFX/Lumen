package com.pako.eminus4.domain

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pako.eminus4.data.model.ReminderSource
import com.pako.eminus4.data.model.ReminderType
import com.pako.eminus4.data.model.ScheduledAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlannerTest {

    private val hour = 60 * 60_000L
    private val now = 1_000_000_000_000L
    private val task = ReminderSource(ReminderType.TASK, 1, 10, "Tarea", "BD", start = null, end = now + 48 * hour)
    private val exam = ReminderSource(ReminderType.EXAM, 1, 20, "Parcial", "BD", start = now + 2 * hour, end = now + 4 * hour)
    private val forum = ReminderSource(ReminderType.FORUM, 1, 30, "Foro", "BD", start = null, end = now + 10 * hour)
    private val all = mapOf(ReminderType.TASK to listOf(task), ReminderType.EXAM to listOf(exam), ReminderType.FORUM to listOf(forum))

    @Test
    fun `ajustes de inicio - tarea 24h 5h 1h y vencida, examen al abrir y antes, foros apagados`() {
        val alarms = ReminderPlanner.plan(all, ReminderSettings(), now)
        val taskMoments = alarms.filter { it.key == task.key }.map { it.moment }
        assertEquals(listOf("antes_1440", "antes_300", "antes_60", "vencida"), taskMoments)
        // examen: abre en 2 h, cierra en 4 h → solo "abre" y 1 h antes (24 h y 5 h antes ya pasaron)
        assertEquals(listOf("abre", "antes_60"), alarms.filter { it.key == exam.key }.map { it.moment })
        assertTrue(alarms.none { it.key == forum.key })
        assertEquals(alarms.sortedBy { it.at }, alarms)
    }

    @Test
    fun `nada si estan desactivados o las fuentes son de otra cuenta`() {
        assertTrue(ReminderPlanner.plan(all, ReminderSettings(enabled = false), now).isEmpty())
        assertTrue(ReminderPlanner.plan(all, ReminderSettings(), now, sourcesOwner = "S1", currentUser = "S2").isEmpty())
        assertEquals(6, ReminderPlanner.plan(all, ReminderSettings(), now, sourcesOwner = "S1", currentUser = "S1").size)
    }

    @Test
    fun `no programa lo que sonaria en menos de 30 segundos`() {
        val soon = task.copy(end = now + 60 * 60_000L + 10_000L)   // "1 h antes" sería en 10 s
        val alarms = ReminderPlanner.plan(mapOf(ReminderType.TASK to listOf(soon)), ReminderSettings(overdue = false), now)
        assertTrue(alarms.isEmpty())
    }

    @Test
    fun `llaves y codigos identicos a los de antes del refactor`() {
        assertEquals("TAREA:1:10", task.key)
        assertEquals("TAREA:1:10|antes_60".hashCode(), ReminderPlanner.alarmCode(task.key, "antes_60"))
    }

    @Test
    fun `lee las fuentes y alarmas guardadas con los nombres de antes`() {
        val gson = Gson()
        val oldSources = """[{"tipo":"EXAMEN","idCurso":5,"idRef":7,"nombre":"Parcial","curso":"BD","inicio":1,"cierre":2,"puntos":10.0,"enEquipo":false}]"""
        val source = gson.fromJson<List<ReminderSource>>(oldSources, object : TypeToken<List<ReminderSource>>() {}.type).single()
        assertEquals(ReminderType.EXAM, source.type)
        assertEquals("EXAMEN:5:7", source.key)
        assertEquals(2L, source.end)

        val oldAlarms = """[{"codigo":42,"clave":"EXAMEN:5:7","cuando":99,"momento":"abre","minutosAntes":0}]"""
        val alarm = gson.fromJson<List<ScheduledAlarm>>(oldAlarms, object : TypeToken<List<ScheduledAlarm>>() {}.type).single()
        assertEquals(ScheduledAlarm(42, "EXAMEN:5:7", 99, "abre", 0), alarm)
        // Y al guardar se escribe igual que antes
        assertTrue(gson.toJson(source).contains("\"tipo\":\"EXAMEN\""))
    }

    @Test
    fun `duracion legible`() {
        assertEquals("2 días 3 h", ReminderPlanner.duration(51 * hour))
        assertEquals("5 h 20 min", ReminderPlanner.duration(5 * hour + 20 * 60_000L))
        assertEquals("1 min", ReminderPlanner.duration(10_000L))
    }
}
