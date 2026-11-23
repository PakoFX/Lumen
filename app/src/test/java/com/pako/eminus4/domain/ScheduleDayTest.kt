package com.pako.eminus4.domain

import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDayTest {

    private fun subject(name: String, vararg sessions: SesionClase) = ClaseHorario(materia = name, sesiones = sessions.toList())
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    private val math = subject("Mate", SesionClase("Lunes", "07:00", fin = "08:59"), SesionClase("Miercoles", "07:00"))
    private val db = subject("BD", SesionClase("Lunes", "11:00"))
    private val classes = listOf(math, db)
    private fun monday(manual: Int? = null) = ScheduleDay.blocksOf(classes, 1) { c, _ -> manual.takeIf { c === db } }

    @Test
    fun `fin de semana aparece solo con clases`() {
        assertEquals(listOf(1, 2, 3, 4, 5), ScheduleDay.visibleDays(classes))
        val withSaturday = classes + subject("Taller", SesionClase("Sábado", "09:00"))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ScheduleDay.visibleDays(withSaturday))
    }

    @Test
    fun `domingo sin clases abre en el primer dia con clases`() {
        assertEquals(1, ScheduleDay.initialDay(classes, today = 7))
        assertEquals(3, ScheduleDay.initialDay(classes, today = 3))
    }

    @Test
    fun `bloques ordenados y hora de termino a mano manda`() {
        val blocks = monday(manual = at(12, 30))
        assertEquals(listOf("Mate", "BD"), blocks.map { it.subject.materia })
        assertEquals(at(8, 59), blocks[0].end)
        assertEquals(at(12, 30), blocks[1].end)
    }

    @Test
    fun `el minuto 59 todavia cuenta como en clase`() {
        val block = monday()[0]
        assertTrue(ScheduleDay.isNow(block, at(8, 59)))
        assertFalse(ScheduleDay.hasPassed(block, monday(), at(8, 59)))
        assertTrue(ScheduleDay.hasPassed(block, monday(), at(9, 0)))
    }

    @Test
    fun `sin hora de termino pasa cuando empieza la siguiente`() {
        val noEnd = subject("X", SesionClase("Lunes", "09:00"))
        val blocks = ScheduleDay.blocksOf(listOf(noEnd, db), 1) { _, _ -> null }
        assertFalse(ScheduleDay.hasPassed(blocks[0], blocks, at(10, 30)))
        assertTrue(ScheduleDay.hasPassed(blocks[0], blocks, at(11, 0)))
    }

    @Test
    fun `tarjeta de arriba en clase, empezada o siguiente`() {
        val blocks = monday()
        assertTrue(ScheduleDay.nowCard(blocks, at(7, 30)) is NowCard.InClass)
        val next = ScheduleDay.nowCard(blocks, at(10, 15)) as NowCard.Next
        assertEquals(45, next.minutesLeft)
        assertTrue(ScheduleDay.nowCard(blocks, at(11, 20)) is NowCard.Started)
        assertNull(ScheduleDay.nowCard(blocks, at(15, 0)))
    }

    @Test
    fun `tiempo libre desde la hora en punto y no si es muy corto`() {
        val blocks = monday()
        val gap = ScheduleDay.freeGap(blocks[0], blocks[1])!!
        assertEquals(at(9), gap.first)
        assertEquals(at(11), gap.last + 1)
        assertEquals("2 h", ScheduleDay.duration(gap.last + 1 - gap.first))
        val close = ScheduleDay.blocksOf(listOf(math, subject("Y", SesionClase("Lunes", "09:05"))), 1) { _, _ -> null }
        assertNull(ScheduleDay.freeGap(close[0], close[1]))
    }

    @Test
    fun `lugar con salon y edificio`() {
        val c = ClaseHorario(materia = "BD", edificio = "A-TEEZ", salon = "105")
        assertEquals("Salón 105 · Edif. A-TEEZ", ScheduleDay.place(c, SesionClase("Lunes", "7:00")))
        assertEquals("Sala 3 · Edif. A-TEEZ", ScheduleDay.place(c, SesionClase("Lunes", "7:00", salon = "Sala 3")))
    }
}
