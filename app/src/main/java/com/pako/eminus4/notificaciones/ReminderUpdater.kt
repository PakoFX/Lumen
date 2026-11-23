package com.pako.eminus4.notificaciones

import android.content.Context
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem

/**
 * Punto de entrada para reprogramar recordatorios cuando llegan datos completos de Eminus.
 * Es una interfaz para que los ViewModel no dependan de Android (y se puedan probar).
 */
interface ReminderUpdater {
    fun tasks(activities: List<CourseActivityItem>)
    fun exams(exams: List<ExamItem>)
    fun forums(forums: List<ForumItem>)
}

/** Implementación real: alarmas del sistema vía [Recordatorios]. */
class AlarmReminderUpdater(private val context: Context) : ReminderUpdater {
    override fun tasks(activities: List<CourseActivityItem>) = Recordatorios.actualizarTareas(context, activities)
    override fun exams(exams: List<ExamItem>) = Recordatorios.actualizarExamenes(context, exams)
    override fun forums(forums: List<ForumItem>) = Recordatorios.actualizarForos(context, forums)
}
