package com.pako.eminus4.domain

/**
 * Tras cerrar sesión o un 401 no se debe reutilizar el token viejo que Eminus deja en localStorage.
 * Esta "puerta" decide si un token que aparece en el WebView sale de un inicio de sesión nuevo.
 * Se abre al ver la pantalla de login, al ver el almacenamiento vacío o con un token distinto al caducado.
 */
class FreshLoginGate(private val expiredToken: String?) {

    enum class Decision {
        ACCEPT,
        REJECT,
        /** Es el mismo token "caducado": puede que nunca caducara (401 pasajero). Preguntar al servidor. */
        VALIDATE_WITH_SERVER
    }

    /** true = solo vale un token de un inicio de sesión nuevo. */
    var waitingForFreshLogin: Boolean = false
        private set

    fun requireFreshLogin() {
        waitingForFreshLogin = true
        sawLoginPage = false
    }

    fun allowAnyToken() {
        waitingForFreshLogin = false
    }

    private var sawLoginPage = false
    private var sawEmptyStorage = false

    /** Pasó por la pantalla de login institucional: cualquier token posterior es nuevo. */
    fun onLoginPageSeen() {
        sawLoginPage = true
        waitingForFreshLogin = false
    }

    /** El login de Eminus vive dentro de eminus4/ sin cambiar de URL: ver el almacenamiento vacío también cuenta. */
    fun onEmptyStorageSeen() {
        sawEmptyStorage = true
    }

    fun decide(token: String): Decision {
        if (!waitingForFreshLogin) return Decision.ACCEPT
        val fresh = (!expiredToken.isNullOrBlank() && token != expiredToken) || sawEmptyStorage || sawLoginPage
        if (fresh) {
            waitingForFreshLogin = false
            return Decision.ACCEPT
        }
        return if (!expiredToken.isNullOrBlank() && token == expiredToken) Decision.VALIDATE_WITH_SERVER else Decision.REJECT
    }

    /** El servidor confirmó que el token "caducado" sigue sirviendo. */
    fun onTokenConfirmedValid() {
        waitingForFreshLogin = false
    }
}
