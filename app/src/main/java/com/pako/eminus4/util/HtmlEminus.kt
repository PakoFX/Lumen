package com.pako.eminus4.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.URLSpan
import com.pako.eminus4.ui.viewer.ReproductorActivity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import coil.imageLoader
import coil.request.ImageRequest
import com.pako.eminus4.R
import com.pako.eminus4.ui.viewer.FileViewerActivity

/**
 * Texto con formato que escriben los profes en Eminus (editor Froala): enlaces, imágenes
 * (`eminusapi/froala/...`, públicas), videos de YouTube en `<iframe>`, `<audio>`, tablas.
 * Se pinta en un WebView con los colores del tema (modo oscuro incluido) y los enlaces se abren fuera.
 */
object HtmlEminus {

    private const val BASE = "https://eminus.uv.mx"
    private val RE_IFRAME = Regex("""<iframe[^>]*>.*?</iframe>|<iframe[^>]*/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    @SuppressLint("SetJavaScriptEnabled")
    fun preparar(web: WebView) {
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true          // reproductores de YouTube
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.isVerticalScrollBarEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                abrirEnlace(view.context, request.url.toString())
                return true
            }
        }
    }

    fun cargar(web: WebView, html: String, tamanoLetra: Int = 15) {
        web.loadDataWithBaseURL(BASE, documento(web.context, html, tamanoLetra), "text/html", "UTF-8", null)
    }

    private val RE_YOUTUBE = Regex("""(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=|shorts/|live/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""", RegexOption.IGNORE_CASE)

    fun youtubeId(url: String) = RE_YOUTUBE.find(url)?.groupValues?.get(1)?.takeIf { it != "live_stream" }

    /** Enlace "normal" de YouTube para un `<iframe src=".../embed/ID">` (abre la app de YouTube). */
    private fun youtubeVer(src: String): String = youtubeId(src)?.let { "https://www.youtube.com/watch?v=$it" }
        ?: src.replace("/embed/", "/")

    /**
     * Abre un enlace donde corresponde: videos/audios subidos a Eminus → reproductor de la app;
     * YouTube → app de YouTube; lo demás → su app o el navegador.
     */
    fun abrirEnlace(ctx: Context, url: String) {
        val limpio = url.trim().let { if (it.startsWith("//")) "https:$it" else it }
        if (ReproductorActivity.esMedia(limpio) && youtubeId(limpio) == null) { ReproductorActivity.start(ctx, limpio); return }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(limpio)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "No hay una app para abrir este enlace", Toast.LENGTH_SHORT).show()
        }
    }

    /** ¿El HTML trae algo que un TextView no puede mostrar (imágenes, videos, audio, tablas)? */
    fun necesitaWeb(html: String?) =
        html != null && Regex("<(img|iframe|audio|video|table)", RegexOption.IGNORE_CASE).containsMatchIn(html)

    /**
     * Para pintar en un TextView sin perder nada: los videos, audios e imágenes incrustados
     * se vuelven enlaces ("▶ Ver video", "🖼 Ver imagen") que se abren fuera.
     */
    fun paraTexto(html: String, conImagenes: Boolean = false): String {
        val base = sinMultimedia(html)
        return if (conImagenes) base else base
            .replace(Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE)) { m ->
                srcDe(m.value)?.takeIf { it.startsWith("http") }?.let { "<a href=\"$it\">🖼 Ver imagen</a> " } ?: "" }
    }

    private fun srcDe(tag: String) = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        ?.let { if (it.startsWith("//")) "https:$it" else it }

    /**
     * Pinta HTML en un TextView con las imágenes **dentro del texto** (se cargan con Coil y se ajustan
     * al ancho). Tocar una imagen la abre en grande en el visor. Videos/audios quedan como enlace.
     */
    fun enTextView(tv: TextView, html: String, tituloVisor: String? = null) {
        val ctx = tv.context
        val dens = ctx.resources.displayMetrics.density
        val anchoMax = { (tv.width - tv.totalPaddingLeft - tv.totalPaddingRight).takeIf { it > 0 }
            ?: (ctx.resources.displayMetrics.widthPixels - (72 * dens).toInt()) }

        val getter = Html.ImageGetter { src ->
            val url = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "$BASE$src"
                else -> src
            }
            val hueco = Hueco().apply { setBounds(0, 0, 1, 1) }
            if (url.startsWith("http")) {
                val req = ImageRequest.Builder(ctx).data(url).allowHardware(false)
                    .target { d: Drawable ->
                        val iw = d.intrinsicWidth.coerceAtLeast(1); val ih = d.intrinsicHeight.coerceAtLeast(1)
                        // Imágenes pequeñas a su tamaño (en dp); grandes, al ancho del texto
                        val w = minOf(anchoMax(), (iw * dens).toInt()); val h = w * ih / iw
                        d.setBounds(0, 0, w, h)
                        hueco.d = d; hueco.setBounds(0, 0, w, h)
                        tv.text = tv.text   // vuelve a medir el texto con la imagen ya cargada
                    }.build()
                ctx.imageLoader.enqueue(req)
            }
            hueco
        }
        val sp = SpannableStringBuilder(
            HtmlCompat.fromHtml(paraTexto(html, conImagenes = true), HtmlCompat.FROM_HTML_MODE_COMPACT, getter, null))
        // Tocar la imagen → visor
        sp.getSpans(0, sp.length, ImageSpan::class.java).forEach { img ->
            val src = img.source ?: return@forEach
            val url = if (src.startsWith("//")) "https:$src" else src
            sp.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    FileViewerActivity.start(widget.context, url, url.substringAfterLast('/').substringBefore('?').ifBlank { "imagen.jpg" }, null, tituloVisor)
                }
                override fun updateDrawState(ds: TextPaint) {}
            }, sp.getSpanStart(img), sp.getSpanEnd(img), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Los enlaces pasan por abrirEnlace (videos de Eminus → reproductor; YouTube → su app)
        sp.getSpans(0, sp.length, URLSpan::class.java).forEach { u ->
            val ini = sp.getSpanStart(u); val fin = sp.getSpanEnd(u); val destino = u.url
            sp.removeSpan(u)
            sp.setSpan(object : URLSpan(destino) {
                override fun onClick(widget: View) = abrirEnlace(widget.context, destino)
            }, ini, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp.trim()
        tv.movementMethod = LinkMovementMethod.getInstance()
    }

    /** Contenedor que se pinta vacío hasta que Coil trae la imagen. */
    private class Hueco : Drawable() {
        var d: Drawable? = null
        override fun draw(canvas: Canvas) { d?.draw(canvas) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Videos y audios incrustados → enlaces (un TextView no puede reproducirlos). */
    private fun sinMultimedia(html: String): String = html
        .replace(RE_IFRAME) { m ->
            srcDe(m.value)?.let { s ->
                if (youtubeId(s) != null || s.contains("youtube", true)) "<p><a href=\"${youtubeVer(s)}\">▶ Ver en YouTube</a></p>"
                else "<p><a href=\"$s\">▶ Abrir contenido</a></p>"
            } ?: "" }
        .replace(Regex("""<(audio|video)[^>]*>.*?</\1>|<(audio|video)[^>]*/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
            val s = srcDe(m.value) ?: Regex("""<source[^>]*>""", RegexOption.IGNORE_CASE).find(m.value)?.value?.let { srcDe(it) }
            s?.let { "<p><a href=\"$it\">▶ Reproducir</a></p>" } ?: "" }

    private fun hex(ctx: Context, id: Int) = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(ctx, id))

    fun documento(ctx: Context, html: String, tamanoLetra: Int = 15): String {
        val texto = hex(ctx, R.color.text_body)
        val enlace = hex(ctx, R.color.badge_info_text)
        val borde = hex(ctx, R.color.border_card)
        // Froala a veces deja src="//www.youtube.com/..." (sin protocolo)
        val cuerpo = html.replace("src=\"//", "src=\"https://")
            // YouTube → miniatura con ▶ que abre la app de YouTube (no el reproductor incrustado)
            .replace(RE_IFRAME) { m ->
                val s = srcDe(m.value) ?: return@replace ""
                val id = youtubeId(s)
                when {
                    id != null -> """<a class="yt" href="https://www.youtube.com/watch?v=$id"><img src="https://img.youtube.com/vi/$id/hqdefault.jpg"><span class="play">▶</span><span class="et">Ver en YouTube</span></a>"""
                    s.contains("youtube", true) -> """<a class="yt sin" href="${youtubeVer(s)}"><span class="play">▶</span><span class="et">Ver en YouTube</span></a>"""
                    else -> m.value
                }
            }
            // Video subido a Eminus → tarjeta que abre el reproductor de la app (pantalla completa, gira)
            .replace(Regex("""<video[^>]*>.*?</video>|<video[^>]*/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { m ->
                val s = srcDe(m.value) ?: Regex("""<source[^>]*>""", RegexOption.IGNORE_CASE).find(m.value)?.value?.let { srcDe(it) }
                s?.let { """<a class="yt sin" href="$it"><span class="play">▶</span><span class="et">Reproducir video</span></a>""" } ?: ""
            }
        return """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  html, body { margin:0; padding:0; background:transparent; }
  body { font-family: Roboto, -apple-system, Helvetica, Arial, sans-serif; font-size:${tamanoLetra}px;
         line-height:1.55; color:$texto !important; overflow-wrap:anywhere; word-break:break-word; }
  body * { color:inherit !important; background-color:transparent !important; max-width:100% !important; }
  a, a * { color:$enlace !important; }
  p { margin:0 0 8px 0; }
  img { height:auto !important; border-radius:8px; margin:6px 0; }
  iframe, video { width:100% !important; aspect-ratio:16/9; height:auto !important; border:0; border-radius:10px; margin:6px 0; }
  audio { width:100%; margin:6px 0; }
  table { border-collapse:collapse; display:block; overflow-x:auto; }
  td, th { border:1px solid $borde; padding:4px 6px; }
  a.yt { position:relative; display:block; margin:8px 0; border-radius:12px; overflow:hidden; text-decoration:none;
         background-color:#000 !important; }
  a.yt img { display:block; width:100%; margin:0; border-radius:0; opacity:.85; }
  a.yt.sin { height:120px; }
  a.yt .play { position:absolute; left:50%; top:50%; transform:translate(-50%,-50%); width:56px; height:56px;
         border-radius:50%; background-color:rgba(0,0,0,.65) !important; color:#fff !important; font-size:26px;
         line-height:56px; text-align:center; }
  a.yt .et { position:absolute; left:10px; bottom:8px; color:#fff !important; font-size:13px; font-weight:500;
         text-shadow:0 1px 3px #000; }
</style></head><body>$cuerpo</body></html>"""
    }
}
