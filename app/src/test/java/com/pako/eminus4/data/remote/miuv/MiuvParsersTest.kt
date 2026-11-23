package com.pako.eminus4.data.remote.miuv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Páginas SINTÉTICAS con la misma estructura que MiUV (nunca datos reales de nadie). */
class MiuvParsersTest {

    private val p = "content_ctl00_dlHorario_"

    private fun subject(i: Int, name: String, nrc: String, days: String) = """
        <div id="${p}divEncabezadoTile_$i" class="tile">
          <span id="${p}lblCampoExp_$i"><span class="x">$name</span></span>
          <span id="${p}lblCampoNRC_$i">$nrc</span>
          <span id="${p}lblCampoCreditos_$i">8</span>
          <span id="${p}lblModalidad_$i">Presencial</span>
          <div id="${p}dlHorariosDias_$i">
            <span id="${p}dlHorariosDias_${i}_lblCampoAcademico_0">Profe Ejemplo</span>
            $days
            <span id="${p}dlHorariosDias_${i}_lblCampoEdificio_0">Edificio A</span>
            <span id="${p}dlHorariosDias_${i}_lblCampoSalon_0">CC1</span>
          </div>
        </div>"""

    private fun day(i: Int, name: String, time: String) =
        """<span id="${p}dlHorariosDias_${i}_lblCampo${name}_0">$time</span>"""

    private val page = """<html><form><input type="hidden" name="__VIEWSTATE" value="abc&amp;123" />
        ${subject(0, "BASES DE DATOS", "11111", day(0, "Lunes", "16:00") + day(0, "Miercoles", "16:00"))}
        ${subject(1, "REDES &amp; MAS", "22222", day(1, "Sabado", "07:00"))}
        </form></html>"""

    @Test
    fun `lee materias, nrc, profesor y dias con hora de inicio`() {
        val schedule = SchedulePageParser.parse(page)
        assertEquals(2, schedule.size)
        val first = schedule[0]
        assertEquals("BASES DE DATOS", first.materia)          // nombre dentro de un span anidado
        assertEquals("11111", first.nrc)
        assertEquals("Profe Ejemplo", first.profesor)
        assertEquals("CC1", first.salon)
        assertEquals(listOf("Lunes" to "16:00", "Miercoles" to "16:00"), first.sesiones.map { it.dia to it.hora })
        assertEquals("REDES & MAS", schedule[1].materia)        // entidades HTML
        assertEquals(6, schedule[1].sesiones.single().numeroDia)
    }

    @Test
    fun `sin bloques de horario no hay materias`() {
        assertTrue(SchedulePageParser.parse("<html><input type=\"hidden\" name=\"__VIEWSTATE\" value=\"x\"></html>").isEmpty())
    }

    @Test
    fun `campos ocultos para el postback de Imprimir`() {
        assertEquals(listOf("__VIEWSTATE" to "abc&123"), HtmlElements.hiddenFields(page))
    }

    @Test
    fun `el PDF completa la hora de termino tal cual (##_59) por NRC y dia`() {
        val schedule = SchedulePageParser.parse(page)
        val pdf = """HORARIO
            11111 BASES DE DATOS 16:00-17:59 - 16:00-17:59 - - -
            22222 REDES - - - - - 07:00-08:59
            Total de créditos 16
            11111 otra tabla 16:00-18:00"""
        val result = SchedulePdfParser.applyEndTimes(schedule, pdf)
        assertEquals(3, result.completed)
        assertEquals(listOf("17:59", "17:59"), result.schedule[0].sesiones.map { it.fin })
        assertEquals("08:59", result.schedule[1].sesiones.single().fin)
    }

    @Test
    fun `NRC que no aparece en el PDF se queda sin hora de termino`() {
        val result = SchedulePdfParser.applyEndTimes(SchedulePageParser.parse(page), "99999 nada 10:00-11:59")
        assertEquals(0, result.completed)
        assertNull(result.schedule[0].sesiones[0].fin)
    }

    @Test
    fun `foto de la credencial con charset en medio del data uri`() {
        val html = """<img id="content_imgFoto2" src="data:image/jpeg;charset=utf-8;base64,QUJD" />"""
        assertEquals("QUJD", CredentialPageParser.photoBase64(html))
        assertNull(CredentialPageParser.photoBase64("""<img id="content_imgFoto2" src="foto.jpg" />"""))
        assertTrue(CredentialPageParser.isCredentialPage(html))
    }
}
