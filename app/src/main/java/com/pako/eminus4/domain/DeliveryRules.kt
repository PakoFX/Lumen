package com.pako.eminus4.domain

import com.pako.eminus4.data.model.ActivityDetail

/** Escapa texto para meterlo en HTML (comentarios y entregas). */
object HtmlText {
    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}

/** Reglas de las entregas de tareas (lo que Eminus web también exige). */
object DeliveryRules {

    /** Por qué no se puede entregar/modificar ahora, o null si sí se puede. */
    fun blockReason(detail: ActivityDetail): String? = when {
        detail.esPorEquipos -> "Las actividades por equipo deben entregarse desde Eminus web."
        !detail.estaCerrada -> null
        detail.estaCalificada || detail.estado == 3 -> "La actividad ya fue calificada; Eminus no admite cambios."
        else -> "La actividad ya cerró; Eminus no admite nuevas entregas."
    }

    /**
     * Eminus exige texto en la entrega; se manda como HTML (el editor web es Froala).
     * Si el alumno no tocó el comentario precargado, se reenvía el HTML original intacto
     * (conserva enlaces, formato e imágenes que la versión en texto plano perdería).
     */
    fun deliveryHtml(previousHtml: String?, comment: String, preloadedComment: String?, fileName: String): String = when {
        !previousHtml.isNullOrBlank() && (comment.isBlank() || comment == preloadedComment) -> previousHtml
        comment.isNotBlank() -> comment.lines().joinToString("") { "<p>${HtmlText.escape(it)}</p>" }
        else -> "<p>Entrega: ${HtmlText.escape(fileName)}</p>"
    }
}
