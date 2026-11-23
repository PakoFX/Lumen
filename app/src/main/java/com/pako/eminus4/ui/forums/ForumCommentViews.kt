package com.pako.eminus4.ui.forums

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.CircleCropTransformation
import com.pako.eminus4.R
import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.util.HtmlEminus
import com.pako.eminus4.util.iconoInicio

/**
 * Arma la vista de cada comentario del foro (con sus respuestas, adjuntos, me gusta y responder).
 * Solo presentación: las acciones se avisan con los callbacks.
 */
class ForumCommentViews(
    private val context: Context,
    private val viewerTitle: String?,
    private val onLike: (ForumComment) -> Unit,
    private val onReply: (root: ForumComment, mention: String?) -> Unit,
    private val onMoreAnswers: (ForumComment) -> Unit,
    private val onOpenFile: (url: String, name: String, size: String) -> Unit
) {
    private val density = context.resources.displayMetrics.density

    fun commentView(parent: ViewGroup, comment: ForumComment, canReply: Boolean): View =
        build(parent, comment, root = comment, isAnswer = false, canReply = canReply)

    private fun build(parent: ViewGroup, c: ForumComment, root: ForumComment, isAnswer: Boolean, canReply: Boolean): View {
        val v = LayoutInflater.from(context).inflate(R.layout.item_forum_comment, parent, false)
        bindAuthor(v, c, isAnswer)
        HtmlEminus.enTextView(v.findViewById(R.id.txtCuerpo), c.descripcion.orEmpty(), viewerTitle)
        v.findViewById<LinearLayout>(R.id.layoutAdjuntos).apply {
            c.archivos.orEmpty().forEach { a ->
                addView(fileRow(a.nombreOriginal ?: "Archivo", a.tamanoLegible(), a.urlDescargaComentario(c.idComentarioForo)))
            }
            visibility = if (c.archivos.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.btnLike).apply {
            paintLike(this, c.meGusta, c.likes)
            setOnClickListener { onLike(c) }
        }
        // Responder (siempre al hilo raíz, como Eminus web; a una respuesta se le menciona)
        v.findViewById<TextView>(R.id.btnResponder).apply {
            visibility = if (canReply) View.VISIBLE else View.GONE
            iconoInicio(R.drawable.ic_reply, tamanoDp = 14, separacionDp = 4)
            setOnClickListener { onReply(root, if (isAnswer) c.nombreCompleto else null) }
        }
        if (!isAnswer) bindAnswers(v, c, canReply)
        return v
    }

    private fun bindAuthor(v: View, c: ForumComment, isAnswer: Boolean) {
        val img = v.findViewById<ImageView>(R.id.imgAutor)
        if (isAnswer) img.layoutParams.apply { width = dp(28); height = dp(28) }   // respuestas: avatar más chico
        c.getAvatarUrl()?.let { url ->
            img.load(url) {
                crossfade(true)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.avatar_vacio)
                error(R.drawable.avatar_vacio)
            }
        }
        v.findViewById<TextView>(R.id.txtAutor).text = c.nombreCompleto?.trim()?.ifBlank { null } ?: "Participante"
        v.findViewById<TextView>(R.id.txtFecha).text = DueDates.timeAgo(DueDates.parseEminusDate(c.fechaPublicacion))
    }

    private fun bindAnswers(v: View, root: ForumComment, canReply: Boolean) {
        val container = v.findViewById<LinearLayout>(R.id.layoutRespuestas)
        root.respuestas.orEmpty().forEach { container.addView(build(container, it, root, isAnswer = true, canReply = canReply)) }
        val missing = root.totalRespuestas - root.respuestas.orEmpty().size
        v.findViewById<TextView>(R.id.btnMasRespuestas).apply {
            visibility = if (missing > 0) View.VISIBLE else View.GONE
            text = "Ver $missing respuesta${if (missing == 1) "" else "s"} más"
            setOnClickListener { onMoreAnswers(root) }
        }
    }

    /** Archivo tocable: se abre en el visor de la app (descarga con la sesión de Eminus). */
    fun fileRow(name: String, size: String, url: String): View = TextView(context).apply {
        text = if (size.isBlank()) name else "$name · $size"
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setTextColor(ContextCompat.getColor(context, R.color.badge_info_text))
        iconoInicio(R.drawable.ic_attach_file, tamanoDp = 15, separacionDp = 6)
        setPadding(0, dp(6), 0, dp(6))
        setOnClickListener { onOpenFile(url, name, size) }
    }

    private fun paintLike(button: TextView, liked: Boolean, likes: Int) {
        button.text = if (likes > 0) likes.toString() else "Me gusta"
        val color = ContextCompat.getColor(context, if (liked) R.color.brand_uv_green else R.color.text_secondary)
        button.setTextColor(color)
        button.iconoInicio(if (liked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up, color, tamanoDp = 16, separacionDp = 4)
    }

    private fun dp(value: Int) = (value * density).toInt()
}
