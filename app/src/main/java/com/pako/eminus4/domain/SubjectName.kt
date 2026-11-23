package com.pako.eminus4.domain

import java.text.Normalizer

/**
 * Nombres de materia legibles:
 *  - [displayName]: formato título ("Administración de Proyectos de"), para listas con espacio.
 *  - [shortName]: ≤ 20 caracteres para chips ("ADMINISTRACION DE PROYECTOS DE SOFTWARE" → "Admón. Proyectos").
 * Se quitan las palabras de enlace, se abrevian las palabras largas y comunes, y se corta sin partir palabras.
 */
object SubjectName {

    private const val MAX_SHORT = 20
    private const val MIN_PREFIX = 6
    private val NRC_SUFFIX = Regex("""\s*\(\d+\)\s*$""")
    private val SPACES = Regex("\\s+")

    private val connectors = setOf(
        "de", "del", "la", "las", "el", "los", "y", "e", "en", "para", "con", "por", "a", "al", "o", "u", "su", "sus"
    )

    // Abreviaturas usuales (clave sin acentos, en minúsculas)
    private val abbreviations = mapOf(
        "administracion" to "Admón.", "fundamentos" to "Fund.", "tecnologias" to "Tec.", "tecnologia" to "Tec.",
        "introduccion" to "Intro.", "programacion" to "Prog.", "desarrollo" to "Des.", "ingenieria" to "Ing.",
        "sistemas" to "Sist.", "sistema" to "Sist.", "aspectos" to "Asp.", "experiencia" to "Exp.",
        "recepcional" to "Recep.", "metodologia" to "Metod.", "investigacion" to "Inv.", "matematicas" to "Mat.",
        "estadistica" to "Estad.", "comunicacion" to "Com.", "organizacional" to "Org.", "organizacion" to "Org.",
        "informacion" to "Info.", "computacion" to "Comp.", "aplicaciones" to "Apps", "aplicacion" to "App",
        "contabilidad" to "Contab.", "habilidades" to "Hab.", "pensamiento" to "Pens.", "lectura" to "Lect.",
        "redaccion" to "Redac.", "principios" to "Princ.", "seminario" to "Sem.", "servicio" to "Serv.",
        "construccion" to "Construcción", "seguridad" to "Seguridad", "practicas" to "Prácticas",
        "profesionales" to "Prof.", "profesional" to "Prof.", "avanzada" to "Av.", "avanzado" to "Av.",
        "optativa" to "Opt.", "taller" to "Taller", "bases" to "Bases", "datos" to "Datos"
    )

    fun displayName(name: String?): String =
        words(name).mapIndexed { i, w -> if (i > 0 && isConnector(w)) w.lowercase() else titleCase(w) }.joinToString(" ")

    fun shortName(name: String?): String {
        val words = words(name)
        if (words.isEmpty()) return "Materia"
        val meaningful = words.filterNot(::isConnector).ifEmpty { words }

        val full = meaningful.joinToString(" ") { titleCase(it) }
        if (full.length <= MAX_SHORT) return full

        val parts = meaningful.mapIndexed { i, w ->
            abbreviationFor(w) ?: if (i == 0 && w.length > 9) titleCase(w).take(5) + "." else titleCase(w)
        }
        return fitWithin(parts, MAX_SHORT)
    }

    /** Eminus corta los nombres ("CONSTRUCCI"): una palabra cortada de ≥ 6 letras también se abrevia. */
    private fun abbreviationFor(word: String): String? {
        val key = normalized(word).trimEnd('.')
        return abbreviations[key]
            ?: abbreviations.entries.firstOrNull { key.length >= MIN_PREFIX && it.key.startsWith(key) }?.value
    }

    /** Junta palabras mientras quepan (siempre al menos la primera). */
    private fun fitWithin(parts: List<String>, max: Int): String {
        var result = ""
        for (part in parts) {
            val next = if (result.isEmpty()) part else "$result $part"
            if (next.length > max && result.isNotEmpty()) break
            result = next
        }
        return result
    }

    private fun words(name: String?): List<String> =
        name.orEmpty().replace(NRC_SUFFIX, "").trim().split(SPACES).filter { it.isNotBlank() }

    private fun isConnector(word: String) = normalized(word) in connectors

    private fun normalized(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun titleCase(w: String) = w.lowercase().replaceFirstChar { it.uppercase() }
}
