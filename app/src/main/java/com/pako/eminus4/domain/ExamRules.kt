package com.pako.eminus4.domain

import com.pako.eminus4.data.model.ExamItem

/** Situación de un examen para el alumno (Eminus solo da fechas, calificación y estado). */
object ExamRules {

    /** Ya abrió, no ha cerrado y no tiene calificación: se puede presentar. */
    fun isAvailable(exam: ExamItem, now: Long = System.currentTimeMillis()) =
        !exam.estaCalificado && !exam.estaVencido && (DueDates.parseEminusDate(exam.fechaInicio) ?: 0L) <= now

    /** Los que se pueden presentar ahora, el que cierra primero arriba. */
    fun availableByClosing(exams: List<ExamItem>, now: Long = System.currentTimeMillis()): List<ExamItem> =
        exams.filter { isAvailable(it, now) }.sortedBy { DueDates.parseEminusDate(it.fechaTermino) ?: Long.MAX_VALUE }

    /** Todavía no abre. */
    fun isUpcoming(exam: ExamItem, now: Long = System.currentTimeMillis()) =
        !exam.estaCalificado && (DueDates.parseEminusDate(exam.fechaInicio) ?: 0L) > now
}
