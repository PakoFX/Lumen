package com.pako.eminus4.domain

import com.google.gson.JsonObject

/**
 * Reglas para redactar un mensaje de Eminus. Los destinatarios son los objetos tal cual los da
 * Eminus (al enviar los espera igual), así que aquí solo se leen sus campos.
 */
object MessageDraft {

    /** Qué falta para poder enviar (null = listo). */
    enum class Problem { NO_COURSE, NO_RECIPIENTS, NO_SUBJECT, NO_BODY }

    fun validate(hasCourse: Boolean, recipients: Int, subject: String, body: String): Problem? = when {
        !hasCourse -> Problem.NO_COURSE
        recipients == 0 -> Problem.NO_RECIPIENTS
        subject.isBlank() -> Problem.NO_SUBJECT
        body.isBlank() -> Problem.NO_BODY
        else -> null
    }

    /** "Tarea 3" → "RE: Tarea 3" (sin repetir el RE:). */
    fun replySubject(original: String?): String {
        val subject = original.orEmpty().trim()
        return if (subject.startsWith("RE:", ignoreCase = true)) subject else "RE: $subject"
    }

    /** Texto plano → HTML como lo guarda el editor de Eminus (párrafos y saltos de línea). */
    fun toHtml(text: String): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return escaped.split(Regex("\n{2,}")).joinToString("") { "<p>" + it.replace("\n", "<br>") + "</p>" }
    }

    fun recipientId(o: JsonObject): String = field(o, "idUsuario")

    fun recipientEmail(o: JsonObject): String = field(o, "correo")

    /** "VIVEROS SALMONES FRANCISCO" → "Viveros Salmones Francisco" (o el correo / id si no hay nombre). */
    fun recipientName(o: JsonObject): String {
        val name = listOf("nombre", "nombreCompleto").map { field(o, it) }.firstOrNull { it.isNotBlank() }
            ?: recipientEmail(o).ifBlank { recipientId(o) }
        return name.lowercase().split(" ").filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** Participantes a los que se puede escribir: todos menos uno mismo, por nombre. */
    fun selectable(all: List<JsonObject>, myStudentId: String?): List<JsonObject> =
        all.filter { StudentId.normalize(recipientId(it)) != myStudentId }.sortedBy(::recipientName)

    private fun field(o: JsonObject, name: String): String = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
