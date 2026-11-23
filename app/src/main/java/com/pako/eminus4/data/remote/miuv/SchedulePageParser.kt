package com.pako.eminus4.data.remote.miuv

import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase

/**
 * Horario de la página `szihorc.aspx` (misma lógica que el JS de MiuvSync al iniciar sesión):
 * por materia `…_dlHorario_*_{i}` y sus días en `…_dlHorariosDias_{i}`. La página solo trae la hora
 * de INICIO de cada clase; la de término la completa [SchedulePdfParser].
 */
object SchedulePageParser {

    private const val MAX_SUBJECTS = 80
    private val PREFIX = Regex("""\bid="(\w*?_dlHorario_)divEncabezadoTile_\d+"""")
    private val DAY_ID = Regex("""_lblCampo(Lunes|Martes|Miercoles|Jueves|Viernes|Sabado|Domingo)_""")
    private val TIME = Regex("""\d{1,2}:\d{2}""")
    private val ONLY_TIME = Regex("""^\d{1,2}:\d{2}$""")
    private val ROOM_TRIM = Regex("""^[\s\-–a]+|[\s\-–]+$""")

    fun parse(html: String): List<ClaseHorario> {
        // Prefijo de los ids ("content_ctl00_dlHorario_"), tomado de la página por si MiUV lo cambia
        val prefix = PREFIX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val elements = HtmlElements.withIds(html)
        val byId = elements.associateBy { it.id }
        val spans = elements.filter { it.tag == "span" }
        fun text(id: String) = byId[id]?.text.orEmpty()

        return (0 until MAX_SUBJECTS)
            .takeWhile { html.contains("${prefix}divEncabezadoTile_$it\"") }
            .mapNotNull { i ->
                val name = text("${prefix}lblCampoExp_$i").ifBlank { return@mapNotNull null }
                val block = parseDaysBlock(spans.filter { it.id.contains("${prefix}dlHorariosDias_${i}_") })
                ClaseHorario(
                    materia = name,
                    nrc = text("${prefix}lblCampoNRC_$i").ifBlank { null },
                    profesor = block.teacher, edificio = block.building, salon = block.room,
                    creditos = listOf(text("${prefix}lblCampoCreditos_$i"), text("${prefix}lblCreditos_$i")).firstOrNull { it.any(Char::isDigit) },
                    modalidad = text("${prefix}lblModalidad_$i").ifBlank { null },
                    sesiones = block.sessions
                )
            }
    }

    private class DaysBlock {
        val sessions = mutableListOf<SesionClase>()
        var teacher: String? = null
        var building: String? = null
        var room: String? = null
    }

    /** Los spans de un bloque van en orden: el día abre una sesión; lo que sigue la completa (fin, salón). */
    private fun parseDaysBlock(spans: List<HtmlElements.Element>): DaysBlock {
        val block = DaysBlock()
        for (span in spans) {
            val day = DAY_ID.find(span.id)?.groupValues?.get(1)
            val last = block.sessions.lastOrNull()
            when {
                day != null -> block.sessions.add(SesionClase(dia = day, hora = span.text))
                last != null && span.cssClass.contains("lblDiaSalon") -> {
                    val times = TIME.findAll(span.text).map { it.value }.toList()
                    val room = span.text.replace(TIME, "").replace(ROOM_TRIM, "").trim()
                    block.sessions[block.sessions.lastIndex] =
                        last.copy(fin = times.lastOrNull() ?: last.fin, salon = room.ifBlank { last.salon })
                }
                last != null && ONLY_TIME.matches(span.text) && last.fin.isNullOrBlank() ->
                    block.sessions[block.sessions.lastIndex] = last.copy(fin = span.text)
                span.id.contains("_lblCampoAcademico_") && block.teacher == null -> block.teacher = span.text
                span.id.contains("_lblCampoEdificio_") && block.building == null -> block.building = span.text
                span.id.contains("_lblCampoSalon_") && block.room == null -> block.room = span.text
            }
        }
        return block
    }
}
