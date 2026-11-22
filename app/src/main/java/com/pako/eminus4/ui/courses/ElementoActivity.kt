package com.pako.eminus4.ui.courses

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.ContentElement
import com.pako.eminus4.ui.viewer.FileViewerActivity
import coil.load
import coil.transform.CircleCropTransformation
import com.pako.eminus4.util.HtmlEminus
import com.pako.eminus4.util.Plazos
import com.pako.eminus4.util.iconoInicio

/**
 * Un elemento del Contenido de la materia: texto del profe, sus archivos (se abren en el visor),
 * "Marcar como leído" (Contenido/insertaLeido) y me gusta (Contenido/insertaLike), como en Eminus web.
 */
class ElementoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CURSO = "idCurso"
        private const val EXTRA_UNIDAD = "unidad"
        private const val EXTRA_ELEMENTO = "elemento"
        const val RESULT_CAMBIO = 41

        fun intent(ctx: Context, idCurso: Long, unidad: String?, e: ContentElement) =
            Intent(ctx, ElementoActivity::class.java).apply {
                putExtra(EXTRA_CURSO, idCurso)
                putExtra(EXTRA_UNIDAD, unidad)
                putExtra(EXTRA_ELEMENTO, Gson().toJson(e))
            }
    }

    private lateinit var api: EminusApiClient
    private lateinit var e: ContentElement
    private var idCurso = 0L
    private var leido = false
    private var meGusta = false
    private var likes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_elemento)
        idCurso = intent.getLongExtra(EXTRA_CURSO, 0L)
        e = try { Gson().fromJson(intent.getStringExtra(EXTRA_ELEMENTO), ContentElement::class.java) } catch (_: Exception) { null }
            ?: run { finish(); return }
        api = EminusApiClient(SessionManager(this))
        leido = e.leido; meGusta = e.usuarioLike == 1; likes = e.likes

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarElemento)
        toolbar.title = intent.getStringExtra(EXTRA_UNIDAD) ?: "Contenido"
        toolbar.setNavigationOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootElemento)) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom); ins
        }

        findViewById<TextView>(R.id.txtElemTitulo).text = e.nombre ?: "Elemento"
        findViewById<TextView>(R.id.txtElemFecha).text = Plazos.parseIso(e.fechaPublicacion)
            ?.let { "Publicado el ${Plazos.fechaCorta(it)}" }.orEmpty() +
            if (e.lecturaObligatoria == 1) "  ·  Lectura obligatoria" else ""

        // Texto del profe como HTML real (enlaces, imágenes, YouTube, audio), no como texto plano
        val html = e.texto.orEmpty()
        val tieneAlgo = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).isNotBlank() ||
            Regex("<(img|iframe|audio|video)", RegexOption.IGNORE_CASE).containsMatchIn(html)
        findViewById<View>(R.id.cardElemTexto).visibility = if (tieneAlgo) View.VISIBLE else View.GONE
        if (tieneAlgo) findViewById<WebView>(R.id.webElemTexto).also { HtmlEminus.preparar(it); HtmlEminus.cargar(it, html) }

        // Tipo 5: página web
        e.urlPaginaWeb()?.let { url ->
            findViewById<MaterialButton>(R.id.btnElemPagina).apply {
                visibility = View.VISIBLE
                setOnClickListener { HtmlEminus.abrirEnlace(this@ElementoActivity, url) }
            }
        }

        pintarEnlaces()

        // En una página web el "archivo" es la carpeta del ZIP: se abre con el botón, no como descarga
        val archivos = if (e.esPaginaWeb) emptyList() else e.archivos.orEmpty().filter { it.visible == 1 }
        findViewById<TextView>(R.id.txtElemArchivosTitulo).apply {
            visibility = if (archivos.isEmpty()) View.GONE else View.VISIBLE
            text = if (e.idTipoElemento == 2) "IMÁGENES" else "ARCHIVOS"
        }
        val cont = findViewById<LinearLayout>(R.id.layoutElemArchivos)
        archivos.forEach { a ->
            val nombre = a.nombreOriginal ?: "Archivo"
            cont.addView(TextView(this).apply {
                text = if (a.peso.isNullOrBlank()) nombre else "$nombre · ${a.peso}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ElementoActivity, R.color.badge_info_text))
                iconoInicio(R.drawable.ic_attach_file, tamanoDp = 16, separacionDp = 8)
                val d = resources.displayMetrics.density
                setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                setOnClickListener {
                    FileViewerActivity.start(this@ElementoActivity, a.urlDescarga(idCurso, e.idElemento), nombre, a.peso, e.nombre)
                }
            })
        }

        pintarComentarios()
        findViewById<View>(R.id.btnElemEnviar).setOnClickListener { enviarComentario() }
        pintarAcciones()
        findViewById<MaterialButton>(R.id.btnElemLeido).setOnClickListener { marcarLeido() }
        findViewById<MaterialButton>(R.id.btnElemLike).setOnClickListener { alternarLike() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Lista aparte de los enlaces del texto (Drive, Docs, Excalidraw…), para tenerlos a la mano. */
    private fun pintarEnlaces() {
        val enlaces = e.enlacesExternos()
        findViewById<View>(R.id.txtElemEnlacesTitulo).visibility = if (enlaces.isEmpty()) View.GONE else View.VISIBLE
        val cont = findViewById<LinearLayout>(R.id.layoutElemEnlaces)
        enlaces.forEach { url ->
            val uri = Uri.parse(url)
            val sitio = (uri.host ?: url).removePrefix("www.")
            val tipo = when {
                sitio.startsWith("docs.google.com") && url.contains("/spreadsheets/") -> "Hoja de cálculo de Google"
                sitio.startsWith("docs.google.com") && url.contains("/presentation/") -> "Presentación de Google"
                sitio.startsWith("docs.google.com") && url.contains("/forms/") -> "Formulario de Google"
                sitio.startsWith("docs.google.com") -> "Documento de Google"
                sitio.startsWith("drive.google.com") -> "Google Drive"
                sitio.contains("youtube.com") || sitio == "youtu.be" -> "Video de YouTube"
                else -> sitio
            }
            cont.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                background = ContextCompat.getDrawable(this@ElementoActivity, android.R.drawable.list_selector_background)
                addView(TextView(this@ElementoActivity).apply {
                    text = tipo
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@ElementoActivity, R.color.badge_info_text))
                    iconoInicio(R.drawable.ic_link, tamanoDp = 16, separacionDp = 8)
                })
                addView(TextView(this@ElementoActivity).apply {
                    text = url
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(this@ElementoActivity, R.color.text_tertiary))
                    setPadding(dp(24), 0, 0, 0)
                })
                setOnClickListener { HtmlEminus.abrirEnlace(this@ElementoActivity, url) }
                setOnLongClickListener {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Enlace", url))
                    Toast.makeText(this@ElementoActivity, "Enlace copiado", Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }
    }

    /** Publica el comentario escrito y vuelve a pedir el elemento para mostrarlo. */
    private fun enviarComentario() {
        val edit = findViewById<android.widget.EditText>(R.id.editElemComentario)
        val btn = findViewById<View>(R.id.btnElemEnviar)
        val texto = edit.text?.toString()?.trim().orEmpty()
        if (texto.isEmpty()) { edit.error = "Escribe algo primero"; return }
        btn.isEnabled = false; btn.alpha = 0.4f
        api.commentElement(idCurso, e.idElemento, texto) { r ->
            r.onSuccess {
                edit.setText("")
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(edit.windowToken, 0)
                setResult(RESULT_CAMBIO)
                Toast.makeText(this, "Comentario publicado", Toast.LENGTH_SHORT).show()
                api.getContentElement(idCurso, e.idUnidad, e.idElemento) { re ->
                    re.getOrNull()?.let { nuevo -> e = nuevo; pintarComentarios() }
                }
            }.onFailure {
                Toast.makeText(this, "No se pudo publicar: ${it.message}", Toast.LENGTH_LONG).show()
            }
            btn.isEnabled = true; btn.alpha = 1f
        }
    }

    /** Comentarios del elemento + caja para escribir uno. */
    private fun pintarComentarios() {
        val lista = e.comentariosVisibles().sortedBy { it.fechaPublicacion }
        findViewById<TextView>(R.id.txtElemComentariosTitulo).apply {
            visibility = View.VISIBLE
            text = if (lista.isEmpty()) "COMENTARIOS" else "COMENTARIOS · ${lista.size}"
        }
        findViewById<android.widget.EditText>(R.id.editElemComentario).hint =
            if (lista.isEmpty()) "Sé el primero en comentar…" else "Escribe un comentario…"
        val cont = findViewById<LinearLayout>(R.id.layoutElemComentarios)
        cont.removeAllViews()
        lista.forEach { c ->
            cont.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
                addView(ImageView(this@ElementoActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                    load(c.fotoUrl()) {
                        transformations(CircleCropTransformation())
                        placeholder(R.drawable.avatar_vacio); error(R.drawable.avatar_vacio)
                    }
                })
                addView(LinearLayout(this@ElementoActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) }
                    addView(TextView(this@ElementoActivity).apply {
                        text = listOfNotNull(c.nombreBonito(), Plazos.parseIso(c.fechaPublicacion)?.let { Plazos.fechaCorta(it) })
                            .joinToString(" · ")
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@ElementoActivity, R.color.text_secondary))
                    })
                    addView(TextView(this@ElementoActivity).apply {
                        text = HtmlCompat.fromHtml(c.texto.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT).trim()
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(this@ElementoActivity, R.color.text_body))
                        setTextIsSelectable(true)
                    })
                })
            })
        }
    }

    private fun pintarAcciones() {
        findViewById<MaterialButton>(R.id.btnElemLeido).apply {
            isEnabled = !leido
            text = if (leido) "Leído" else "Marcar como leído"
        }
        findViewById<MaterialButton>(R.id.btnElemLike).apply {
            text = if (likes > 0) "$likes" else "Me gusta"
            setIconResource(if (meGusta) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up)
        }
    }

    private fun marcarLeido() {
        findViewById<MaterialButton>(R.id.btnElemLeido).isEnabled = false
        api.markElementRead(idCurso, e.idElemento) { r ->
            r.onSuccess {
                leido = true
                setResult(RESULT_CAMBIO)
                Toast.makeText(this, "Marcado como leído", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(this, "No se pudo marcar: ${it.message}", Toast.LENGTH_LONG).show() }
            pintarAcciones()
        }
    }

    private fun alternarLike() {
        meGusta = !meGusta
        likes = (likes + if (meGusta) 1 else -1).coerceAtLeast(0)
        pintarAcciones()
        api.likeElement(idCurso, e.idElemento) { r ->
            if (r.isFailure) {
                meGusta = !meGusta
                likes = (likes + if (meGusta) 1 else -1).coerceAtLeast(0)
                pintarAcciones()
                Toast.makeText(this, "No se pudo registrar el me gusta", Toast.LENGTH_SHORT).show()
            } else setResult(RESULT_CAMBIO)
        }
    }
}
