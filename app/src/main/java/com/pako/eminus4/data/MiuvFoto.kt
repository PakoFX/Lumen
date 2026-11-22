package com.pako.eminus4.data

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.pako.eminus4.core.SessionManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Volver a cargar mi foto" (Perfil): vuelve a traer la foto oficial de la credencial digital de MiUV
 * (`szicred.aspx`, imagen en base64) usando la sesión de MiUV que dejó el último inicio de sesión.
 * Si esa sesión ya caducó, devuelve [Resultado.SIN_SESION] y la app pide entrar otra vez a MiUV.
 */
object MiuvFoto {

    enum class Resultado { OK, SIN_SESION, SIN_FOTO, ERROR }

    private const val TAG = "MIUV_SYNC"
    private const val URL_CREDENCIAL = "https://dsiapes.uv.mx/MIUV/portales/estudiantes/szicred.aspx?params=E"

    private object CookiesDelWebView : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            CookieManager.getInstance().getCookie(url.toString())?.split(";")
                ?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
        }
    }

    fun recargar(ctx: Context, listo: (Resultado) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val r = try { traer(app) } catch (e: Exception) { Log.w(TAG, "Recargar foto: ${e.message}"); Resultado.ERROR }
            Handler(Looper.getMainLooper()).post { listo(r) }
        }.start()
    }

    private fun traer(ctx: Context): Resultado {
        val sm = SessionManager(ctx)
        val cred = sm.getCredencialDelUsuarioActual() ?: return Resultado.ERROR
        val cliente = OkHttpClient.Builder().cookieJar(CookiesDelWebView)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        // Sin seguir redirecciones: sin sesión, MiUV manda al login o a la portada uv.mx (que desde el
        // celular puede no contestar y hacía esperar 15 s). Una redirección = sesión de MiUV caducada.
        val html = MiuvHttp.traerPagina(cliente, URL_CREDENCIAL) ?: return Resultado.SIN_SESION
        if (!html.contains("szicred", true) && !html.contains("imgFoto", true)) return Resultado.SIN_SESION

        // La foto viene incrustada: <img id="content_imgFoto2" src="data:image/jpeg;charset=utf-8;base64,…">
        val img = Regex("""<img[^>]*id="content_imgFoto2"[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
            ?: Regex("""<img[^>]*id="[^"]*imgFoto[^"]*"[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
            ?: return Resultado.SIN_FOTO
        // MiUV la manda como "data:image/jpeg;charset=utf-8;base64,…" (con charset en medio)
        val src = Regex("""src="data:image/[^";,]+(?:;[^";,]+)*;base64,([^"]+)"""", RegexOption.IGNORE_CASE).find(img)?.groupValues?.get(1)
            ?: return Resultado.SIN_FOTO
        val bytes = Base64.decode(src, Base64.DEFAULT)
        val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opciones)
        if (opciones.outWidth < 40 || opciones.outHeight < 40) return Resultado.SIN_FOTO

        // Mismo esquema que MiuvSync: un archivo nuevo por persona (la caché de imágenes nunca se cruza)
        val dir = File(ctx.filesDir, "credencial").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val id = cred.getMatriculaOficial().uppercase().replace(Regex("[^A-Z0-9]"), "_")
        val destino = File(dir, "foto_${id}_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
        Handler(Looper.getMainLooper()).post {
            sm.saveStudentCredential(cred.copy(photo = "file://" + destino.absolutePath))
        }
        Log.i(TAG, "Foto recargada: ${opciones.outWidth}×${opciones.outHeight}")
        return Resultado.OK
    }
}
