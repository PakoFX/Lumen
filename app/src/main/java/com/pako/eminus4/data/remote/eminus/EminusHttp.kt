package com.pako.eminus4.data.remote.eminus

import android.util.Log
import com.pako.eminus4.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Peticiones a la API de Eminus con corrutinas. Traduce la respuesta HTTP a texto o a [AppError]:
 * 401 → [AppError.SessionExpired], 413 → [AppError.FileTooLarge], otro error → [AppError.Server],
 * sin respuesta → [AppError.Network].
 *
 * Nunca registra el cuerpo de las respuestas (puede traer datos personales): solo etiqueta, código y tamaño.
 */
class EminusHttp(
    private val client: OkHttpClient,
    private val globalBaseUrl: String,
    private val uploadClient: OkHttpClient = client,
    private val logger: (String) -> Unit = { Log.d(TAG, it) }
) {

    /** Archivo para subir en un multipart (campo `file`, como Eminus web). */
    data class UploadFile(val file: File, val name: String, val mimeType: String)

    suspend fun get(path: String, tag: String): String = getUrl(globalBaseUrl + path, tag)

    /** GET a una URL completa (p. ej. la API v8 `eminusapi8`, que tiene otra base). */
    suspend fun getUrl(url: String, tag: String): String =
        execute(Request.Builder().url(url).get().build(), tag)

    suspend fun postJson(path: String, body: JSONObject, tag: String): String =
        execute(Request.Builder().url(globalBaseUrl + path).post(body.toJsonBody()).build(), tag)

    suspend fun putJson(path: String, body: JSONObject, tag: String): String =
        execute(Request.Builder().url(globalBaseUrl + path).put(body.toJsonBody()).build(), tag)

    /** POST con un JSON ya armado como texto (cuerpos que Eminus espera con nulls explícitos). */
    suspend fun postRawJson(path: String, json: String, tag: String): String =
        execute(Request.Builder().url(globalBaseUrl + path).post(json.toRequestBody(JSON)).build(), tag)

    /** Sube archivos en una sola petición multipart, con el cliente de tiempos largos. */
    suspend fun upload(path: String, files: List<UploadFile>, tag: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            files.forEach { addFormDataPart("file", it.name, it.file.asRequestBody(it.mimeType.toMediaTypeOrNull())) }
        }.build()
        return execute(Request.Builder().url(globalBaseUrl + path).post(body).build(), "$tag (${files.size} archivo/s)", uploadClient)
    }

    /** Descarga a disco una URL autenticada de Eminus. */
    suspend fun download(url: String, destination: File, tag: String): File = withContext(Dispatchers.IO) {
        val response = call(Request.Builder().url(url).get().build(), tag, client)
        response.use { r ->
            logger("$tag HTTP ${r.code}")
            failIfError(r.code, r.isSuccessful)
            val body = r.body ?: throw AppError.MissingData("Respuesta vacía del servidor")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
        destination
    }

    /**
     * Para peticiones con URL o cuerpo especiales. Segura de llamar desde el hilo principal:
     * leer el cuerpo de la respuesta también es red, y Android la prohíbe en el hilo principal
     * (NetworkOnMainThreadException), así que todo ocurre en [Dispatchers.IO].
     */
    suspend fun execute(request: Request, tag: String, httpClient: OkHttpClient = client): String =
        withContext(Dispatchers.IO) {
            call(request, tag, httpClient).use { r ->
                val body = try { r.body?.string().orEmpty() } catch (_: IOException) { "" }
                logger("$tag HTTP ${r.code} (${body.length} bytes)")
                if (r.code == HTTP_NO_CONTENT) "" else body.also { failIfError(r.code, r.isSuccessful) }
            }
        }

    private suspend fun call(request: Request, tag: String, httpClient: OkHttpClient): Response = try {
        httpClient.newCall(request).await()
    } catch (e: IOException) {
        logger("$tag: sin respuesta (${e.javaClass.simpleName})")
        throw AppError.Network(e)
    }

    private fun failIfError(code: Int, successful: Boolean) {
        when {
            code == HTTP_UNAUTHORIZED -> throw AppError.SessionExpired()
            code == HTTP_TOO_LARGE -> throw AppError.FileTooLarge()
            !successful -> throw AppError.Server(code)
        }
    }

    private fun JSONObject.toJsonBody(): RequestBody = toString().toRequestBody(JSON)

    companion object {
        private const val TAG = "EMINUS_API"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_TOO_LARGE = 413
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Ejecuta la llamada de OkHttp sin bloquear el hilo; se cancela si se cancela la corrutina. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
