package com.pako.eminus4.data.model

/**
 * Horario de clases de MiUV (`portales/estudiantes/szihorc.aspx`), leído al iniciar sesión.
 * Cada materia trae sus sesiones por día con hora y salón.
 */
data class ClaseHorario(
    val materia: String = "",
    val nrc: String? = null,
    val profesor: String? = null,
    val edificio: String? = null,
    val salon: String? = null,
    val creditos: String? = null,
    val modalidad: String? = null,
    val sesiones: List<SesionClase> = emptyList()
)

data class SesionClase(
    /** "Lunes", "Martes", "Miercoles"… tal como lo nombra MiUV. */
    val dia: String = "",
    /** Hora de inicio tal como la da la página de MiUV (p. ej. "16:00"). */
    val hora: String = "",
    /** Salón de ese día, si MiUV lo trae. */
    val salon: String? = null,
    /** Hora de término: la página no la trae; se completa con el PDF de "Imprimir" (`HorarioPdf`). */
    val fin: String? = null
) {
    /** 1 = lunes … 7 = domingo (0 si no se reconoce). */
    val numeroDia: Int get() = when (dia.lowercase().replace("é", "e").replace("á", "a").take(3)) {
        "lun" -> 1; "mar" -> 2; "mie" -> 3; "jue" -> 4; "vie" -> 5; "sab" -> 6; "dom" -> 7
        else -> 0
    }

    // Sin "by lazy": Gson guardaría el objeto interno del lazy y luego no podría leer el horario
    private fun minutos(): List<Int> =
        Regex("""(\d{1,2})\s*:\s*(\d{2})""").findAll(hora + " " + fin.orEmpty())
            .map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            .filter { it in 0..(24 * 60) }.toList()

    /** Minutos desde medianoche (para ordenar y saber si está en curso). */
    val inicioMin: Int? get() = minutos().getOrNull(0)
    val finMin: Int? get() = minutos().getOrNull(1)

    fun rango(): String {
        val i = inicioMin ?: return hora
        val f = finMin
        fun hhmm(m: Int) = "%d:%02d".format(m / 60, m % 60)
        return if (f != null) "${hhmm(i)} – ${hhmm(f)}" else hhmm(i)
    }
}
