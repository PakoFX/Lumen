package com.pako.eminus4.domain

/**
 * Situación de una actividad DESDE EL PUNTO DE VISTA DEL ALUMNO.
 * Es el único criterio que deben usar todas las pantallas (Avisos, lista del curso, detalle).
 *
 * Reglas (valores observados en vivo en Eminus, nov-2026):
 *  - estadoAct: 1 = cerrada, 2 = abierta, 4 = abierta con prórroga.
 *  - estado:    null = nunca tocada, 0 = borrador sin enviar, 1 = enviada sin revisar,
 *               3 = calificada/revisada, 4 = calificada (visto en actividad por equipo).
 *
 * El ícono de cada estado vive en la capa de UI (`ui/common/StudentStatusUi.kt`).
 */
enum class StudentStatus(val label: String) {
    TO_DO("Por hacer"),
    DRAFT("Borrador sin enviar"),
    AWAITING_GRADE("Esperando calificación"),
    GRADED("Calificada"),
    NOT_SUBMITTED("No entregada");

    /** Cuenta como trabajo pendiente del alumno (lo que aparece en la agenda). */
    val isPending: Boolean get() = this == TO_DO || this == DRAFT
}
