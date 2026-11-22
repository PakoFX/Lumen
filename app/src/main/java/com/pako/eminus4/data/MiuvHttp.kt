package com.pako.eminus4.data

import okhttp3.OkHttpClient
import okhttp3.Request

/** Utilidades para pedir páginas de MiUV fuera del WebView (con sus cookies). */
object MiuvHttp {

    /**
     * Trae una página de MiUV siguiendo solo redirecciones que se quedan dentro de MiUV.
     * Si MiUV manda al login o fuera (p. ej. la portada uv.mx, que desde el celular puede ni contestar)
     * devuelve null de inmediato: la sesión de MiUV caducó. Así no se espera a un sitio que no responde.
     */
    fun traerPagina(cliente: OkHttpClient, url: String): String? {
        val sinSeguir = cliente.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var actual = url
        repeat(5) {
            sinSeguir.newCall(Request.Builder().url(actual).build()).execute().use { r ->
                if (r.isRedirect) {
                    val sig = r.header("Location")?.let { r.request.url.resolve(it) } ?: return null
                    val dentro = sig.host.equals("dsiapes.uv.mx", true) && sig.encodedPath.contains("/MIUV/", true)
                    if (!dentro || sig.encodedPath.contains("login", true)) return null
                    actual = sig.toString()
                } else {
                    return if (r.isSuccessful) r.body?.string().orEmpty() else null
                }
            }
        }
        return null
    }
}
