package com.pako.eminus4.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.pako.eminus4.core.Constants
import com.pako.eminus4.data.local.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Sesión que Eminus dejó en el localStorage del WebView al iniciar sesión. */
data class CapturedSession(
    val token: String,
    val userName: String?,
    val userId: String?,
    val userEmail: String?,
    val userCampus: String?,
    val avatar: String?,
    val tokenExpiration: String?,
    val userRoles: String?
) {
    companion object {
        private val gson = Gson()

        /** null si no trae token (o el JSON no se entiende). */
        fun fromJson(json: String): CapturedSession? {
            val obj = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: return null
            fun text(key: String) = obj.get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
            val token = text("token")?.takeIf { it.isNotBlank() && it != "null" } ?: return null
            return CapturedSession(
                token, text("userName"), text("userId"), text("userEmail"), text("userCampus"),
                text("avatar"), text("tokenExpiration"), text("userRoles")
            )
        }
    }
}

/** Lo que pasa al iniciar sesión, fuera de la pantalla (que solo maneja el WebView). */
class AuthRepository(
    private val sessionStore: SessionStore,
    private val profiles: ProfileRepository,
    private val agenda: AgendaRepository,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Guarda la sesión nueva. Si entró otra persona, no muestra nada de la anterior. */
    fun start(session: CapturedSession) {
        sessionStore.discardForeignCredential(session.userId)
        agenda.clear()
        sessionStore.saveSession(
            accessToken = session.token,
            userName = session.userName,
            userId = session.userId,
            userEmail = session.userEmail,
            userCampus = session.userCampus,
            avatarUrl = session.avatar,
            tokenExpiration = session.tokenExpiration,
            userRoles = session.userRoles
        )
        sessionStore.setSessionExpired(false)
    }

    /**
     * Eminus a veces aún no escribió nombre/usuario en localStorage cuando aparece el token.
     * Se completan desde la API para que Perfil, saludo y credencial no queden vacíos.
     */
    suspend fun completeProfileIfMissing(session: CapturedSession): Result<Unit>? {
        if (!session.userName.isNullOrBlank() && !session.userId.isNullOrBlank()) return null
        return profiles.refresh().map { }
    }

    /** GET al perfil con ese token: 200 = el token sigue sirviendo. */
    suspend fun isTokenValid(token: String): Boolean = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("${Constants.API_GLOBAL_URL}${Constants.ENDPOINT_GET_USER}")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        runCatching { client.newCall(request).execute().use { it.code == 200 } }.getOrDefault(false)
    }

    /**
     * Renovar la sesión de la MISMA cuenta con credencial y horario de MiUV de hace menos de 7 días:
     * no hace falta volver a entrar a MiUV. "Volver a cargar mi foto/horario" (forceMiuv) siempre entra.
     */
    fun canSkipMiuv(typedUser: String, isRenewal: Boolean, forceMiuv: Boolean): Boolean {
        if (!isRenewal || forceMiuv) return false
        val credential = sessionStore.getStudentCredential() ?: return false
        val sameAccount = credential.getMatriculaOficial() == sessionStore.normalizeStudentId(typedUser)
        val recent = clock() - (credential.actualizadoMiuv ?: 0L) < MIUV_FRESH_MS
        return sameAccount && recent && sessionStore.getSchedule() != null
    }

    private companion object {
        const val MIUV_FRESH_MS = 7L * 24 * 60 * 60 * 1000
    }
}
