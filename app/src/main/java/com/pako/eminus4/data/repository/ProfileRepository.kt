package com.pako.eminus4.data.repository

import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.data.model.StudentCredential
import com.pako.eminus4.data.model.UserProfile
import com.pako.eminus4.data.remote.eminus.AccountApi

/** Perfil de Eminus (con copia en el teléfono) y la credencial guardada. */
class ProfileRepository(private val api: AccountApi, private val sessionStore: SessionStore) {

    fun cached(): UserProfile = sessionStore.getUserProfile()

    fun savedCredential(): StudentCredential? = sessionStore.getStudentCredential()

    fun saveCredential(credential: StudentCredential) = sessionStore.saveStudentCredential(credential)

    /** Trae el perfil de Eminus y guarda sus datos en la sesión (sin tocar el token). */
    suspend fun refresh(): Result<UserProfile> = resultOf { api.getProfile() }.onSuccess { profile ->
        sessionStore.saveSession(
            accessToken = sessionStore.getAccessToken().orEmpty(),
            userName = profile.getNombreCompleto(),
            firstName = profile.nombre,
            userId = profile.userId,
            userEmail = profile.userEmail,
            userCampus = profile.userCampus,
            avatarUrl = profile.getFullAvatarUrl(),
            tokenExpiration = sessionStore.getUserProfile().tokenExpiration
        )
    }
}
