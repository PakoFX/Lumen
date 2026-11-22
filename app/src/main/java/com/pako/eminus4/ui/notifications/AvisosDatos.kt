package com.pako.eminus4.ui.notifications

import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.model.MessageItem

/**
 * Lo último que cargó Avisos, para que la pantalla de Pendientes lo desglose al instante
 * sin volver a consultar Eminus. Se actualiza cada vez que Avisos repinta.
 */
object AvisosDatos {
    @Volatile var cursos: List<Course> = emptyList()
    @Volatile var actividades: List<CourseActivityItem> = emptyList()
    /** Exámenes que se pueden presentar ahora. */
    @Volatile var examenesDisponibles: List<ExamItem> = emptyList()
    /** Foros con aportes nuevos. */
    @Volatile var forosNuevos: List<ForumItem> = emptyList()
    @Volatile var mensajesNoLeidos: List<MessageItem> = emptyList()

    val hayDatos get() = cursos.isNotEmpty()

    fun limpiar() {
        cursos = emptyList(); actividades = emptyList(); examenesDisponibles = emptyList()
        forosNuevos = emptyList(); mensajesNoLeidos = emptyList()
    }

    /** Mensaje de esa materia (Eminus solo trae el nombre del curso en el mensaje). */
    fun esDeMateria(m: MessageItem, c: Course) =
        m.curso != null && m.getCursoLimpio().equals(c.getNombreLimpio(), ignoreCase = true)
}
