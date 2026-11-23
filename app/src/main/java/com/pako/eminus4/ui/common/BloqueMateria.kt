package com.pako.eminus4.ui.common

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pako.eminus4.R
import com.pako.eminus4.domain.SubjectName

/**
 * Bloque de materia para Exámenes y Foros: color, nombre, resumen, aviso (p. ej. "2 disponibles")
 * y flecha. Al tocarlo se despliegan/ocultan sus elementos debajo.
 */
data class DatosBloque(
    val idCurso: Long,
    val materia: String,
    val resumen: String,
    val colorHex: String?,
    val abierto: Boolean,
    val aviso: String? = null,
    val avisoColor: Int = R.color.badge_info_text
)

class BloqueHolder(itemView: View, private val alTocar: (Long) -> Unit) : RecyclerView.ViewHolder(itemView) {

    companion object {
        fun crear(parent: ViewGroup, alTocar: (Long) -> Unit) =
            BloqueHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_bloque_materia, parent, false), alTocar)
    }

    private val color: View = itemView.findViewById(R.id.viewBloqueColor)
    private val materia: TextView = itemView.findViewById(R.id.txtBloqueMateria)
    private val resumen: TextView = itemView.findViewById(R.id.txtBloqueResumen)
    private val aviso: TextView = itemView.findViewById(R.id.txtBloqueAviso)
    private val flecha: ImageView = itemView.findViewById(R.id.imgBloqueFlecha)

    fun bind(d: DatosBloque) {
        val ctx = itemView.context
        materia.text = SubjectName.displayName(d.materia)
        resumen.text = d.resumen
        try { color.setBackgroundColor(Color.parseColor(d.colorHex)) }
        catch (_: Exception) { color.setBackgroundColor(ContextCompat.getColor(ctx, R.color.brand_uv_green)) }
        aviso.visibility = if (d.aviso.isNullOrBlank()) View.GONE else View.VISIBLE
        aviso.text = d.aviso
        aviso.setTextColor(ContextCompat.getColor(ctx, d.avisoColor))
        flecha.rotation = if (d.abierto) 180f else 0f
        itemView.contentDescription = "${d.materia}. ${if (d.abierto) "Ocultar" else "Mostrar"}"
        itemView.setOnClickListener { alTocar(d.idCurso) }
    }
}
