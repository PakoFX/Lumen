package com.pako.eminus4.exams

import com.google.gson.Gson
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.domain.ExamRules
import com.pako.eminus4.ui.exams.ExamBlocks
import com.pako.eminus4.ui.exams.FilaExamen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ExamBlocksTest {

    private val now = System.currentTimeMillis()
    private fun iso(offsetDays: Int) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now + offsetDays * 86_400_000L)

    private fun exam(id: Long, course: Long, start: Int, end: Int, grade: Double? = null) =
        ExamItem(id, "Examen $id", 10.0, iso(start), iso(end), calificacion = grade, idCurso = course, nombreCurso = "Materia $course")

    private fun course(id: Long, active: Boolean): Course = Gson().fromJson(
        """{"idCurso":$id,"nombre":"M$id","fechaTerminoEpoch":${if (active) 0 else 1}}""", Course::class.java
    )

    private val courses = mapOf(1L to course(1, true), 2L to course(2, true), 3L to course(3, false))

    @Test
    fun `reglas de examen disponible y proximo`() {
        assertTrue(ExamRules.isAvailable(exam(1, 1, start = -1, end = 2), now))
        assertTrue(ExamRules.isUpcoming(exam(1, 1, start = 2, end = 3), now))
        assertFalse(ExamRules.isAvailable(exam(1, 1, start = -1, end = 2, grade = 9.0), now))
    }

    @Test
    fun `bloques de materias activas con la que tiene disponibles primero y plegados`() {
        val exams = listOf(exam(1, 1, 2, 3), exam(2, 2, -1, 2), exam(3, 3, -9, -8, grade = 8.0))
        val result = ExamBlocks.build(exams, courses, showPast = false, expanded = emptySet(), now = now)
        assertEquals(2, result.activeSubjects)
        assertEquals(1, result.pastSubjects)
        val headers = result.rows.filterIsInstance<FilaExamen.Encabezado>()
        assertEquals(listOf(2L, 1L), headers.map { it.bloque.idCurso })
        assertEquals("1 disponible", headers.first().bloque.aviso)
        assertEquals(2, result.rows.size)                  // plegados: solo encabezados
    }

    @Test
    fun `desplegar una materia muestra sus examenes y una sola materia se abre sola`() {
        val exams = listOf(exam(1, 1, 2, 3), exam(2, 1, -1, 2), exam(3, 2, -1, 2))
        val expanded = ExamBlocks.build(exams, courses, showPast = false, expanded = setOf(1L), now = now)
        assertEquals(4, expanded.rows.size)
        val single = ExamBlocks.build(exams.filter { it.idCurso == 1L }, courses, false, emptySet(), now)
        assertEquals(3, single.rows.size)
        assertEquals(2L, (single.rows[1] as FilaExamen.Examen).examen.idExamen)   // disponible antes que próximo
    }

    @Test
    fun `materias pasadas`() {
        val exams = listOf(exam(3, 3, -9, -8, grade = 8.0))
        val result = ExamBlocks.build(exams, courses, showPast = true, expanded = emptySet(), now = now)
        assertEquals("1 examen · 1 calificado", (result.rows.first() as FilaExamen.Encabezado).bloque.resumen)
    }
}
