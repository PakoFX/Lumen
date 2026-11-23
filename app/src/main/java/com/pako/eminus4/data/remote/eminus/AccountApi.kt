package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.core.AppError
import com.pako.eminus4.core.Constants
import com.pako.eminus4.data.model.NotificationSummary
import com.pako.eminus4.data.model.UserProfile

/** Datos de la cuenta: perfil y contadores globales de notificaciones. */
class AccountApi(private val http: EminusHttp, private val json: JsonMapper) {

    suspend fun getProfile(): UserProfile =
        json.obj<UserProfile>(http.get(Constants.ENDPOINT_GET_USER, "getUsuario"))
            ?: throw AppError.MissingData("Eminus no devolvió el perfil")

    suspend fun getNotifications(): NotificationSummary =
        json.obj<NotificationSummary>(http.get(Constants.ENDPOINT_NOTIFICATIONS, "NotificacionesUsuario"))
            ?: throw AppError.MissingData("Eminus no devolvió las notificaciones")
}
