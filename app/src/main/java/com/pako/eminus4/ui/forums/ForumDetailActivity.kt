package com.pako.eminus4.ui.forums

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.model.ForumDetail
import com.pako.eminus4.util.Plazos
import com.pako.eminus4.util.iconoInicio

/**
 * Foro dentro de la app: leer instrucciones y comentarios, dar "me gusta",
 * comentar y responder. Mismos endpoints que Eminus web (ver EminusApiClient, sección FOROS).
 */
class ForumDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CURSO = "idCurso"
        private const val EXTRA_FORO = "idForo"
        private const val EXTRA_TITULO = "titulo"
        private const val EXTRA_CURSO_NOMBRE = "nombreCurso"

        fun start(ctx: Context, idCurso: Long, idForo: Long, titulo: String, nombreCurso: String) {
            ctx.startActivity(Intent(ctx, ForumDetailActivity::class.java).apply {
                putExtra(EXTRA_CURSO, idCurso)
                putExtra(EXTRA_FORO, idForo)
                putExtra(EXTRA_TITULO, titulo)
                putExtra(EXTRA_CURSO_NOMBRE, nombreCurso)
            })
        }
    }

    private lateinit var apiClient: EminusApiClient
    private var idCurso = 0L
    private var idForo = 0L

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var txtEstado: TextView
    private lateinit var txtCierre: TextView
    private lateinit var txtDescripcion: TextView
    private lateinit var txtTituloComentarios: TextView
    private lateinit var layoutComentarios: LinearLayout
    private lateinit var txtSinComentarios: TextView
    private lateinit var btnMasComentarios: MaterialButton
    private lateinit var layoutRespondiendo: View
    private lateinit var txtRespondiendo: TextView
    private lateinit var layoutEscribir: View
    private lateinit var txtForoCerrado: TextView
    private lateinit var editComentario: EditText
    private lateinit var btnEnviar: ImageButton

    private var detalle: ForumDetail? = null
    private var comentarios: List<ForumComment> = emptyList()
    /** Comentario al que se está respondiendo (null = comentario nuevo). */
    private var respondiendoA: ForumComment? = null
    private var enviando = false
    private lateinit var adjuntos: com.pako.eminus4.ui.common.ListaAdjuntos

    private val elegirArchivos = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) adjuntos.agregar(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forum_detail)

        apiClient = EminusApiClient(SessionManager(this))
        idCurso = intent.getLongExtra(EXTRA_CURSO, 0L)
        idForo = intent.getLongExtra(EXTRA_FORO, 0L)

        inicializarVistas()
        cargar()
    }

    private fun inicializarVistas() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra(EXTRA_TITULO).orEmpty()
        toolbar.subtitle = intent.getStringExtra(EXTRA_CURSO_NOMBRE).orEmpty()
        toolbar.setNavigationOnClickListener { finish() }

        // Barra de estado arriba; barra de navegación o teclado abajo (la caja sube con el teclado)
        val appBar = findViewById<View>(R.id.appBarLayout)
        val composer = findViewById<View>(R.id.layoutComposer)
        val padComposer = composer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootForo)) { _, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.setPadding(0, barras.top, 0, 0)
            composer.setPadding(
                composer.paddingLeft, composer.paddingTop, composer.paddingRight,
                padComposer + maxOf(barras.bottom, teclado.bottom)
            )
            insets
        }

        swipeRefresh = findViewById(R.id.swipeRefresh)
        scroll = findViewById(R.id.scrollForo)
        txtEstado = findViewById(R.id.txtForoEstado)
        txtCierre = findViewById(R.id.txtForoCierre)
        txtDescripcion = findViewById(R.id.txtForoDescripcion)
        txtTituloComentarios = findViewById(R.id.txtTituloComentarios)
        layoutComentarios = findViewById(R.id.layoutComentarios)
        txtSinComentarios = findViewById(R.id.txtSinComentarios)
        btnMasComentarios = findViewById(R.id.btnMasComentarios)
        layoutRespondiendo = findViewById(R.id.layoutRespondiendo)
        txtRespondiendo = findViewById(R.id.txtRespondiendo)
        layoutEscribir = findViewById(R.id.layoutEscribir)
        txtForoCerrado = findViewById(R.id.txtForoCerrado)
        editComentario = findViewById(R.id.editComentario)
        btnEnviar = findViewById(R.id.btnEnviar)

        txtDescripcion.movementMethod = LinkMovementMethod.getInstance()
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { cargar() }
        btnMasComentarios.setOnClickListener { cargarMasComentarios() }
        findViewById<View>(R.id.btnCancelarRespuesta).setOnClickListener { responderA(null) }
        btnEnviar.setOnClickListener { publicar() }
        adjuntos = com.pako.eminus4.ui.common.ListaAdjuntos(this, findViewById(R.id.layoutAdjuntosForo))
        findViewById<View>(R.id.btnAdjuntarForo).setOnClickListener { elegirArchivos.launch(arrayOf("*/*")) }
    }

    // ===================== CARGA =====================

    private fun cargar() {
        swipeRefresh.isRefreshing = true
        apiClient.getForumDetail(idCurso, idForo) { r ->
            r.onSuccess { detalle = it; pintarDetalle() }
                .onFailure { mostrarError("No se pudo cargar el foro", it) }
        }
        apiClient.getForumComments(idCurso, idForo) { r ->
            swipeRefresh.isRefreshing = false
            r.onSuccess { comentarios = it; pintarComentarios() }
                .onFailure { mostrarError("No se pudieron cargar los comentarios", it) }
        }
    }

    private fun cargarMasComentarios() {
        btnMasComentarios.isEnabled = false
        apiClient.getMoreForumComments(idCurso, idForo) { r ->
            btnMasComentarios.isEnabled = true
            r.onSuccess { mas ->
                val ids = comentarios.map { it.idComentarioForo }.toSet()
                comentarios = comentarios + mas.filter { it.idComentarioForo !in ids }
                pintarComentarios()
            }.onFailure { mostrarError("No se pudieron cargar más comentarios", it) }
        }
    }

    private fun foroAbierto(): Boolean {
        val d = detalle ?: return true
        if (d.estado != 1) return false
        val cierre = Plazos.parseIso(d.fechaProrroga) ?: Plazos.parseIso(d.fechaTermino) ?: return true
        return System.currentTimeMillis() <= cierre
    }

    private fun pintarDetalle() {
        val d = detalle ?: return
        val abierto = foroAbierto()
        txtEstado.text = if (abierto) "Abierto" else "Cerrado"
        txtEstado.setTextColor(ContextCompat.getColor(this, if (abierto) R.color.badge_success_text else R.color.badge_neutral_text))
        val cierre = Plazos.parseIso(d.fechaProrroga) ?: Plazos.parseIso(d.fechaTermino)
        txtCierre.text = if (cierre != null) Plazos.plazo(cierre).texto.replace("Vence", "Cierra").replace("Venció", "Cerró") else ""

        // Archivos que el profe adjuntó al foro
        val contArchivos = findViewById<LinearLayout>(R.id.layoutArchivosForo)
        contArchivos.removeAllViews()
        d.archivos.orEmpty().forEach { a ->
            contArchivos.addView(filaArchivo(a.nombreOriginal ?: "Archivo", a.tamanoLegible(), a.urlDescargaForo(idCurso, idForo)))
        }
        contArchivos.visibility = if (d.archivos.isNullOrEmpty()) View.GONE else View.VISIBLE

        val descHtml = d.descripcion.orEmpty()
        val vacia = HtmlCompat.fromHtml(descHtml, HtmlCompat.FROM_HTML_MODE_COMPACT).isBlank() &&
            !com.pako.eminus4.util.HtmlEminus.necesitaWeb(descHtml)
        // Imágenes dentro del texto (tocar → visor)
        if (!vacia) com.pako.eminus4.util.HtmlEminus.enTextView(txtDescripcion, descHtml, intent.getStringExtra(EXTRA_TITULO))
        txtDescripcion.visibility = if (vacia) View.GONE else View.VISIBLE

        layoutEscribir.visibility = if (abierto) View.VISIBLE else View.GONE
        txtForoCerrado.visibility = if (abierto) View.GONE else View.VISIBLE
        if (!abierto) responderA(null)
        pintarComentarios() // el botón "Responder" depende de si está abierto
    }

    // ===================== COMENTARIOS =====================

    private fun pintarComentarios() {
        layoutComentarios.removeAllViews()
        val total = maxOf(detalle?.totalComentariosRaiz ?: 0, comentarios.size)
        txtTituloComentarios.text = if (total > 0) "COMENTARIOS · $total" else "COMENTARIOS"
        txtSinComentarios.visibility = if (comentarios.isEmpty()) View.VISIBLE else View.GONE
        comentarios.forEach { layoutComentarios.addView(vistaComentario(it, esRespuesta = false, raiz = it)) }
        btnMasComentarios.visibility = if (total > comentarios.size) View.VISIBLE else View.GONE
    }

    private fun vistaComentario(c: ForumComment, esRespuesta: Boolean, raiz: ForumComment): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_forum_comment, layoutComentarios, false)
        val d = resources.displayMetrics.density

        if (esRespuesta) {
            // Sangría y avatar más chico para las respuestas
            v.findViewById<ImageView>(R.id.imgAutor).layoutParams.apply { width = (28 * d).toInt(); height = (28 * d).toInt() }
        }

        val img = v.findViewById<ImageView>(R.id.imgAutor)
        val avatar = c.getAvatarUrl()
        if (avatar != null) {
            img.load(avatar) {
                crossfade(true)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.avatar_vacio)
                error(R.drawable.avatar_vacio)
            }
        }

        v.findViewById<TextView>(R.id.txtAutor).text = c.nombreCompleto?.trim()?.ifBlank { null } ?: "Participante"
        v.findViewById<TextView>(R.id.txtFecha).text = Plazos.haceCuanto(Plazos.parseIso(c.fechaPublicacion))

        v.findViewById<TextView>(R.id.txtCuerpo).apply {
            com.pako.eminus4.util.HtmlEminus.enTextView(this, c.descripcion.orEmpty(), intent.getStringExtra(EXTRA_TITULO))
        }

        v.findViewById<LinearLayout>(R.id.layoutAdjuntos).apply {
            c.archivos.orEmpty().forEach { a ->
                addView(filaArchivo(a.nombreOriginal ?: "Archivo", a.tamanoLegible(), a.urlDescargaComentario(c.idComentarioForo)))
            }
            visibility = if (c.archivos.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        // Me gusta
        val btnLike = v.findViewById<TextView>(R.id.btnLike)
        pintarLike(btnLike, c.meGusta, c.likes)
        btnLike.setOnClickListener { alternarLike(c, btnLike) }

        // Responder (solo a comentarios raíz, como en Eminus web; responder a una respuesta
        // la agrega al mismo hilo)
        v.findViewById<TextView>(R.id.btnResponder).apply {
            visibility = if (foroAbierto()) View.VISIBLE else View.GONE
            iconoInicio(R.drawable.ic_reply, tamanoDp = 14, separacionDp = 4)
            setOnClickListener { responderA(raiz, mencion = if (esRespuesta) c.nombreCompleto else null) }
        }

        // Respuestas
        if (!esRespuesta) {
            val cont = v.findViewById<LinearLayout>(R.id.layoutRespuestas)
            c.respuestas.orEmpty().forEach { cont.addView(vistaComentario(it, esRespuesta = true, raiz = c)) }
            val faltan = c.totalRespuestas - c.respuestas.orEmpty().size
            v.findViewById<TextView>(R.id.btnMasRespuestas).apply {
                if (faltan > 0) {
                    visibility = View.VISIBLE
                    text = "Ver $faltan respuesta${if (faltan == 1) "" else "s"} más"
                    setOnClickListener { cargarRespuestas(c) }
                }
            }
        }
        return v
    }

    /** Archivo tocable: se abre en el visor de la app (descarga con la sesión de Eminus). */
    private fun filaArchivo(nombre: String, tamano: String, url: String): View =
        TextView(this).apply {
            text = if (tamano.isBlank()) nombre else "$nombre · $tamano"
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(ContextCompat.getColor(this@ForumDetailActivity, R.color.badge_info_text))
            iconoInicio(R.drawable.ic_attach_file, tamanoDp = 15, separacionDp = 6)
            val d = resources.displayMetrics.density
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            setOnClickListener {
                com.pako.eminus4.ui.viewer.FileViewerActivity.start(this@ForumDetailActivity, url, nombre,
                    tamano.ifBlank { null }, intent.getStringExtra(EXTRA_TITULO))
            }
        }

    private fun pintarLike(btn: TextView, meGusta: Boolean, likes: Int) {
        btn.text = if (likes > 0) likes.toString() else "Me gusta"
        val color = ContextCompat.getColor(this, if (meGusta) R.color.brand_uv_green else R.color.text_secondary)
        btn.setTextColor(color)
        btn.iconoInicio(if (meGusta) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up, color, tamanoDp = 16, separacionDp = 4)
    }

    /** Optimista: cambia al instante y se revierte si Eminus falla. */
    private fun alternarLike(c: ForumComment, btn: TextView) {
        val nuevo = !c.meGusta
        val likes = (c.likes + if (nuevo) 1 else -1).coerceAtLeast(0)
        actualizarComentario(c.copy(tieneLike = if (nuevo) "Si" else "No", likes = likes))
        pintarLike(btn, nuevo, likes)
        btn.isEnabled = false
        apiClient.likeForumComment(idCurso, c.idComentarioForo) { r ->
            btn.isEnabled = true
            r.onFailure {
                actualizarComentario(c)
                pintarLike(btn, c.meGusta, c.likes)
                mostrarError("No se pudo registrar el me gusta", it)
            }
        }
    }

    /** Reemplaza un comentario (raíz o respuesta) en la lista por su versión actualizada. */
    private fun actualizarComentario(nuevo: ForumComment) {
        comentarios = comentarios.map { raiz ->
            when {
                raiz.idComentarioForo == nuevo.idComentarioForo -> nuevo.copy(respuestas = raiz.respuestas)
                raiz.respuestas.orEmpty().any { it.idComentarioForo == nuevo.idComentarioForo } ->
                    raiz.copy(respuestas = raiz.respuestas.orEmpty().map { if (it.idComentarioForo == nuevo.idComentarioForo) nuevo else it })
                else -> raiz
            }
        }
    }

    private fun cargarRespuestas(c: ForumComment) {
        apiClient.getMoreForumAnswers(idCurso, idForo, c.idComentarioForo) { r ->
            r.onSuccess { todas ->
                val ids = c.respuestas.orEmpty().map { it.idComentarioForo }.toSet()
                val unidas = c.respuestas.orEmpty() + todas.filter { it.idComentarioForo !in ids }
                comentarios = comentarios.map {
                    if (it.idComentarioForo == c.idComentarioForo) it.copy(respuestas = unidas, totalRespuestas = maxOf(it.totalRespuestas, unidas.size)) else it
                }
                pintarComentarios()
            }.onFailure { mostrarError("No se pudieron cargar las respuestas", it) }
        }
    }

    // ===================== PARTICIPAR =====================

    private fun responderA(c: ForumComment?, mencion: String? = null) {
        respondiendoA = c
        if (c == null) {
            layoutRespondiendo.visibility = View.GONE
            editComentario.hint = "Escribe tu participación…"
            return
        }
        layoutRespondiendo.visibility = View.VISIBLE
        txtRespondiendo.text = "Respondiendo a ${c.nombreCompleto?.trim()?.ifBlank { null } ?: "este comentario"}"
        editComentario.hint = "Escribe tu respuesta…"
        if (!mencion.isNullOrBlank() && editComentario.text.isNullOrBlank()) {
            editComentario.setText("@${mencion.trim()} ")
            editComentario.setSelection(editComentario.text.length)
        }
        editComentario.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(editComentario, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun publicar() {
        val texto = editComentario.text.toString().trim()
        if (enviando) return
        if (texto.isEmpty()) {
            if (adjuntos.archivos.isNotEmpty()) Toast.makeText(this, "Escribe algo para acompañar tus archivos", Toast.LENGTH_SHORT).show()
            return
        }
        if (!foroAbierto()) {
            Toast.makeText(this, "El foro ya está cerrado", Toast.LENGTH_SHORT).show()
            return
        }
        val listos = try { adjuntos.prepararParaSubir() } catch (e: Exception) {
            Toast.makeText(this, "No se pudo leer un archivo: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        enviando = true
        btnEnviar.isEnabled = false
        btnEnviar.alpha = 0.5f
        val esRespuesta = respondiendoA != null
        val html = aHtml(texto)
        apiClient.postForumComment(idCurso, idForo, html, respondiendoA?.idComentarioForo ?: 0L) { r ->
            if (r.isSuccess && listos.isNotEmpty()) {
                // El comentario ya existe: subirle los archivos (como Eminus web)
                Toast.makeText(this, "Subiendo archivos…", Toast.LENGTH_SHORT).show()
                conIdDeComentario(r.getOrNull(), texto) { id ->
                    if (id == null) {
                        com.pako.eminus4.util.Adjuntos.borrar(listos)
                        terminarPublicacion(esRespuesta, "El comentario se publicó, pero no se pudieron adjuntar los archivos")
                        return@conIdDeComentario
                    }
                    apiClient.uploadForumCommentFiles(idCurso, idForo, id, listos) { rArch ->
                        com.pako.eminus4.util.Adjuntos.borrar(listos)
                        terminarPublicacion(esRespuesta,
                            rArch.exceptionOrNull()?.let { "El comentario se publicó, pero los archivos fallaron: ${it.message}" })
                    }
                }
                return@postForumComment
            }
            com.pako.eminus4.util.Adjuntos.borrar(listos)
            enviando = false
            btnEnviar.isEnabled = true
            btnEnviar.alpha = 1f
            r.onSuccess {
                editComentario.setText("")
                responderA(null)
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(editComentario.windowToken, 0)
                Toast.makeText(this, if (esRespuesta) "Respuesta publicada" else "Comentario publicado", Toast.LENGTH_SHORT).show()
                cargar()
                if (!esRespuesta) scroll.post { scroll.smoothScrollTo(0, 0) }
            }.onFailure { mostrarError("No se pudo publicar", it) }
        }
    }

    /**
     * Id del comentario recién publicado: el que devolvió Eminus o, si no vino, el más nuevo
     * propio cuyo texto empieza igual (se busca recargando los comentarios).
     */
    private fun conIdDeComentario(idDevuelto: Long?, texto: String, listo: (Long?) -> Unit) {
        if (idDevuelto != null && idDevuelto > 0) { listo(idDevuelto); return }
        val sm = SessionManager(this)
        val yo = sm.getMatriculaSesion()
        val inicio = texto.take(30)
        apiClient.getForumComments(idCurso, idForo) { r ->
            val todos = r.getOrNull().orEmpty().flatMap { listOf(it) + it.respuestas.orEmpty() }
            val mio = todos.filter { sm.normalizarMatricula(it.idUsuario) == yo }
                .filter { HtmlCompat.fromHtml(it.descripcion.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT).toString().contains(inicio) }
                .maxByOrNull { it.idComentarioForo }
            listo(mio?.idComentarioForo)
        }
    }

    private fun terminarPublicacion(esRespuesta: Boolean, aviso: String?) {
        enviando = false
        btnEnviar.isEnabled = true
        btnEnviar.alpha = 1f
        editComentario.setText("")
        adjuntos.limpiar()
        responderA(null)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editComentario.windowToken, 0)
        Toast.makeText(this, aviso ?: if (esRespuesta) "Respuesta publicada" else "Comentario publicado",
            if (aviso != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        cargar()
    }

    /** Texto plano → HTML como lo guarda el editor de Eminus (párrafo con saltos de línea). */
    private fun aHtml(texto: String): String {
        val escapado = texto
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<p>" + escapado.replace("\n", "<br>") + "</p>"
    }

    private fun mostrarError(msg: String, e: Throwable) {
        swipeRefresh.isRefreshing = false
        val detalle = if (e.message?.contains("401") == true) "tu sesión caducó, renuévala" else e.message
        Toast.makeText(this, "$msg: $detalle", Toast.LENGTH_LONG).show()
    }
}
