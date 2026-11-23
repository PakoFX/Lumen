package com.pako.eminus4.data.remote.eminus

import android.util.Log
import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Crea los clientes HTTP para Eminus. Todos comparten [authInterceptor]: token Bearer de la sesión,
 * cookies del WebView y los encabezados que espera Eminus (como si fuera el navegador).
 */
object EminusClientFactory {

    private const val EMINUS_ORIGIN = "https://eminus.uv.mx"
    private const val REFERER = "https://eminus.uv.mx/eminus4/page/course/list"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** @param tokenProvider token actual de la sesión (se lee en cada petición: puede renovarse). */
    fun authInterceptor(tokenProvider: () -> String?, accept: String = "application/json, text/plain, */*") =
        Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .apply {
                    tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                    webViewCookies()?.let { header("Cookie", it) }
                }
                .build()
            chain.proceed(request)
        }

    fun apiClient(tokenProvider: () -> String?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor(tokenProvider))
        .build()

    /** Mismo cliente con tiempos amplios para subir archivos pesados. */
    fun uploadClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Cliente de imágenes (Coil): las miniaturas y fotos de Eminus también piden token y cookies. */
    fun imageClient(tokenProvider: () -> String?): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor(tokenProvider, accept = "image/png,image/jpeg,image/webp,image/*,*/*"))
        .build()

    private fun webViewCookies(): String? = try {
        CookieManager.getInstance().getCookie(EMINUS_ORIGIN)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w("EMINUS_API", "Sin cookies del WebView: ${e.javaClass.simpleName}")
        null
    }
}
