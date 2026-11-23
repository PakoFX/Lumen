package com.pako.eminus4.ui.forums

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.domain.ForumThread
import com.pako.eminus4.ui.common.ListaAdjuntos
import com.pako.eminus4.ui.viewer.FileViewerActivity
import com.pako.eminus4.util.Adjuntos
import com.pako.eminus4.util.HtmlEminus
import kotlinx.coroutines.launch

/**
 * Foro dentro de la app: leer instrucciones y comentarios, dar "me gusta", comentar y responder
 * (con archivos). Solo dibuja [ForumDetailUiState]; la lógica vive en [ForumDetailViewModel].
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

    private val forumTitle by lazy { intent.getStringExtra(EXTRA_TITULO).orEmpty() }

    private val viewModel: ForumDetailViewModel by viewModels {
        viewModelFactory {
            initializer {
                ForumDetailViewModel(
                    appContainer.forumRepository,
                    intent.getLongExtra(EXTRA_CURSO, 0L),
                    intent.getLongExtra(EXTRA_FORO, 0L),
                    myStudentId = { appContainer.sessionStore.getSessionStudentId() },
                    plainText = { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() }
                )
            }
        }
    }

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var txtStatus: TextView
    private lateinit var txtClosing: TextView
    private lateinit var txtDescription: TextView
    private lateinit var txtCommentsTitle: TextView
    private lateinit var layoutComments: LinearLayout
    private lateinit var layoutForumFiles: LinearLayout
    private lateinit var txtNoComments: TextView
    private lateinit var btnMoreComments: MaterialButton
    private lateinit var layoutReplying: View
    private lateinit var txtReplying: TextView
    private lateinit var layoutWrite: View
    private lateinit var txtClosed: TextView
    private lateinit var editComment: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var attachments: ListaAdjuntos
    private lateinit var commentViews: ForumCommentViews

    /** Para no repintar las instrucciones (WebView/imágenes) si no cambiaron. */
    private var renderedDescription: String? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) attachments.agregar(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forum_detail)
        setUpToolbarAndInsets()
        bindViews()
        commentViews = ForumCommentViews(
            this, forumTitle,
            onLike = viewModel::toggleLike,
            onReply = ::replyTo,
            onMoreAnswers = viewModel::loadAllAnswers,
            onOpenFile = { url, name, size -> FileViewerActivity.start(this, url, name, size.ifBlank { null }, forumTitle) }
        )
        observe()
    }

    private fun setUpToolbarAndInsets() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = forumTitle
        toolbar.subtitle = intent.getStringExtra(EXTRA_CURSO_NOMBRE).orEmpty()
        toolbar.setNavigationOnClickListener { finish() }
        // Barra de estado arriba; barra de navegación o teclado abajo (la caja sube con el teclado)
        val appBar = findViewById<View>(R.id.appBarLayout)
        val composer = findViewById<View>(R.id.layoutComposer)
        val composerPadding = composer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootForo)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.setPadding(0, bars.top, 0, 0)
            composer.setPadding(composer.paddingLeft, composer.paddingTop, composer.paddingRight,
                composerPadding + maxOf(bars.bottom, keyboard.bottom))
            insets
        }
    }

    private fun bindViews() {
        swipeRefresh = findViewById(R.id.swipeRefresh)
        scroll = findViewById(R.id.scrollForo)
        txtStatus = findViewById(R.id.txtForoEstado)
        txtClosing = findViewById(R.id.txtForoCierre)
        txtDescription = findViewById(R.id.txtForoDescripcion)
        txtCommentsTitle = findViewById(R.id.txtTituloComentarios)
        layoutComments = findViewById(R.id.layoutComentarios)
        layoutForumFiles = findViewById(R.id.layoutArchivosForo)
        txtNoComments = findViewById(R.id.txtSinComentarios)
        btnMoreComments = findViewById(R.id.btnMasComentarios)
        layoutReplying = findViewById(R.id.layoutRespondiendo)
        txtReplying = findViewById(R.id.txtRespondiendo)
        layoutWrite = findViewById(R.id.layoutEscribir)
        txtClosed = findViewById(R.id.txtForoCerrado)
        editComment = findViewById(R.id.editComentario)
        btnSend = findViewById(R.id.btnEnviar)

        txtDescription.movementMethod = LinkMovementMethod.getInstance()
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { viewModel.load() }
        btnMoreComments.setOnClickListener { viewModel.loadMoreComments() }
        findViewById<View>(R.id.btnCancelarRespuesta).setOnClickListener { viewModel.replyTo(null) }
        btnSend.setOnClickListener { publish() }
        attachments = ListaAdjuntos(this, findViewById(R.id.layoutAdjuntosForo))
        findViewById<View>(R.id.btnAdjuntarForo).setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun render(state: ForumDetailUiState) {
        swipeRefresh.isRefreshing = state.loading
        renderForum(state)
        renderComments(state)
        renderComposer(state)
    }

    private fun renderForum(state: ForumDetailUiState) {
        val detail = state.detail ?: return
        txtStatus.text = if (state.isOpen) "Abierto" else "Cerrado"
        txtStatus.setTextColor(ContextCompat.getColor(this, if (state.isOpen) R.color.badge_success_text else R.color.badge_neutral_text))
        txtClosing.text = ForumThread.closingText(detail)

        // Archivos que el profe adjuntó al foro
        layoutForumFiles.removeAllViews()
        detail.archivos.orEmpty().forEach { a ->
            layoutForumFiles.addView(commentViews.fileRow(a.nombreOriginal ?: "Archivo", a.tamanoLegible(),
                a.urlDescargaForo(intent.getLongExtra(EXTRA_CURSO, 0L), intent.getLongExtra(EXTRA_FORO, 0L))))
        }
        layoutForumFiles.visibility = if (detail.archivos.isNullOrEmpty()) View.GONE else View.VISIBLE

        val html = detail.descripcion.orEmpty()
        if (html != renderedDescription) {
            renderedDescription = html
            val empty = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).isBlank() && !HtmlEminus.necesitaWeb(html)
            if (!empty) HtmlEminus.enTextView(txtDescription, html, forumTitle)   // imágenes dentro del texto
            txtDescription.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun renderComments(state: ForumDetailUiState) {
        layoutComments.removeAllViews()
        txtCommentsTitle.text = if (state.totalComments > 0) "COMENTARIOS · ${state.totalComments}" else "COMENTARIOS"
        txtNoComments.visibility = if (state.comments.isEmpty()) View.VISIBLE else View.GONE
        state.comments.forEach { layoutComments.addView(commentViews.commentView(layoutComments, it, canReply = state.isOpen)) }
        btnMoreComments.visibility = if (state.hasMoreComments) View.VISIBLE else View.GONE
        btnMoreComments.isEnabled = !state.loadingMore
    }

    private fun renderComposer(state: ForumDetailUiState) {
        layoutWrite.visibility = if (state.isOpen) View.VISIBLE else View.GONE
        txtClosed.visibility = if (state.isOpen) View.GONE else View.VISIBLE
        btnSend.isEnabled = !state.sending
        btnSend.alpha = if (state.sending) 0.5f else 1f
        val replying = state.replyingTo
        layoutReplying.visibility = if (replying != null) View.VISIBLE else View.GONE
        editComment.hint = if (replying != null) "Escribe tu respuesta…" else "Escribe tu participación…"
        if (replying != null) {
            txtReplying.text = "Respondiendo a ${replying.nombreCompleto?.trim()?.ifBlank { null } ?: "este comentario"}"
        }
    }

    private fun replyTo(root: ForumComment, mention: String?) {
        viewModel.replyTo(root)
        if (!mention.isNullOrBlank() && editComment.text.isNullOrBlank()) {
            editComment.setText("@${mention.trim()} ")
            editComment.setSelection(editComment.text.length)
        }
        editComment.requestFocus()
        keyboard().showSoftInput(editComment, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun publish() {
        val text = editComment.text.toString().trim()
        if (text.isEmpty()) {
            if (attachments.archivos.isNotEmpty()) toast("Escribe algo para acompañar tus archivos")
            return
        }
        val ready = try {
            attachments.prepararParaSubir()
        } catch (e: Exception) {
            toast("No se pudo leer un archivo: ${e.message}", long = true)
            return
        }
        viewModel.publish(text, ready.map { EminusHttp.UploadFile(it.archivo, it.nombre, it.mime) }) { Adjuntos.borrar(ready) }
    }

    private fun onEvent(event: ForumDetailEvent) {
        when (event) {
            is ForumDetailEvent.Message -> toast(event.text, event.long)
            is ForumDetailEvent.Published -> onPublished(event.wasReply)
        }
    }

    /** Limpia la caja de texto y los archivos; si fue comentario nuevo, sube para verlo. */
    private fun onPublished(wasReply: Boolean) {
        editComment.setText("")
        attachments.limpiar()
        keyboard().hideSoftInputFromWindow(editComment.windowToken, 0)
        if (!wasReply) scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun keyboard() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
