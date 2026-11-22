package com.pako.eminus4.ui.messages

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.MessageDetail
import com.pako.eminus4.ui.viewer.FileViewerActivity
import java.text.SimpleDateFormat
import java.util.Locale
import com.pako.eminus4.util.mostrarIconoArchivo

class MessageDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MESSAGE_ID = "extra_message_id"

        fun start(context: Context, messageId: Long) {
            val intent = Intent(context, MessageDetailActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EminusApiClient
    private var messageId: Long = 0L

    private lateinit var toolbar: MaterialToolbar
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollContent: NestedScrollView
    private lateinit var txtSubject: TextView
    private lateinit var txtCourseTag: TextView
    private lateinit var imgSenderAvatar: ImageView
    private lateinit var txtSenderName: TextView
    private lateinit var txtSenderEmail: TextView
    private lateinit var txtMessageDate: TextView
    private lateinit var txtDestinatarios: TextView
    private lateinit var txtBody: TextView
    private lateinit var webBody: WebView
    private lateinit var cardAttachments: MaterialCardView
    private lateinit var layoutAttachmentsList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_detail)

        messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
        if (messageId == 0L) {
            finish()
            return
        }

        sessionManager = SessionManager(this)
        apiClient = EminusApiClient(sessionManager)

        inicializarVistas()
        cargarDetalle()
    }

    private fun inicializarVistas() {
        toolbar = findViewById(R.id.toolbar)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
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
        txtDestinatarios = findViewById(R.id.txtDestinatarios)
        txtBody = findViewById(R.id.txtBody)
        webBody = findViewById(R.id.webBody)
        cardAttachments = findViewById(R.id.cardAttachments)
        layoutAttachmentsList = findViewById(R.id.layoutAttachmentsList)

        com.pako.eminus4.util.HtmlEminus.preparar(webBody)
    }

    /**
     * "Responder" solo si el mensaje lo envió otra persona y su curso sigue vigente
     * (igual que Eminus web: no se responde en cursos concluidos).
     */
    private fun configurarResponder(detail: MessageDetail) {
        val fab = findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.fabResponder)
        val sm = com.pako.eminus4.core.SessionManager(this)
        val remitente = detail.remitente
        val idCurso = remitente?.idCurso
        val esMio = sm.normalizarMatricula(remitente?.idUsuario) == sm.getMatriculaSesion()
        val cursoVigente = idCurso != null && sm.getCachedCourses().any { it.idCurso == idCurso && it.isActivo() }
        if (remitente == null || idCurso == null || esMio || !cursoVigente) {
            fab.visibility = View.GONE
            return
        }
        fab.visibility = View.VISIBLE
        val margen = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = margen + nav.bottom
            v.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(fab)
        fab.setOnClickListener {
            ComposeMessageActivity.startRespuesta(this, idCurso, remitente.idUsuario, detail.asunto, detail.idMensaje)
        }
    }

    private fun cargarDetalle() {
        progressBar.visibility = View.VISIBLE
        scrollContent.visibility = View.GONE

        apiClient.getMessageDetail(messageId) { result ->
            progressBar.visibility = View.GONE

            result.onSuccess { detail ->
                scrollContent.visibility = View.VISIBLE
                mostrarDetalle(detail)
            }.onFailure { error ->
                Toast.makeText(this, "Error al cargar mensaje: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun mostrarDetalle(detail: MessageDetail) {
        txtSubject.text = detail.asunto ?: "(Sin asunto)"

        val sender = detail.remitente
        configurarResponder(detail)
        val courseName = sender?.nombreCurso ?: "Mensaje General"
        txtCourseTag.text = courseName.replace(Regex("""\s*\(\d+\)\s*$"""), "").trim()

        txtSenderName.text = sender?.nombre?.trim() ?: "Remitente Desconocido"
        txtSenderEmail.text = sender?.correo?.trim() ?: ""
        txtMessageDate.text = formatearFecha(detail.fecha)

        val fotoUrl = sender?.getFotoUrl()
        if (!fotoUrl.isNullOrBlank()) {
            imgSenderAvatar.load(fotoUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
                error(R.drawable.ic_launcher_foreground)
            }
        }

        // Destinatarios
        val destCount = detail.destinatarios?.size ?: 0
        txtDestinatarios.text = "Para: $destCount destinatario${if (destCount == 1) "" else "s"}"

        // Cuerpo HTML
        val html = detail.mensaje ?: ""
        if (com.pako.eminus4.util.HtmlEminus.necesitaWeb(html)) {
            txtBody.visibility = View.GONE
            webBody.visibility = View.VISIBLE
            com.pako.eminus4.util.HtmlEminus.cargar(webBody, html, 15)
        } else {
            webBody.visibility = View.GONE
            txtBody.visibility = View.VISIBLE
            txtBody.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            txtBody.movementMethod = LinkMovementMethod.getInstance()
        }

        // Archivos adjuntos
        val adjuntos = detail.listaArchivos ?: emptyList()
        val courseId = sender?.idCurso ?: 0L

        if (adjuntos.isNotEmpty()) {
            cardAttachments.visibility = View.VISIBLE
            layoutAttachmentsList.removeAllViews()

            for (adj in adjuntos) {
                val itemView = LayoutInflater.from(this).inflate(R.layout.item_attachment, layoutAttachmentsList, false)
                val icono = itemView.findViewById<ImageView>(R.id.imgAttachmentIcon)
                val nombre = itemView.findViewById<TextView>(R.id.txtAttachmentName)
                val peso = itemView.findViewById<TextView>(R.id.txtAttachmentSize)
                val btnView = itemView.findViewById<MaterialButton>(R.id.btnViewOnline)
                val btnDownload = itemView.findViewById<MaterialButton>(R.id.btnDownload)

                icono.mostrarIconoArchivo(adj.nombreOriginal)
                nombre.text = adj.nombreOriginal
                peso.text = "${adj.getTamanoLegible()} · Mensaje"

                val downloadUrl = adj.getDownloadUrl(courseId, detail.idMensaje)

                btnView.setOnClickListener {
                    FileViewerActivity.start(
                        context = this,
                        url = downloadUrl,
                        fileName = adj.nombreOriginal,
                        fileSize = adj.getTamanoLegible(),
                        activityTitle = detail.asunto
                    )
                }

                btnDownload.setOnClickListener {
                    descargarArchivo(downloadUrl, adj.nombreOriginal)
                }

                layoutAttachmentsList.addView(itemView)
            }
        } else {
            cardAttachments.visibility = View.GONE
        }
    }

    private fun descargarArchivo(downloadUrl: String, fileName: String) {
        try {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle(fileName)
                setDescription("Descargando archivo adjunto de mensaje…")
                val token = sessionManager.getAccessToken()
                if (!token.isNullOrBlank()) {
                    addRequestHeader("Authorization", "Bearer $token")
                }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            downloadManager.enqueue(request)
            Toast.makeText(this, "Descargando: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al descargar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatearFecha(isoDate: String?): String {
        if (isoDate.isNullOrBlank()) return ""
        return try {
            val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val outputFmt = SimpleDateFormat("dd 'de' MMMM yyyy, HH:mm", Locale("es"))
            val date = inputFmt.parse(isoDate.substring(0, 19)) ?: return isoDate
            outputFmt.format(date)
        } catch (_: Exception) {
            isoDate
        }
    }
}

