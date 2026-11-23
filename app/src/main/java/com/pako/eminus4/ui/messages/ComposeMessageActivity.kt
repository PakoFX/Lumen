package com.pako.eminus4.ui.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.domain.MessageDraft
import com.pako.eminus4.ui.common.ListaAdjuntos
import com.pako.eminus4.util.Adjuntos
import kotlinx.coroutines.launch

/**
 * Redactar o responder un mensaje: materia (solo vigentes), destinatarios, asunto, texto y archivos.
 * Solo dibuja [ComposeUiState]; la lógica vive en [ComposeMessageViewModel].
 */
class ComposeMessageActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CURSO = "idCurso"
        private const val EXTRA_PARA = "idUsuarioPara"
        private const val EXTRA_ASUNTO = "asunto"
        private const val EXTRA_PADRE = "idPadre"

        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, ComposeMessageActivity::class.java))

        /** Mensaje nuevo a una persona de una materia (desde Miembros). */
        fun startPara(ctx: Context, idCurso: Long, idUsuarioPara: String?) {
            ctx.startActivity(Intent(ctx, ComposeMessageActivity::class.java).apply {
                putExtra(EXTRA_CURSO, idCurso)
                putExtra(EXTRA_PARA, idUsuarioPara)
            })
        }

        fun startRespuesta(ctx: Context, idCurso: Long, idUsuarioPara: String?, asuntoOriginal: String?, idMensajeOriginal: Long) {
            ctx.startActivity(Intent(ctx, ComposeMessageActivity::class.java).apply {
                putExtra(EXTRA_CURSO, idCurso)
                putExtra(EXTRA_PARA, idUsuarioPara)
                putExtra(EXTRA_ASUNTO, MessageDraft.replySubject(asuntoOriginal))
                putExtra(EXTRA_PADRE, idMensajeOriginal)
            })
        }
    }

    private val viewModel: ComposeMessageViewModel by viewModels {
        viewModelFactory {
            initializer {
                ComposeMessageViewModel(
                    appContainer.messageRepository, appContainer.courseRepository,
                    parentId = intent.getLongExtra(EXTRA_PADRE, 0L),
                    initialCourseId = intent.getLongExtra(EXTRA_CURSO, -1L),
                    preselectedUserId = intent.getStringExtra(EXTRA_PARA),
                    myStudentId = { appContainer.sessionStore.getSessionStudentId() }
                )
            }
        }
    }

    private lateinit var dropCourse: MaterialAutoCompleteTextView
    private lateinit var tilCourse: TextInputLayout
    private lateinit var btnChoose: MaterialButton
    private lateinit var chipsRecipients: ChipGroup
    private lateinit var txtNoRecipients: TextView
    private lateinit var txtToTitle: TextView
    private lateinit var tilSubject: TextInputLayout
    private lateinit var editSubject: TextInputEditText
    private lateinit var tilBody: TextInputLayout
    private lateinit var editBody: TextInputEditText
    private lateinit var btnSend: MaterialButton
    private lateinit var attachments: ListaAdjuntos

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) attachments.agregar(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose_message)
        setUpToolbarAndInsets()
        bindViews()
        setUpCourses()
        observe()
    }

    private fun setUpToolbarAndInsets() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (viewModel.isReply) "Responder" else "Nuevo mensaje"
        toolbar.setNavigationOnClickListener { leave() }
        val appBar = findViewById<View>(R.id.appBarLayout)
        val sendBar = findViewById<View>(R.id.barraEnviar)
        val sendBarPadding = sendBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootRedactar)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.setPadding(0, bars.top, 0, 0)
            sendBar.setPadding(sendBar.paddingLeft, sendBar.paddingTop, sendBar.paddingRight,
                sendBarPadding + maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun bindViews() {
        dropCourse = findViewById(R.id.dropMateria)
        tilCourse = findViewById(R.id.tilMateria)
        btnChoose = findViewById(R.id.btnAgregarDestinatarios)
        chipsRecipients = findViewById(R.id.chipsDestinatarios)
        txtNoRecipients = findViewById(R.id.txtSinDestinatarios)
        txtToTitle = findViewById(R.id.txtParaTitulo)
        tilSubject = findViewById(R.id.tilAsunto)
        editSubject = findViewById(R.id.editAsunto)
        tilBody = findViewById(R.id.tilMensaje)
        editBody = findViewById(R.id.editMensaje)
        btnSend = findViewById(R.id.btnEnviarMensaje)

        intent.getStringExtra(EXTRA_ASUNTO)?.let { editSubject.setText(it) }
        btnChoose.setOnClickListener { chooseRecipients() }
        attachments = ListaAdjuntos(this, findViewById(R.id.layoutAdjuntosMensaje))
        findViewById<View>(R.id.btnAdjuntarMensaje).setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        btnSend.setOnClickListener { send() }
    }

    private fun setUpCourses() {
        val courses = viewModel.state.value.courses
        dropCourse.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, courses.map { it.getNombreLimpio() }))
        dropCourse.setOnItemClickListener { _, _, position, _ -> viewModel.selectCourse(courses[position]) }
        if (courses.isEmpty()) tilCourse.error = "No tienes materias vigentes para escribir"
        viewModel.state.value.course?.let { dropCourse.setText(it.getNombreLimpio(), false) }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun render(state: ComposeUiState) {
        if (state.course != null) tilCourse.error = null
        btnChoose.isEnabled = state.available.isNotEmpty()
        chipsRecipients.removeAllViews()
        state.chosen.forEach { recipient ->
            chipsRecipients.addView(Chip(this).apply {
                text = MessageDraft.recipientName(recipient)
                isCloseIconVisible = true
                setOnCloseIconClickListener { viewModel.remove(recipient) }
            })
        }
        txtNoRecipients.visibility = if (state.chosen.isEmpty()) View.VISIBLE else View.GONE
        txtNoRecipients.text = when {
            state.course == null -> "Primero elige la materia."
            state.loadingRecipients -> "Cargando participantes…"
            state.recipientsError != null -> "No se pudieron cargar los participantes: ${state.recipientsError}"
            else -> "Toca \"Elegir\" para seleccionar a quién escribirle."
        }
        txtToTitle.text = if (state.chosen.isEmpty()) "Para" else "Para · ${state.chosen.size}"
        btnSend.isEnabled = !state.sending
        btnSend.text = when {
            !state.sending -> "Enviar"
            state.sendingFiles -> "Subiendo archivos y enviando…"
            else -> "Enviando…"
        }
    }

    /** Lista con casillas; la primera ("Todos los participantes") marca o desmarca a todos. */
    private fun chooseRecipients() {
        val state = viewModel.state.value
        val available = state.available
        if (available.isEmpty()) return
        val labels = (listOf("Todos los participantes (${available.size})") + available.map { o ->
            MessageDraft.recipientName(o) + MessageDraft.recipientEmail(o).takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        }).toTypedArray()
        val chosenIds = state.chosen.map(MessageDraft::recipientId).toSet()
        val checked = BooleanArray(labels.size) { i ->
            if (i == 0) state.chosen.size == available.size else MessageDraft.recipientId(available[i - 1]) in chosenIds
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿A quién le escribes?")
            .setMultiChoiceItems(labels, checked) { dialog, index, isChecked ->
                val list = (dialog as AlertDialog).listView
                if (index == 0) {
                    for (k in 1 until labels.size) { checked[k] = isChecked; list.setItemChecked(k, isChecked) }
                } else {
                    checked[index] = isChecked
                    val all = (1 until labels.size).all { checked[it] }
                    checked[0] = all
                    list.setItemChecked(0, all)
                }
            }
            .setPositiveButton("Listo") { _, _ -> viewModel.setChosen(available.filterIndexed { i, _ -> checked[i + 1] }) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun send() {
        val subject = editSubject.text?.toString().orEmpty()
        val body = editBody.text?.toString().orEmpty()
        tilSubject.error = null
        tilBody.error = null
        viewModel.problem(subject, body)?.let { showProblem(it); return }
        // Copiar adjuntos a caché antes de enviar (los Uri del selector pueden caducar)
        val ready = try {
            attachments.prepararParaSubir()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo leer un archivo: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.send(subject, body, ready.map { EminusHttp.UploadFile(it.archivo, it.nombre, it.mime) }) { Adjuntos.borrar(ready) }
    }

    private fun showProblem(problem: MessageDraft.Problem) = when (problem) {
        MessageDraft.Problem.NO_COURSE -> tilCourse.error = "Elige la materia"
        MessageDraft.Problem.NO_RECIPIENTS -> Toast.makeText(this, "Elige al menos un destinatario", Toast.LENGTH_SHORT).show()
        MessageDraft.Problem.NO_SUBJECT -> tilSubject.error = "Escribe un asunto"
        MessageDraft.Problem.NO_BODY -> tilBody.error = "Escribe tu mensaje"
    }

    private fun onEvent(event: ComposeEvent) {
        when (event) {
            ComposeEvent.Sent -> {
                Toast.makeText(this, "Mensaje enviado", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            is ComposeEvent.Invalid -> showProblem(event.problem)
            is ComposeEvent.Failed -> Toast.makeText(this, "No se pudo enviar: ${event.reason}", Toast.LENGTH_LONG).show()
        }
    }

    /** Si ya escribió algo, confirmar antes de descartar. */
    private fun leave() {
        val wroteSomething = !editBody.text.isNullOrBlank() || attachments.archivos.isNotEmpty() ||
            (!editSubject.text.isNullOrBlank() && editSubject.text.toString() != intent.getStringExtra(EXTRA_ASUNTO))
        if (!wroteSomething || viewModel.state.value.sending) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Descartar mensaje?")
            .setMessage("Lo que escribiste no se guardará.")
            .setPositiveButton("Descartar") { _, _ -> finish() }
            .setNegativeButton("Seguir escribiendo", null)
            .show()
    }
}
