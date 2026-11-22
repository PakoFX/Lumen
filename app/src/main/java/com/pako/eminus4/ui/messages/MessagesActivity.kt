package com.pako.eminus4.ui.messages

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.pako.eminus4.R
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.api.EminusApiClient
import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.util.Plazos

/**
 * Mensajería en dos niveles:
 *  1. Secciones por materia: cuántos mensajes y cuántos sin leer, y el último mensaje.
 *     Arriba, "Todos los mensajes".
 *  2. Al tocar una sección: los mensajes de esa materia (orden de Eminus, cronológico).
 * Las pestañas Recibidos / Enviados aplican en ambos niveles.
 */
class MessagesActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, MessagesActivity::class.java))
        }

        /** Clave especial de la sección "Todos los mensajes". */
        private const val TODOS = "\u0000todos"
    }

    private lateinit var sessionManager: SessionManager
    private lateinit var apiClient: EminusApiClient

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabFolders: TabLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtEmptyTitle: TextView
    private lateinit var txtEmptySubtitle: TextView

    private lateinit var mensajesAdapter: MessageAdapter
    private lateinit var materiasAdapter: MateriasAdapter
    private var currentFolderId: Int = 1 // 1: Recibidos, 2: Enviados

    /** Mensajes de la carpeta actual (sin filtrar). */
    private var todos: List<MessageItem> = emptyList()
    /** null = viendo las secciones; TODOS o el nombre de una materia = viendo sus mensajes. */
    private var materiaAbierta: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)

        sessionManager = SessionManager(this)
        apiClient = EminusApiClient(sessionManager)

        inicializarVistas()
        cargarMensajes()
    }

    private fun inicializarVistas() {
        toolbar = findViewById(R.id.toolbar)
        val appBarLayout = findViewById<View>(R.id.appBarLayout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        toolbar.setNavigationOnClickListener { atras() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = atras()
        })

        tabFolders = findViewById(R.id.tabFolders)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { cargarMensajes() }

        mensajesAdapter = MessageAdapter { item -> MessageDetailActivity.start(this, item.idMensaje) }
        materiasAdapter = MateriasAdapter { seccion -> abrirMateria(seccion.clave) }
        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.adapter = materiasAdapter

        // Redactar: botón flotante sobre la barra de navegación del sistema
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.fabRedactar)
        val margenFab = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = margenFab + nav.bottom
            v.requestLayout()
            insets
        }
        fab.setOnClickListener { ComposeMessageActivity.start(this) }
        // Encoger el botón al desplazarse hacia abajo para no tapar mensajes
        recyclerMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6) fab.shrink() else if (dy < -6) fab.extend()
            }
        })

        tabFolders.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentFolderId = if (tab?.position == 1) 2 else 1
                cargarMensajes()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /** Al volver (p. ej. tras enviar o leer un mensaje), actualizar. */
    override fun onRestart() {
        super.onRestart()
        cargarMensajes()
    }

    private fun cargarMensajes() {
        swipeRefresh.isRefreshing = true
        apiClient.getMessages(currentFolderId) { result ->
            swipeRefresh.isRefreshing = false
            result.onSuccess { list ->
                todos = list
                // Si la materia abierta ya no tiene mensajes en esta carpeta, volver a las secciones
                val m = materiaAbierta
                if (m != null && m != TODOS && todos.none { materiaDe(it) == m }) materiaAbierta = null
                pintar()
            }.onFailure { error ->
                Toast.makeText(this, "Error al cargar mensajes: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===================== NAVEGACIÓN ENTRE NIVELES =====================

    private fun abrirMateria(clave: String) {
        materiaAbierta = clave
        pintar()
        recyclerMessages.scrollToPosition(0)
    }

    private fun atras() {
        if (materiaAbierta != null) {
            materiaAbierta = null
            pintar()
        } else finish()
    }

    private fun pintar() {
        val m = materiaAbierta
        if (m == null) {
            toolbar.title = "Mensajería"
            toolbar.subtitle = null
            pintarSecciones()
        } else {
            val lista = if (m == TODOS) todos else todos.filter { materiaDe(it) == m }
            toolbar.title = if (m == TODOS) "Todos los mensajes" else bonito(m)
            val sinLeer = if (currentFolderId == 1) lista.count { it.esNoLeido } else 0
            toolbar.subtitle = "${lista.size} mensaje${if (lista.size == 1) "" else "s"}" +
                if (sinLeer > 0) " · $sinLeer sin leer" else ""
            if (recyclerMessages.adapter !== mensajesAdapter) recyclerMessages.adapter = mensajesAdapter
            mensajesAdapter.submitList(lista)
            mostrarVacio(lista.isEmpty())
        }
    }

    // ===================== SECCIONES POR MATERIA =====================

    data class Seccion(
        val clave: String,
        val titulo: String,
        val total: Int,
        val sinLeer: Int,
        val ultimo: MessageItem?,
        val color: Int?
    )

    private fun materiaDe(m: MessageItem) =
        m.curso?.let { m.getCursoLimpio() }?.takeIf { it.isNotBlank() } ?: "General"

    /** "ADMINISTRACION DE PROYECTOS DE" → "Administracion de Proyectos de". */
    private fun bonito(nombre: String): String {
        val menores = setOf("de", "del", "la", "las", "los", "y", "e", "en", "para", "con", "el", "a")
        return nombre.lowercase().split(" ").filter { it.isNotBlank() }.mapIndexed { i, w ->
            if (i > 0 && w in menores) w else w.replaceFirstChar { it.uppercase() }
        }.joinToString(" ")
    }

    private fun colorMateria(nombre: String): Int? = sessionManager.getCachedCourses()
        .firstOrNull { it.getNombreLimpio().equals(nombre, ignoreCase = true) }
        ?.let { try { Color.parseColor(it.getColorHexaLimpio()) } catch (_: Exception) { null } }

    private fun masReciente(lista: List<MessageItem>) =
        lista.maxByOrNull { Plazos.parseIso(it.fechaEnviado) ?: 0L }

    private fun pintarSecciones() {
        val contarSinLeer = currentFolderId == 1
        val materias = todos.groupBy { materiaDe(it) }.map { (materia, lista) ->
            Seccion(materia, bonito(materia), lista.size,
                if (contarSinLeer) lista.count { it.esNoLeido } else 0, masReciente(lista), colorMateria(materia))
        }.sortedWith(compareByDescending<Seccion> { it.sinLeer }
            .thenByDescending { Plazos.parseIso(it.ultimo?.fechaEnviado) ?: 0L })

        val secciones = if (todos.isEmpty()) emptyList() else listOf(
            Seccion(TODOS, "Todos los mensajes", todos.size,
                if (contarSinLeer) todos.count { it.esNoLeido } else 0, masReciente(todos), null)
        ) + materias

        if (recyclerMessages.adapter !== materiasAdapter) recyclerMessages.adapter = materiasAdapter
        materiasAdapter.enviados = currentFolderId == 2
        materiasAdapter.submitList(secciones)
        mostrarVacio(secciones.isEmpty())
    }

    private fun mostrarVacio(vacio: Boolean) {
        layoutEmpty.visibility = if (vacio) View.VISIBLE else View.GONE
        if (!vacio) return
        if (currentFolderId == 1) {
            txtEmptyTitle.text = "Bandeja vacía"
            txtEmptySubtitle.text = "No tienes mensajes recibidos por el momento."
        } else {
            txtEmptyTitle.text = "Sin mensajes enviados"
            txtEmptySubtitle.text = "No has enviado mensajes institucionales aún."
        }
    }

    /** Tarjetas de sección: color de la materia, conteo, "N sin leer" y el último mensaje. */
    class MateriasAdapter(private val onClick: (Seccion) -> Unit) :
        ListAdapter<Seccion, MateriasAdapter.VH>(object : DiffUtil.ItemCallback<Seccion>() {
            override fun areItemsTheSame(a: Seccion, b: Seccion) = a.clave == b.clave
            override fun areContentsTheSame(a: Seccion, b: Seccion) = a == b
        }) {

        var enviados = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_materia_mensajes, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val img: ImageView = v.findViewById(R.id.imgMateria)
            private val titulo: TextView = v.findViewById(R.id.txtMateria)
            private val badge: TextView = v.findViewById(R.id.badgeSinLeer)
            private val conteo: TextView = v.findViewById(R.id.txtConteo)
            private val ultimo: TextView = v.findViewById(R.id.txtUltimo)

            fun bind(s: Seccion) {
                val ctx = itemView.context
                val esTodos = s.clave == TODOS
                titulo.text = s.titulo
                img.setImageResource(if (esTodos) R.drawable.ic_inbox else R.drawable.ic_school)
                img.backgroundTintList = ColorStateList.valueOf(
                    s.color ?: ContextCompat.getColor(ctx, if (esTodos) R.color.brand_uv_blue else R.color.brand_uv_green)
                )

                conteo.text = "${s.total} mensaje${if (s.total == 1) "" else "s"}" +
                    if (enviados) " enviado${if (s.total == 1) "" else "s"}" else ""
                if (s.sinLeer > 0) {
                    badge.visibility = View.VISIBLE
                    badge.text = "${s.sinLeer} sin leer"
                    titulo.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    badge.visibility = View.GONE
                }

                val m = s.ultimo
                if (m != null) {
                    ultimo.visibility = View.VISIBLE
                    val quien = if (enviados) "Tú" else m.getNombreRemitente().split(" ").take(2).joinToString(" ")
                    val asunto = m.titulo?.takeIf { it.isNotBlank() } ?: "(Sin asunto)"
                    val cuando = Plazos.haceCuanto(Plazos.parseIso(m.fechaEnviado))
                    ultimo.text = listOf("$quien: $asunto", cuando).filter { it.isNotBlank() }.joinToString(" · ")
                } else {
                    ultimo.visibility = View.GONE
                }
                itemView.setOnClickListener { onClick(s) }
            }
        }
    }
}
