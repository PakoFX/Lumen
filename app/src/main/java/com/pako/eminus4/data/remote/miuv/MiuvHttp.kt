package com.pako.eminus4.data.remote.miuv

import android.webkit.CookieManager
import com.pako.eminus4.data.remote.eminus.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Peticiones a MiUV fuera del WebView, con la sesión (cookies) que dejó el último inicio de sesión.
 * No sigue redirecciones fuera de MiUV: sin sesión, MiUV manda al login o a la portada uv.mx, que desde
 * el celular puede ni contestar. Una redirección así basta para saber que la sesión de MiUV caducó.
 */
object MiuvHttp {

    const val SCHEDULE_URL = "https://dsiapes.uv.mx/MIUV/portales/estudiantes/szihorc.aspx"
    const val CREDENTIAL_URL = "https://dsiapes.uv.mx/MIUV/portales/estudiantes/szicred.aspx?params=E"
    private const val MIUV_HOST = "dsiapes.uv.mx"
    private const val MAX_REDIRECTS = 5

    /** Las cookies de MiUV viven en el WebView: se comparten con OkHttp (único puente en toda la app). */
    object WebViewCookieJar : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            CookieManager.getInstance().getCookie(url.toString())?.split(";")
                ?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val manager = CookieManager.getInstance()
            cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        }
    }

    fun client(readTimeoutSeconds: Long = 30): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** HTML de la página, o null si MiUV mandó al login / fuera de MiUV (sesión caducada). */
    suspend fun fetchPage(client: OkHttpClient, url: String): String? = withContext(Dispatchers.IO) { fetchPageBlocking(client, url) }

    private suspend fun fetchPageBlocking(client: OkHttpClient, url: String): String? {
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = url
        repeat(MAX_REDIRECTS) {
            noRedirects.newCall(Request.Builder().url(current).build()).await().use { response ->
                if (!response.isRedirect) {
                    return if (response.isSuccessful) response.body?.string().orEmpty() else null
                }
                val next = response.header("Location")?.let { response.request.url.resolve(it) } ?: return null
                val insideMiuv = next.host.equals(MIUV_HOST, true) && next.encodedPath.contains("/MIUV/", true)
                if (!insideMiuv || next.encodedPath.contains("login", true)) return null
                current = next.toString()
            }
        }
        return null
    }
}
