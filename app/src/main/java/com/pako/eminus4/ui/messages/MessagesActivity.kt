package com.pako.eminus4.ui.messages

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.ui.messages.MessageSections.Section
import com.pako.eminus4.ui.messages.MessagesUiState.Folder
import kotlinx.coroutines.launch

/**
 * Mensajería en dos niveles:
 *  1. Secciones por materia: cuántos mensajes y cuántos sin leer, y el último mensaje.
 *     Arriba, "Todos los mensajes".
 *  2. Al tocar una sección: los mensajes de esa materia (orden de Eminus, cronológico).
 * Las pestañas Recibidos / Enviados aplican en ambos niveles. Solo dibuja [MessagesUiState].
 */
class MessagesActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, MessagesActivity::class.java))
        }
    }

    private val viewModel: MessagesViewModel by viewModels {
        viewModelFactory { initializer { MessagesViewModel(appContainer.messageRepository, appContainer.courseRepository) } }
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabFolders: TabLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var txtEmptyTitle: TextView
    private lateinit var txtEmptySubtitle: TextView
    private lateinit var messagesAdapter: MessageAdapter
    private lateinit var sectionsAdapter: SectionsAdapter
    private var renderedSection: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        bindViews()
        setUpComposeButton()
        observe()
    }

    /** Al volver (p. ej. tras enviar o leer un mensaje), actualizar. */
    override fun onRestart() {
        super.onRestart()
        viewModel.reload()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBarLayout)) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
            insets
        }
        toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        tabFolders = findViewById(R.id.tabFolders)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        txtEmptyTitle = findViewById(R.id.txtEmptyTitle)
        txtEmptySubtitle = findViewById(R.id.txtEmptySubtitle)

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand_uv_green))
        swipeRefresh.setOnRefreshListener { viewModel.reload() }

        messagesAdapter = MessageAdapter { item -> MessageDetailActivity.start(this, item.idMensaje) }
        sectionsAdapter = SectionsAdapter { section -> viewModel.openSection(section.key) }
        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.adapter = sectionsAdapter

        tabFolders.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) =
                viewModel.selectFolder(if (tab?.position == 1) Folder.SENT else Folder.INBOX)
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /** Redactar: botón flotante sobre la barra del sistema; se encoge al bajar para no tapar mensajes. */
    private fun setUpComposeButton() {
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.fabRedactar)
        val margin = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(fab) { v, insets ->
            (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                margin + insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.requestLayout()
            insets
        }
        fab.setOnClickListener { ComposeMessageActivity.start(this) }
        recyclerMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 6) fab.shrink() else if (dy < -6) fab.extend()
            }
        })
    }

    private fun goBack() {
        if (!viewModel.back()) finish()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.errors.collect { Toast.makeText(this@MessagesActivity, it, Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    private fun render(state: MessagesUiState) {
        swipeRefresh.isRefreshing = state.loading
        val open = state.openSection
        if (open == null) renderSections(state) else renderMessages(state, open)
        if (renderedSection != open) recyclerMessages.scrollToPosition(0)
        renderedSection = open
    }

    private fun renderSections(state: MessagesUiState) {
        toolbar.title = "Mensajería"
        toolbar.subtitle = null
        val sections = MessageSections.build(state.messages, state.countsUnread, viewModel::colorOf)
        if (recyclerMessages.adapter !== sectionsAdapter) recyclerMessages.adapter = sectionsAdapter
        sectionsAdapter.sent = state.folder == Folder.SENT
        sectionsAdapter.submitList(sections)
        renderEmpty(state, sections.isEmpty())
    }

    private fun renderMessages(state: MessagesUiState, key: String) {
        val list = MessageSections.messagesOf(state.messages, key)
        val unread = if (state.countsUnread) list.count { it.esNoLeido } else 0
        toolbar.title = MessageSections.titleOf(key)
        toolbar.subtitle = "${list.size} mensaje${if (list.size == 1) "" else "s"}" + if (unread > 0) " · $unread sin leer" else ""
        if (recyclerMessages.adapter !== messagesAdapter) recyclerMessages.adapter = messagesAdapter
        messagesAdapter.submitList(list)
        renderEmpty(state, list.isEmpty())
    }

    private fun renderEmpty(state: MessagesUiState, empty: Boolean) {
        layoutEmpty.visibility = if (empty && !state.loading) View.VISIBLE else View.GONE
        if (state.folder == Folder.INBOX) {
            txtEmptyTitle.text = "Bandeja vacía"
            txtEmptySubtitle.text = "No tienes mensajes recibidos por el momento."
        } else {
            txtEmptyTitle.text = "Sin mensajes enviados"
            txtEmptySubtitle.text = "No has enviado mensajes institucionales aún."
        }
    }

    /** Tarjetas de sección: color de la materia, conteo, "N sin leer" y el último mensaje. */
    class SectionsAdapter(private val onClick: (Section) -> Unit) :
        ListAdapter<Section, SectionsAdapter.VH>(object : DiffUtil.ItemCallback<Section>() {
            override fun areItemsTheSame(a: Section, b: Section) = a.key == b.key
            override fun areContentsTheSame(a: Section, b: Section) = a == b
        }) {

        var sent = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_materia_mensajes, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val icon: ImageView = v.findViewById(R.id.imgMateria)
            private val title: TextView = v.findViewById(R.id.txtMateria)
            private val badge: TextView = v.findViewById(R.id.badgeSinLeer)
            private val count: TextView = v.findViewById(R.id.txtConteo)
            private val latest: TextView = v.findViewById(R.id.txtUltimo)

            fun bind(s: Section) {
                val ctx = itemView.context
                val isAll = s.key == MessageSections.ALL
                title.text = s.title
                icon.setImageResource(if (isAll) R.drawable.ic_inbox else R.drawable.ic_school)
                val color = s.colorHex?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                    ?: ContextCompat.getColor(ctx, if (isAll) R.color.brand_uv_blue else R.color.brand_uv_green)
                icon.backgroundTintList = ColorStateList.valueOf(color)

                count.text = "${s.total} mensaje${if (s.total == 1) "" else "s"}" +
                    if (sent) " enviado${if (s.total == 1) "" else "s"}" else ""
                badge.visibility = if (s.unread > 0) View.VISIBLE else View.GONE
                badge.text = "${s.unread} sin leer"
                title.setTypeface(null, if (s.unread > 0) Typeface.BOLD else Typeface.NORMAL)

                val m = s.latest
                latest.visibility = if (m != null) View.VISIBLE else View.GONE
                if (m != null) {
                    val who = if (sent) "Tú" else m.getNombreRemitente().split(" ").take(2).joinToString(" ")
                    val subject = m.titulo?.takeIf { it.isNotBlank() } ?: "(Sin asunto)"
                    val time = DueDates.timeAgo(DueDates.parseEminusDate(m.fechaEnviado))
                    latest.text = listOf("$who: $subject", time).filter { it.isNotBlank() }.joinToString(" · ")
                }
                itemView.setOnClickListener { onClick(s) }
            }
        }
    }
}
