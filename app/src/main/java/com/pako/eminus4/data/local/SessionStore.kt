package com.pako.eminus4.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pako.eminus4.core.Constants
import com.pako.eminus4.domain.StudentId
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.StudentCredential
import com.pako.eminus4.data.model.UserProfile

/**
 * Almacenamiento local de la sesión y de lo que es de la cuenta (token, perfil, credencial, cursos
 * en caché, horario). Las llaves de SharedPreferences NO deben cambiar: los usuarios ya instalados
 * las tienen guardadas.
 */
class SessionStore(context: Context) : CourseCache {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences = context.getSharedPreferences(
        Constants.PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val KEY_STUDENT_CREDENTIAL = "key_student_credential_json"
        private const val KEY_CACHED_COURSES = "key_cached_courses_json"
        private const val KEY_HORARIO = "horario_miuv"
        private const val KEY_HORARIO_FECHA = "horario_miuv_fecha"
        private const val KEY_SESSION_EXPIRED = "key_session_expired"
        const val KEY_DARK_MODE = "key_dark_mode_enabled"
    }

    fun saveSession(
        accessToken: String,
        userName: String? = null,
        firstName: String? = null,
        userId: String? = null,
        userEmail: String? = null,
        userCampus: String? = null,
        avatarUrl: String? = null,
        tokenExpiration: String? = null,
        userRoles: String? = null
    ) {
        prefs.edit().apply {
            putString(Constants.KEY_ACCESS_TOKEN, accessToken)
            userName?.let { putString(Constants.KEY_USER_NAME, it) }
            firstName?.let { putString(Constants.KEY_FIRST_NAME, it) }
            userId?.let { putString(Constants.KEY_USER_ID, it) }
            userEmail?.let { putString(Constants.KEY_USER_EMAIL, it) }
            userCampus?.let { putString(Constants.KEY_USER_CAMPUS, it) }
            avatarUrl?.let { putString(Constants.KEY_AVATAR_URL, it) }
            tokenExpiration?.let { putString(Constants.KEY_TOKEN_EXPIRATION, it) }
            userRoles?.let { putString(Constants.KEY_USER_ROLES, it) }
            apply()
        }
    }

    fun getAccessToken(): String? {
        return prefs.getString(Constants.KEY_ACCESS_TOKEN, null)
    }

    fun getUserId(): String? {
        return prefs.getString(Constants.KEY_USER_ID, null)
    }

    fun getUserProfile(): UserProfile {
        return UserProfile(
            userId = prefs.getString(Constants.KEY_USER_ID, "") ?: "",
            nombre = prefs.getString(Constants.KEY_FIRST_NAME, "") ?: "",
            userName = prefs.getString(Constants.KEY_USER_NAME, "Estudiante") ?: "Estudiante",
            userEmail = prefs.getString(Constants.KEY_USER_EMAIL, "") ?: "",
            userCampus = prefs.getString(Constants.KEY_USER_CAMPUS, "") ?: "",
            avatarUrl = prefs.getString(Constants.KEY_AVATAR_URL, null),
            tokenExpiration = prefs.getString(Constants.KEY_TOKEN_EXPIRATION, null)
        )
    }

    fun isLoggedIn(): Boolean {
        val token = getAccessToken()
        return !token.isNullOrBlank() && token != "null"
    }

    /**
     * Determina si el dispositivo tiene una cuenta configurada (incluso si el token caducó).
     * Permite acceder a la app en modo sin conexión para ver credencial y cursos guardados.
     */
    fun hasSession(): Boolean {
        val userId = getUserId()
        return (!userId.isNullOrBlank() && userId != "null") || isLoggedIn() || hasStudentCredential()
    }

    fun isSessionExpired(): Boolean {
        return prefs.getBoolean(KEY_SESSION_EXPIRED, false)
    }

    override fun setSessionExpired(expired: Boolean) {
        prefs.edit().putBoolean(KEY_SESSION_EXPIRED, expired).apply()
    }

    override fun saveCachedCourses(courses: List<Course>) {
        val json = gson.toJson(courses)
        prefs.edit().putString(KEY_CACHED_COURSES, json).apply()
    }

    override fun getCachedCourses(): List<Course> {
        val json = prefs.getString(KEY_CACHED_COURSES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Course>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveStudentCredential(credential: StudentCredential) {
        val json = gson.toJson(credential)
        prefs.edit().putString(KEY_STUDENT_CREDENTIAL, json).apply()
    }

    fun getStudentCredential(): StudentCredential? {
        val json = prefs.getString(KEY_STUDENT_CREDENTIAL, null) ?: return null
        return try {
            gson.fromJson(json, StudentCredential::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun hasStudentCredential(): Boolean {
        return getStudentCredential() != null
    }

    /** Matrícula oficial de quien tiene la sesión ("zs24022198" -> "S24022198"), o null. */
    fun getSessionStudentId(): String? = StudentId.normalize(getUserId())

    /**
     * Credencial guardada SOLO si pertenece a quien tiene la sesión.
     * Si no hay sesión (modo sin conexión tras cerrar sesión) se devuelve la guardada.
     */
    fun getCurrentUserCredential(): StudentCredential? {
        val cred = getStudentCredential() ?: return null
        val matricula = getSessionStudentId() ?: return cred
        return if (cred.getMatriculaOficial() == matricula) cred else null
    }

    /**
     * Guarda en la credencial del usuario actual los datos oficiales leídos de MiUV.
     * Solo se sobrescribe lo que MiUV sí trajo.
     */
    fun applyMiuvData(
        datos: com.pako.eminus4.data.MiuvSync.DatosMiuv,
        nombreRespaldo: String?,
        matriculaRespaldo: String?
    ) {
        datos.horario?.let { saveSchedule(it) }
        // Recién iniciada la sesión, Eminus a veces aún no ha reportado el usuario:
        // se usa la matrícula que la persona escribió en el login.
        val matricula = getSessionStudentId() ?: normalizeStudentId(matriculaRespaldo) ?: return
        if (getSessionStudentId() == null) {
            prefs.edit().putString(Constants.KEY_USER_ID, matriculaRespaldo!!.trim().lowercase()).apply()
        }
        val base = getCurrentUserCredential() ?: StudentCredential(
            user = matricula,
            name = nombreRespaldo.orEmpty(),
            qrCode = "https://dsia.uv.mx/credencial/validar?u=$matricula",
            barCode = matricula
        )
        saveStudentCredential(
            base.copy(
                // Recién iniciada la sesión el nombre puede faltar: se completa con el de la sesión
                name = base.name.ifBlank { nombreRespaldo ?: getUserProfile().getNombreCompleto() },
                program = datos.programa ?: base.program,
                region = datos.campus ?: base.region,
                facultad = datos.facultad ?: base.facultad,
                area = datos.area ?: base.area,
                nivel = datos.nivel ?: base.nivel,
                photo = datos.fotoLocal ?: base.photo,
                qrCode = datos.qrOficial ?: base.qrCode,
                actualizadoMiuv = System.currentTimeMillis()
            )
        )
    }

    // ===================== HORARIO (MiUV) =====================
    // Se guarda con la sesión: al cerrar sesión se borra y al volver a entrar se descarga de nuevo.

    fun saveSchedule(clases: List<com.pako.eminus4.data.model.ClaseHorario>) {
        prefs.edit()
            .putString(KEY_HORARIO, gson.toJson(clases))
            .putLong(KEY_HORARIO_FECHA, System.currentTimeMillis())
            .apply()
    }

    fun getSchedule(): List<com.pako.eminus4.data.model.ClaseHorario>? {
        val json = prefs.getString(KEY_HORARIO, null) ?: return null
        return try {
            gson.fromJson(json, object : TypeToken<List<com.pako.eminus4.data.model.ClaseHorario>>() {}.type)
        } catch (_: Exception) { null }
    }

    fun getScheduleUpdatedAt(): Long = prefs.getLong(KEY_HORARIO_FECHA, 0L)

    /** "zS24022198" / "zs24022198" -> "S24022198"; null si viene vacío. */
    fun normalizeStudentId(id: String?): String? = StudentId.normalize(id)

    /** Borra la credencial guardada si es de otra persona (al iniciar sesión con otra cuenta). */
    fun discardForeignCredential(nuevoUserId: String?) {
        val cred = getStudentCredential() ?: return
        val nueva = normalizeStudentId(nuevoUserId) ?: return
        if (cred.getMatriculaOficial() != nueva) {
            prefs.edit().remove(KEY_STUDENT_CREDENTIAL).apply()
            // Fotos oficiales descargadas de MiUV de la persona anterior
            java.io.File(appContext.filesDir, "credencial").listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * "Cerrar sesión" a propósito: además de la sesión, borra lo que es de la cuenta y queda en el
     * celular (credencial y su foto, documentos abiertos/descargados, entregas y adjuntos en caché,
     * horas de término puestas a mano, novedades vistas). Así un celular prestado no conserva nada.
     * Las preferencias de la app (tema, emojis, filtros) se quedan. NO se usa cuando la sesión solo caduca.
     */
    fun clearAccountData() {
        clearSession(preserveCredential = false)
        java.io.File(appContext.filesDir, "credencial").listFiles()?.forEach { it.delete() }
        listOf("temp_docs", "entregas", "adjuntos").forEach { java.io.File(appContext.cacheDir, it).deleteRecursively() }
        appContext.getSharedPreferences("novedades", Context.MODE_PRIVATE).edit().clear().apply()
        // Horario: se borran las horas de término a mano; el ajuste "mostrar tiempo libre" se queda
        val fines = appContext.getSharedPreferences("horario_fines", Context.MODE_PRIVATE)
        val mostrarLibre = fines.getBoolean("mostrar_libre", false)
        fines.edit().clear().putBoolean("mostrar_libre", mostrarLibre).apply()
    }

    /**
     * Cierra la sesión activa. Por defecto preserva la credencial descargada
     * (la sesión puede caducar o renovarse sin que el estudiante la pierda).
     */
    fun clearSession(preserveCredential: Boolean = true) {
        val savedCredential = if (preserveCredential) getStudentCredential() else null
        val darkMode = isDarkModeEnabled()
        prefs.edit().clear().apply()
        if (savedCredential != null) {
            saveStudentCredential(savedCredential)
        }
        // Preservar también la preferencia de tema
        setDarkModeEnabled(darkMode)
    }

    fun isDarkModeEnabled(): Boolean = prefs.getBoolean(KEY_DARK_MODE, false)

    fun setDarkModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }
}
