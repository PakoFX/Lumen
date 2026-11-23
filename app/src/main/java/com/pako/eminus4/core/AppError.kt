package com.pako.eminus4.core

import kotlinx.coroutines.CancellationException

/**
 * Errores de la app con significado propio, en lugar de revisar textos de excepción.
 * Los mensajes se conservan como antes ("Sesión expirada (401)", "Error de Eminus (500)")
 * porque algunas pantallas aún no migradas los muestran o buscan "401" en ellos.
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Eminus respondió 401: el token caducó y hay que renovar la sesión. */
    class SessionExpired : AppError("Sesión expirada (401)")

    /** No hubo respuesta (sin internet, tiempo agotado, DNS…). */
    class Network(cause: Throwable) : AppError(cause.message ?: "Sin conexión", cause)

    /** El servidor respondió con un código de error distinto de 401. */
    class Server(val code: Int) : AppError("Error de Eminus ($code)")

    /** Eminus rechazó un archivo por tamaño (HTTP 413). */
    class FileTooLarge : AppError("El archivo es demasiado grande para Eminus")

    /** Eminus respondió bien pero sin el dato esperado (p. ej. el número de la entrega creada). */
    class MissingData(what: String) : AppError(what)

    /** La respuesta llegó pero no tiene la forma esperada. */
    class Parsing(cause: Throwable) : AppError("Respuesta inesperada de Eminus", cause)
}

/** Sesión caducada, venga como [AppError.SessionExpired] o como error antiguo con "401" en el texto. */
fun Throwable.isSessionExpired(): Boolean =
    this is AppError.SessionExpired || message?.contains("401") == true

/** Sin conexión (error nuevo [AppError.Network] o IOException directa de OkHttp). */
fun Throwable.isNetworkError(): Boolean = this is AppError.Network || this is java.io.IOException

/**
 * Como [runCatching], pero sin tragarse la cancelación de corrutinas (si una pantalla se cierra,
 * su trabajo debe cancelarse, no convertirse en un error).
 */
inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
