package com.pako.eminus4.ui.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.model.MessageAttachment
import com.pako.eminus4.data.model.MessageDetail
import com.pako.eminus4.ui.viewer.FileViewerActivity
import com.pako.eminus4.util.HtmlEminus
import com.pako.eminus4.util.PublicDownloads
import com.pako.eminus4.util.mostrarIconoArchivo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Un mensaje: remitente, cuerpo (HTML), adjuntos y "Responder". Solo dibuja [MessageDetailUiState]. */
class MessageDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MESSAGE_ID = "extra_message_id"

        fun start(context: Context, messageId: Long) {
            context.startActivity(Intent(context, MessageDetailActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
            })
        }
    }

    private val viewModel: MessageDetailViewModel by viewModels {
        viewModelFactory {
            initializer {
                MessageDetailViewModel(
                    appContainer.messageRepository, appContainer.courseRepository,
                    intent.getLongExtra(EXTRA_MESSAGE_ID, 0L),
                    myStudentId = { appContainer.sessionStore.getSessionStudentId() }
                )
            }
        }
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var scrollContent: NestedScrollView
    private lateinit var txtSubject: TextView
    private lateinit var txtCourseTag: TextView
    private lateinit var imgSenderAvatar: ImageView
    private lateinit var txtSenderName: TextView
    private lateinit var txtSenderEmail: TextView
    private lateinit var txtMessageDate: TextView
    private lateinit var txtRecipients: TextView
    private lateinit var txtBody: TextView
    private lateinit var webBody: WebView
    private lateinit var cardAttachments: MaterialCardView
    private lateinit var layoutAttachments: LinearLayout
    private lateinit var fabReply: ExtendedFloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_detail)
        if (intent.getLongExtra(EXTRA_MESSAGE_ID, 0L) == 0L) {
            finish()
            return
        }
        bindViews()
        observe()
    }

    private fun bindViews() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBarLayout)) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
            insets
        }
        toolbar.setNavigationOnClickListener { finish() }

        progressBar = findViewById(R.id.progressBar)
        scrollContent = findViewById(R.id.scrollContent)
        txtSubject = findViewById(R.id.txtSubject)
        txtCourseTag = findViewById(R.id.txtCourseTag)
        imgSenderAvatar = findViewById(R.id.imgSenderAvatar)
        txtSenderName = findViewById(R.id.txtSenderName)
        txtSenderEmail = findViewById(R.id.txtSenderEmail)
        txtMessageDate = findViewById(R.id.txtMessageDate)
        txtRecipients = findViewById(R.id.txtDestinatarios)
        txtBody = findViewById(R.id.txtBody)
        webBody = findViewById(R.id.webBody)
        cardAttachments = findViewById(R.id.cardAttachments)
        layoutAttachments = findViewById(R.id.layoutAttachmentsList)
        fabReply = findViewById(R.id.fabResponder)
        HtmlEminus.preparar(webBody)

        // "Responder" flota sobre la barra de navegación del sistema
        val margin = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(fabReply) { v, insets ->
            (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                margin + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.requestLayout()
            insets
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) }
        }
    }

    private fun render(state: MessageDetailUiState) {
        progressBar.visibility = if (state is MessageDetailUiState.Loading) View.VISIBLE else View.GONE
        scrollContent.visibility = if (state is MessageDetailUiState.Loaded) View.VISIBLE else View.GONE
        when (state) {
            MessageDetailUiState.Loading -> Unit
            is MessageDetailUiState.Failed -> {
                Toast.makeText(this, "Error al cargar mensaje: ${state.reason}", Toast.LENGTH_SHORT).show()
                finish()
            }
            is MessageDetailUiState.Loaded -> {
                renderHeader(state.detail)
                renderBody(state.detail.mensaje.orEmpty())
                renderAttachments(state.detail)
                renderReply(state.detail, state.canReply)
            }
        }
    }

    private fun renderHeader(detail: MessageDetail) {
        val sender = detail.remitente
        txtSubject.text = detail.asunto ?: "(Sin asunto)"
        txtCourseTag.text = (sender?.nombreCurso ?: "Mensaje General").replace(Regex("""\s*\(\d+\)\s*$"""), "").trim()
        txtSenderName.text = sender?.nombre?.trim() ?: "Remitente Desconocido"
        txtSenderEmail.text = sender?.correo?.trim().orEmpty()
        txtMessageDate.text = formatDate(detail.fecha)
        sender?.getFotoUrl()?.takeIf { it.isNotBlank() }?.let {
            imgSenderAvatar.load(it) {
                crossfade(true)
                transformations(CircleCropTransformation())
                error(R.drawable.ic_launcher_foreground)
            }
        }
        val count = detail.destinatarios?.size ?: 0
        txtRecipients.text = "Para: $count destinatario${if (count == 1) "" else "s"}"
    }

    /** HTML con imágenes, videos o tablas → WebView; si no, texto con enlaces. */
    private fun renderBody(html: String) {
        val needsWeb = HtmlEminus.necesitaWeb(html)
        webBody.visibility = if (needsWeb) View.VISIBLE else View.GONE
        txtBody.visibility = if (needsWeb) View.GONE else View.VISIBLE
        if (needsWeb) {
            HtmlEminus.cargar(webBody, html, 15)
        } else {
            txtBody.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            txtBody.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private fun renderAttachments(detail: MessageDetail) {
        val attachments = detail.listaArchivos.orEmpty()
        cardAttachments.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        layoutAttachments.removeAllViews()
        val courseId = detail.remitente?.idCurso ?: 0L
        attachments.forEach { layoutAttachments.addView(attachmentRow(it, it.getDownloadUrl(courseId, detail.idMensaje), detail.asunto)) }
    }

    private fun attachmentRow(file: MessageAttachment, url: String, subject: String?): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_attachment, layoutAttachments, false)
        row.findViewById<ImageView>(R.id.imgAttachmentIcon).mostrarIconoArchivo(file.nombreOriginal)
        row.findViewById<TextView>(R.id.txtAttachmentName).text = file.nombreOriginal
        row.findViewById<TextView>(R.id.txtAttachmentSize).text = "${file.getTamanoLegible()} · Mensaje"
        row.findViewById<MaterialButton>(R.id.btnViewOnline).setOnClickListener {
            FileViewerActivity.start(context = this, url = url, fileName = file.nombreOriginal,
                fileSize = file.getTamanoLegible(), activityTitle = subject)
        }
        row.findViewById<MaterialButton>(R.id.btnDownload).setOnClickListener {
            PublicDownloads.enqueue(this, url, file.nombreOriginal, "Descargando archivo adjunto de mensaje…")
        }
        return row
    }

    private fun renderReply(detail: MessageDetail, canReply: Boolean) {
        fabReply.visibility = if (canReply) View.VISIBLE else View.GONE
        val sender = detail.remitente ?: return
        val courseId = sender.idCurso ?: return
        if (!canReply) return
        ViewCompat.requestApplyInsets(fabReply)
        fabReply.setOnClickListener {
            ComposeMessageActivity.startRespuesta(this, courseId, sender.idUsuario, detail.asunto, detail.idMensaje)
        }
    }

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(iso.substring(0, 19)) ?: return iso
            SimpleDateFormat("dd 'de' MMMM yyyy, HH:mm", Locale("es")).format(date)
        } catch (_: Exception) {
            iso
        }
    }
}
