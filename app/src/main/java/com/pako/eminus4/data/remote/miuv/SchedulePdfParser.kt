package com.pako.eminus4.data.remote.miuv

import com.pako.eminus4.data.model.ClaseHorario

/**
 * Completa la hora de término de cada clase con el texto del PDF de "Imprimir" de MiUV
 * (Reporting Services, `rep=horCreditos`). Cada materia aparece con su NRC y renglones de
 * 6 casillas LUN…SAB: "16:00-17:59" o " - ". La hora de término se guarda tal cual (17:59).
 */
object SchedulePdfParser {

    data class Result(val schedule: List<ClaseHorario>, val completed: Int, val total: Int)

    private data class TimeRange(val dayIndex: Int, val start: Int, val end: Int)

    private const val DAYS_PER_ROW = 6
    private val CELL = Regex("""(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})|(?:^|\s)-(?=\s|$)""")

    fun applyEndTimes(base: List<ClaseHorario>, pdfText: String): Result {
        val table = pdfText.substringBefore("Total de cr")   // la segunda tabla (direcciones) no interesa
        val positions = base.mapNotNull { it.nrc?.takeIf(String::isNotBlank) }.distinct()
            .mapNotNull { nrc -> Regex("""\b$nrc\b""").find(table)?.range?.first?.let { nrc to it } }
            .sortedBy { it.second }

        var completed = 0
        var total = 0
        val schedule = base.map { subject ->
            val index = positions.indexOfFirst { it.first == subject.nrc }
            if (index < 0) return@map subject
            val segment = table.substring(positions[index].second, positions.getOrNull(index + 1)?.second ?: table.length)
            val ranges = parseRanges(segment)
            subject.copy(sesiones = subject.sesiones.map { session ->
                total++
                val start = session.inicioMin ?: return@map session
                val dayIndex = session.numeroDia - 1   // 0 = lunes
                val end = (ranges.firstOrNull { it.dayIndex == dayIndex && it.start == start }
                    ?: ranges.firstOrNull { it.start == start })?.end
                if (end != null) { completed++; session.copy(fin = hhmm(end)) } else session
            })
        }
        return Result(schedule, completed, total)
    }

    /**
     * Se respeta la posición de cada casilla para saber el día; si el conteo no cuadra con renglones
     * de 6, el día queda en -1 y se empareja solo por hora de inicio.
     */
    private fun parseRanges(segment: String): List<TimeRange> {
        val cells = CELL.findAll(segment).toList()
        val aligned = cells.isNotEmpty() && cells.size % DAYS_PER_ROW == 0
        return cells.mapIndexedNotNull { k, m ->
            val g = m.groupValues
            if (g[1].isEmpty()) return@mapIndexedNotNull null
            TimeRange(if (aligned) k % DAYS_PER_ROW else -1, g[1].toInt() * 60 + g[2].toInt(), g[3].toInt() * 60 + g[4].toInt())
        }
    }

    private fun hhmm(minutes: Int) = "%02d:%02d".format((minutes / 60) % 24, minutes % 60)
}
