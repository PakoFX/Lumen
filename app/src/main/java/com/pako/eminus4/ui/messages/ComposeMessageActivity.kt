package com.pako.eminus4.ui.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.JsonObject
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.Course

/**
 * Redactar un mensaje nuevo o responder uno recibido (centromensajes de Eminus).
 * Para responder: [startRespuesta] con el curso, el remitente y el asunto original.
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
                val a = asuntoOriginal.orEmpty().trim()
                putExtra(EXTRA_ASUNTO, if (a.startsWith("RE:", ignoreCase = true)) a else "RE: $a")
                putExtra(EXTRA_PADRE, idMensajeOriginal)
            })
        }
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EminusApiClient

    private lateinit var dropMateria: MaterialAutoCompleteTextView
    private lateinit var tilMateria: TextInputLayout
    private lateinit var btnAgregar: MaterialButton
    private lateinit var chipsDestinatarios: ChipGroup
    private lateinit var txtSinDestinatarios: TextView
    private lateinit var tilAsunto: TextInputLayout
    private lateinit var editAsunto: TextInputEditText
    private lateinit var tilMensaje: TextInputLayout
    private lateinit var editMensaje: TextInputEditText
    private lateinit var btnEnviar: MaterialButton

    private var cursos: List<Course> = emptyList()
    private var cursoSel: Course? = null
    /** Destinatarios posibles del curso elegido (objetos tal cual los da Eminus). */
    private var disponibles: List<JsonObject> = emptyList()
    private val elegidos = mutableListOf<JsonObject>()
    private var idPadre = 0L
    private var enviando = false
    private lateinit var adjuntos: com.pako.eminus4.ui.common.ListaAdjuntos

    private val elegirArchivos = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) adjuntos.agregar(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose_message)
        sessionManager = SessionManager(this)
        apiClient = EminusApiClient(sessionManager)
        idPadre = intent.getLongExtra(EXTRA_PADRE, 0L)

        inicializarVistas()
        cargarCursos()
    }

    private fun inicializarVistas() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (idPadre > 0) "Responder" else "Nuevo mensaje"
        toolbar.setNavigationOnClickListener { salir() }

        val appBar = findViewById<View>(R.id.appBarLayout)
        val barra = findViewById<View>(R.id.barraEnviar)
        val padBarra = barra.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootRedactar)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.setPadding(0, sb.top, 0, 0)
            barra.setPadding(barra.paddingLeft, barra.paddingTop, barra.paddingRight, padBarra + maxOf(sb.bottom, ime.bottom))
            insets
        }

        dropMateria = findViewById(R.id.dropMateria)
        tilMateria = findViewById(R.id.tilMateria)
        btnAgregar = findViewById(R.id.btnAgregarDestinatarios)
        chipsDestinatarios = findViewById(R.id.chipsDestinatarios)
        txtSinDestinatarios = findViewById(R.id.txtSinDestinatarios)
        tilAsunto = findViewById(R.id.tilAsunto)
        editAsunto = findViewById(R.id.editAsunto)
        tilMensaje = findViewById(R.id.tilMensaje)
        editMensaje = findViewById(R.id.editMensaje)
        btnEnviar = findViewById(R.id.btnEnviarMensaje)

        intent.getStringExtra(EXTRA_ASUNTO)?.let { editAsunto.setText(it) }
        btnAgregar.setOnClickListener { elegirDestinatarios() }
        adjuntos = com.pako.eminus4.ui.common.ListaAdjuntos(this, findViewById(R.id.layoutAdjuntosMensaje))
        findViewById<View>(R.id.btnAdjuntarMensaje).setOnClickListener { elegirArchivos.launch(arrayOf("*/*")) }
        btnEnviar.setOnClickListener { enviar() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = salir()
        })
    }

    // ===================== MATERIA Y DESTINATARIOS =====================

    /** Igual que Eminus web: solo se puede escribir en cursos vigentes. */
    private fun cargarCursos() {
        cursos = sessionManager.getCachedCourses().filter { it.isActivo() }.sortedBy { it.getNombreLimpio() }
        val nombres = cursos.map { it.getNombreLimpio() }
        dropMateria.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, nombres))
        dropMateria.setOnItemClickListener { _, _, pos, _ -> elegirCurso(cursos[pos]) }

        if (cursos.isEmpty()) {
            tilMateria.error = "No tienes materias vigentes para escribir"
            return
        }
        val inicial = cursos.firstOrNull { it.idCurso == intent.getLongExtra(EXTRA_CURSO, -1L) }
            ?: cursos.singleOrNull()
        inicial?.let {
            dropMateria.setText(it.getNombreLimpio(), false)
            elegirCurso(it, preseleccionar = intent.getStringExtra(EXTRA_PARA))
        }
    }

    private fun elegirCurso(c: Course, preseleccionar: String? = null) {
        if (cursoSel?.idCurso == c.idCurso && disponibles.isNotEmpty()) return
        cursoSel = c
        tilMateria.error = null
        elegidos.clear()
        disponibles = emptyList()
        pintarElegidos()
        btnAgregar.isEnabled = false
        txtSinDestinatarios.text = "Cargando participantes…"
        apiClient.getMessageRecipients(c.idCurso) { r ->
            if (cursoSel?.idCurso != c.idCurso) return@getMessageRecipients
            r.onSuccess { lista ->
                val yo = sessionManager.getMatriculaSesion()
                // Sin uno mismo en la lista
                disponibles = lista.filter { sessionManager.normalizarMatricula(texto(it, "idUsuario")) != yo }
                    .sortedBy { nombre(it) }
                btnAgregar.isEnabled = disponibles.isNotEmpty()
                preseleccionar?.let { id ->
                    disponibles.firstOrNull { texto(it, "idUsuario").equals(id, ignoreCase = true) }?.let { elegidos += it }
                }
                pintarElegidos()
            }.onFailure {
                txtSinDestinatarios.text = "No se pudieron cargar los participantes: ${it.message}"
            }
        }
    }

    private fun texto(o: JsonObject, campo: String): String =
        o.get(campo)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /** "VIVEROS SALMONES FRANCISCO" → "Viveros Salmones Francisco". */
    private fun nombre(o: JsonObject): String {
        val n = listOf("nombre", "nombreCompleto").map { texto(o, it) }.firstOrNull { it.isNotBlank() }
            ?: texto(o, "correo").ifBlank { texto(o, "idUsuario") }
        return n.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun elegirDestinatarios() {
        if (disponibles.isEmpty()) return
        val nombres = (listOf("Todos los participantes (${disponibles.size})") +
            disponibles.map { o -> nombre(o) + texto(o, "correo").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty() })
            .toTypedArray()
        val ids = disponibles.map { texto(it, "idUsuario") }
        val marcados = BooleanArray(nombres.size) { i ->
            if (i == 0) elegidos.size == disponibles.size
            else elegidos.any { texto(it, "idUsuario") == ids[i - 1] }
        }
        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle("¿A quién le escribes?")
            .setMultiChoiceItems(nombres, marcados) { d, i, checked ->
                val lista = (d as androidx.appcompat.app.AlertDialog).listView
                if (i == 0) {
                    for (k in 1 until nombres.size) { marcados[k] = checked; lista.setItemChecked(k, checked) }
                } else {
                    marcados[i] = checked
                    val todos = (1 until nombres.size).all { marcados[it] }
                    marcados[0] = todos; lista.setItemChecked(0, todos)
                }
            }
            .setPositiveButton("Listo") { _, _ ->
                elegidos.clear()
                disponibles.forEachIndexed { i, o -> if (marcados[i + 1]) elegidos += o }
                pintarElegidos()
            }
            .setNegativeButton("Cancelar", null)
            .create()
        dialogo.show()
    }

    private fun pintarElegidos() {
        chipsDestinatarios.removeAllViews()
        elegidos.forEach { o ->
            chipsDestinatarios.addView(Chip(this).apply {
                text = nombre(o)
                isCloseIconVisible = true
                setOnCloseIconClickListener { elegidos.remove(o); pintarElegidos() }
            })
        }
        txtSinDestinatarios.visibility = if (elegidos.isEmpty()) View.VISIBLE else View.GONE
        txtSinDestinatarios.text = when {
            cursoSel == null -> "Primero elige la materia."
            disponibles.isEmpty() -> txtSinDestinatarios.text
            else -> "Toca \"Elegir\" para seleccionar a quién escribirle."
        }
        findViewById<TextView>(R.id.txtParaTitulo).text =
            if (elegidos.isEmpty()) "Para" else "Para · ${elegidos.size}"
    }

    // ===================== ENVIAR =====================

    private fun enviar() {
        if (enviando) return
        val asunto = editAsunto.text?.toString()?.trim().orEmpty()
        val mensaje = editMensaje.text?.toString()?.trim().orEmpty()
        tilAsunto.error = null; tilMensaje.error = null
        when {
            cursoSel == null -> { tilMateria.error = "Elige la materia"; return }
            elegidos.isEmpty() -> { Toast.makeText(this, "Elige al menos un destinatario", Toast.LENGTH_SHORT).show(); return }
            asunto.isEmpty() -> { tilAsunto.error = "Escribe un asunto"; return }
            mensaje.isEmpty() -> { tilMensaje.error = "Escribe tu mensaje"; return }
        }
        // Copiar adjuntos a caché antes de enviar (los Uri del selector pueden caducar)
        val listos = try { adjuntos.prepararParaSubir() } catch (e: Exception) {
            Toast.makeText(this, "No se pudo leer un archivo: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        enviando = true
        btnEnviar.isEnabled = false
        btnEnviar.text = if (listos.isEmpty()) "Enviando…" else "Subiendo archivos y enviando…"
        apiClient.sendMessage(asunto, aHtml(mensaje), elegidos.toList(), idPadre, listos) { r ->
            com.pako.eminus4.util.Adjuntos.borrar(listos)
            enviando = false
            btnEnviar.isEnabled = true
            btnEnviar.text = "Enviar"
            r.onSuccess {
                Toast.makeText(this, "Mensaje enviado", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                val detalle = if (it.message?.contains("401") == true) "tu sesión caducó, renuévala" else it.message
                Toast.makeText(this, "No se pudo enviar: $detalle", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Texto plano → HTML como lo guarda el editor de Eminus. */
    private fun aHtml(texto: String): String {
        val esc = texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return esc.split(Regex("\n{2,}")).joinToString("") { "<p>" + it.replace("\n", "<br>") + "</p>" }
    }

    /** Si ya escribió algo, confirmar antes de descartar. */
    private fun salir() {
        val escrito = !editMensaje.text.isNullOrBlank() || adjuntos.archivos.isNotEmpty() ||
            (!editAsunto.text.isNullOrBlank() && editAsunto.text.toString() != intent.getStringExtra(EXTRA_ASUNTO))
        if (!escrito || enviando) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Descartar mensaje?")
            .setMessage("Lo que escribiste no se guardará.")
            .setPositiveButton("Descartar") { _, _ -> finish() }
            .setNegativeButton("Seguir escribiendo", null)
            .show()
    }
}
