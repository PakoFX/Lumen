package com.pako.eminus4.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Reproduce un video o audio que el profe subió a Eminus o incrustó en un texto
 * (`<video>`/`<audio>` con archivo en eminus.uv.mx). Pantalla negra, gira con el celular.
 * Los de YouTube no pasan por aquí: se abren en la app de YouTube.
 */
class ReproductorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private val EXT_AUDIO = Regex("""\.(mp3|wav|ogg|oga|m4a|aac|opus)(\?.*)?$""", RegexOption.IGNORE_CASE)
        private val EXT_MEDIA = Regex("""\.(mp4|webm|mov|m4v|3gp|mkv|mp3|wav|ogg|oga|m4a|aac|opus)(\?.*)?$""", RegexOption.IGNORE_CASE)

        fun esMedia(url: String) = EXT_MEDIA.containsMatchIn(url)

        fun start(ctx: Context, url: String) =
            ctx.startActivity(Intent(ctx, ReproductorActivity::class.java).putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private lateinit var web: WebView
    private var pantallaCompleta: View? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                // Botón de pantalla completa del reproductor
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    pantallaCompleta = view
                    raiz.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                override fun onHideCustomView() {
                    pantallaCompleta?.let { raiz.removeView(it) }; pantallaCompleta = null
                }
            }
        }
        raiz.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(raiz)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, raiz).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val limpio = url.replace("\"", "%22")
        val etiqueta = if (EXT_AUDIO.containsMatchIn(url))
            """<audio controls autoplay src="$limpio" style="width:92%"></audio>"""
        else """<video controls autoplay playsinline src="$limpio" style="width:100%;max-height:100%"></video>"""
        val html = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>html,body{margin:0;height:100%;background:#000;display:flex;align-items:center;justify-content:center}</style>
</head><body>$etiqueta</body></html>"""
        web.loadDataWithBaseURL("https://eminus.uv.mx", html, "text/html", "UTF-8", null)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pantallaCompleta != null) web.webChromeClient?.onHideCustomView() else finish()
            }
        })
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
