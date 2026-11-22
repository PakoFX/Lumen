package com.pako.eminus4.util

import java.text.Normalizer

/**
 * Nombres de materia cortos para los chips de filtro (ahorrar pantalla):
 *   "ADMINISTRACION DE PROYECTOS DE SOFTWARE"   → "Admón. Proyectos"
 *   "FUNDAMENTOS DE PRUEBA DE SOFTWARE"         → "Fund. Prueba Software"
 *   "TECNOLOGIAS PARA LA CONSTRUCCION DE SOFTW" → "Tec. Construcción"
 * Se quitan las palabras de enlace, se abrevian las palabras largas y comunes,
 * y se corta a ~20 caracteres sin partir palabras.
 */
object NombreMateria {

    private const val MAX = 20

    private val enlaces = setOf("de", "del", "la", "las", "el", "los", "y", "e", "en", "para", "con", "por", "a", "al", "o", "u", "su", "sus")

    // Abreviaturas usuales (clave sin acentos, en minúsculas)
    private val abreviaturas = mapOf(
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

    private fun sinAcentos(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun titulo(w: String) = w.lowercase().replaceFirstChar { it.uppercase() }

    /** Nombre completo en formato título ("Administración de Proyectos…"), para listas con espacio. */
    fun bonito(nombre: String?): String {
        val limpio = nombre.orEmpty().replace(Regex("""\s*\(\d+\)\s*$"""), "").trim()
        return limpio.split(Regex("\\s+")).filter { it.isNotBlank() }.mapIndexed { i, w ->
            if (i > 0 && sinAcentos(w.lowercase()) in enlaces) w.lowercase() else titulo(w)
        }.joinToString(" ")
    }

    fun corto(nombre: String?): String {
        val limpio = nombre.orEmpty().replace(Regex("""\s*\(\d+\)\s*$"""), "").trim()
        if (limpio.isEmpty()) return "Materia"
        val palabras = limpio.split(Regex("\\s+")).filter { it.isNotBlank() }
        val significativas = palabras.filter { sinAcentos(it.lowercase()) !in enlaces }.ifEmpty { palabras }

        // Si ya es corto, solo se pone bonito
        val completo = significativas.joinToString(" ") { titulo(it) }
        if (completo.length <= MAX) return completo

        val partes = significativas.mapIndexed { i, w ->
            val clave = sinAcentos(w.lowercase()).trimEnd('.')
            abreviaturas[clave] ?: if (i == 0 && w.length > 9) titulo(w).take(5) + "." else titulo(w)
        }
        val sb = StringBuilder()
        for (p in partes) {
            val siguiente = if (sb.isEmpty()) p else "$sb $p"
            if (siguiente.length > MAX && sb.isNotEmpty()) break
            sb.clear(); sb.append(siguiente)
        }
        return sb.toString()
    }
}
