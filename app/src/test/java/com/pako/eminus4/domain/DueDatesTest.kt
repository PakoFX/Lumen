package com.pako.eminus4.domain

import com.pako.eminus4.domain.DueDates.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DueDatesTest {

    /** Miércoles 18 nov 2026, 10:00 (hora local). */
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 18, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun `fecha de Eminus sin zona se lee como hora local`() {
        assertEquals(at(18, 23, 45), DueDates.parseEminusDate("2026-11-18T23:45:00"))
        assertEquals(at(18, 23, 45), DueDates.parseEminusDate("2026-11-18T23:45:00.123"))
    }

    @Test
    fun `fechas invalidas devuelven null`() {
        assertNull(DueDates.parseEminusDate(null))
        assertNull(DueDates.parseEminusDate(""))
        assertNull(DueDates.parseEminusDate("2026-11-18"))
        assertNull(DueDates.parseEminusDate("no es fecha aaaaaaa"))
    }

    @Test
    fun `menos de una hora se cuenta en minutos`() {
        val d = DueDates.dueDate(now + TimeUnit.MINUTES.toMillis(30), now)
        assertEquals("Vence en 30 min", d.text)
        assertEquals(Urgency.CRITICAL, d.urgency)
    }

    @Test
    fun `hoy y manana son criticos con su hora`() {
        assertEquals(DueDates.DueDate("Vence hoy, 23:45", Urgency.CRITICAL), DueDates.dueDate(at(18, 23, 45), now))
        assertEquals(DueDates.DueDate("Vence mañana, 08:00", Urgency.CRITICAL), DueDates.dueDate(at(19, 8), now))
    }

    @Test
    fun `dos o tres dias es pronto, hasta siete normal`() {
        assertEquals(Urgency.SOON, DueDates.dueDate(at(20, 12), now).urgency)
        assertTrue(DueDates.dueDate(at(20, 12), now).text.startsWith("Vence en 2 días"))
        assertEquals(Urgency.NORMAL, DueDates.dueDate(at(24, 12), now).urgency)
    }

    @Test
    fun `vencida y sin fecha`() {
        assertEquals(Urgency.OVERDUE, DueDates.dueDate(now - 1000, now).urgency)
        assertEquals(DueDates.DueDate("Sin fecha límite", Urgency.NO_DATE), DueDates.dueDate(null, now))
    }

    @Test
    fun `hace cuanto`() {
        assertEquals("ahora", DueDates.timeAgo(now - 10_000, now))
        assertEquals("hace 5 min", DueDates.timeAgo(now - TimeUnit.MINUTES.toMillis(5), now))
        assertEquals("hace 3 h", DueDates.timeAgo(now - TimeUnit.HOURS.toMillis(3), now))
        assertEquals("ayer", DueDates.timeAgo(at(17, 9), now))
        assertEquals("hace 4 días", DueDates.timeAgo(at(14, 9), now))
        assertEquals("", DueDates.timeAgo(null, now))
    }
}
