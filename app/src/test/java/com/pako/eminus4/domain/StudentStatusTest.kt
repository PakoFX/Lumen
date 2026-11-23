package com.pako.eminus4.domain

import com.google.gson.Gson
import com.pako.eminus4.data.model.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Casos tomados de respuestas reales de Eminus (nov-2026). */
class StudentStatusTest {

    private val gson = Gson()
    private fun act(json: String) = gson.fromJson(json, Activity::class.java)

    @Test fun abiertaNuncaTocada_esPorHacer() =
        assertEquals(StudentStatus.TO_DO, act("""{"idActividad":1,"estadoAct":2,"estado":null}""").studentStatus)

    @Test fun estadoNullNoSeConfundeConCero() =
        assertNull(act("""{"idActividad":1,"estado":null}""").estado)

    @Test fun abiertaConBorrador_esBorrador() =
        assertEquals(StudentStatus.DRAFT, act("""{"idActividad":1,"estadoAct":2,"estado":0}""").studentStatus)

    @Test fun enviadaSinRevisar_esEsperando() =
        assertEquals(StudentStatus.AWAITING_GRADE, act("""{"idActividad":1,"estadoAct":2,"estado":1}""").studentStatus)

    @Test fun cerradaEnviada_sigueEsperando() =
        assertEquals(StudentStatus.AWAITING_GRADE, act("""{"idActividad":1,"estadoAct":1,"estado":1}""").studentStatus)

    @Test fun calificada() =
        assertEquals(StudentStatus.GRADED, act("""{"idActividad":1,"estadoAct":1,"estado":3,"calificacion":9.5}""").studentStatus)

    @Test fun estado3SinNota_esCalificada() =
        assertEquals(StudentStatus.GRADED, act("""{"idActividad":1,"estadoAct":1,"estado":3}""").studentStatus)

    @Test fun calificacionCeroCuentaComoCalificada() =
        assertEquals(StudentStatus.GRADED, act("""{"idActividad":1,"estadoAct":1,"estado":3,"calificacion":0.0}""").studentStatus)

    @Test fun cerradaSinEnviar_esNoEntregada() =
        assertEquals(StudentStatus.NOT_SUBMITTED, act("""{"idActividad":1,"estadoAct":1,"estado":null}""").studentStatus)

    @Test fun cerradaConBorrador_esNoEntregada() =
        assertEquals(StudentStatus.NOT_SUBMITTED, act("""{"idActividad":1,"estadoAct":1,"estado":0}""").studentStatus)

    @Test fun prorroga_esPorHacer() =
        assertEquals(StudentStatus.TO_DO, act("""{"idActividad":1,"estadoAct":4,"fechaTermino":"2020-01-01T00:00:00"}""").studentStatus)

    @Test fun sinEstadoAct_usaFecha() {
        assertEquals(StudentStatus.NOT_SUBMITTED, act("""{"idActividad":1,"fechaTermino":"2020-01-01T00:00:00"}""").studentStatus)
        assertEquals(StudentStatus.TO_DO, act("""{"idActividad":1,"fechaTermino":"2099-01-01T00:00:00"}""").studentStatus)
    }

    @Test fun detalleNuncaTocadaNoEsBorrador() {
        val d = gson.fromJson("""{"idActividad":1,"titulo":"x","estadoAct":2,"estado":null}""",
            com.pako.eminus4.data.model.ActivityDetail::class.java)
        assertNull(d.estado)
        assertEquals(false, d.estaEntregada)
        assertEquals(false, d.estaCerrada)
    }

    @Test fun detalleEnviadaEsEntregadaYAbierta() {
        val d = gson.fromJson("""{"idActividad":1,"titulo":"x","estadoAct":2,"estado":1}""",
            com.pako.eminus4.data.model.ActivityDetail::class.java)
        assertEquals(true, d.estaEntregada)
        assertEquals(false, d.estaCerrada)
    }

    @Test fun plazos() {
        val ahora = System.currentTimeMillis()
        assertEquals(DueDates.Urgency.OVERDUE, DueDates.dueDate(ahora - 1000, ahora).urgency)
        assertEquals(DueDates.Urgency.CRITICAL, DueDates.dueDate(ahora + TimeUnit.MINUTES.toMillis(30), ahora).urgency)
        assertEquals(DueDates.Urgency.NORMAL, DueDates.dueDate(ahora + TimeUnit.DAYS.toMillis(20), ahora).urgency)
        assertEquals(DueDates.Urgency.NO_DATE, DueDates.dueDate(null, ahora).urgency)
    }
}
