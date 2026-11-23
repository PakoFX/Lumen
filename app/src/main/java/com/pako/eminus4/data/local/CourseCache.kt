package com.pako.eminus4.data.local

import com.pako.eminus4.data.model.Course

/** Copia local de los cursos y del estado de la sesión (lo implementa [SessionStore]). */
interface CourseCache {
    fun getCachedCourses(): List<Course>
    fun saveCachedCourses(courses: List<Course>)
    fun setSessionExpired(expired: Boolean)
}
