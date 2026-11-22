package com.pako.eminus4.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.pako.eminus4.R
import com.pako.eminus4.core.Constants

/**
 * Hoja inferior con diseño para mostrar el detalle de un examen o foro
 * (antes era un diálogo de texto plano).
 *
 * Uso:
 *   DetalleSheet(ctx).icono(R.drawable.ic_quiz).titulo("…").subtitulo("…")
 *       .estado("Disponible", R.color.badge_success_text)
 *       .fila(R.drawable.ic_event, "Cierra", "24 nov, 23:59")
 *       .puntaje("Calificación", "8.5 / 10").nota("…").mostrar()
 */
class DetalleSheet(private val ctx: Context) {

    private val dialog = BottomSheetDialog(ctx)
    private val vista: View = LayoutInflater.from(ctx).inflate(R.layout.sheet_detalle, null)
    private val filas: LinearLayout = vista.findViewById(R.id.layoutSheetFilas)

    init {
        dialog.setContentView(vista)
        vista.findViewById<View>(R.id.btnSheetCerrar).setOnClickListener { dialog.dismiss() }
        vista.findViewById<View>(R.id.btnSheetEminus).setOnClickListener {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Constants.EMINUS_LOGIN_URL)))
            } catch (_: Exception) { }
            dialog.dismiss()
        }
    }

    fun icono(@DrawableRes res: Int) = apply {
        vista.findViewById<ImageView>(R.id.imgSheetIcono).setImageResource(res)
    }

    fun titulo(texto: String) = apply {
        vista.findViewById<TextView>(R.id.txtSheetTitulo).text = texto
    }

    fun subtitulo(texto: String?) = apply {
        vista.findViewById<TextView>(R.id.txtSheetSubtitulo).apply {
            text = texto.orEmpty()
            visibility = if (texto.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    /** Etiqueta de estado con el color del texto (la píldora es neutra). */
    fun estado(texto: String, @ColorRes color: Int) = apply {
        vista.findViewById<TextView>(R.id.txtSheetEstado).apply {
            text = texto
            setTextColor(ContextCompat.getColor(ctx, color))
            visibility = View.VISIBLE
        }
    }

    fun fila(@DrawableRes icono: Int, etiqueta: String, valor: String) = apply {
        val f = LayoutInflater.from(ctx).inflate(R.layout.item_detalle_fila, filas, false)
        f.findViewById<ImageView>(R.id.imgFilaIcono).setImageResource(icono)
        f.findViewById<TextView>(R.id.txtFilaEtiqueta).text = etiqueta
        f.findViewById<TextView>(R.id.txtFilaValor).text = valor
        filas.addView(f)
    }

    fun puntaje(etiqueta: String, valor: String) = apply {
        vista.findViewById<MaterialCardView>(R.id.cardSheetPuntaje).visibility = View.VISIBLE
        vista.findViewById<TextView>(R.id.txtSheetPuntajeEtiqueta).text = etiqueta
        vista.findViewById<TextView>(R.id.txtSheetPuntaje).text = valor
    }

    fun descripcion(texto: String?) = apply {
        if (texto.isNullOrBlank()) return@apply
        vista.findViewById<TextView>(R.id.txtSheetDescripcion).apply {
            text = texto
            visibility = View.VISIBLE
        }
    }

    fun nota(texto: String) = apply {
        vista.findViewById<View>(R.id.layoutSheetNota).visibility = View.VISIBLE
        vista.findViewById<TextView>(R.id.txtSheetNota).text = texto
    }

    /**
     * Cambia el botón principal ("Abrir en Eminus") por otra acción; con texto null lo oculta.
     */
    fun accion(texto: String?, alTocar: (() -> Unit)?) = apply {
        vista.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSheetEminus).apply {
            if (texto == null) { visibility = View.GONE; return@apply }
            text = texto
            icon = null
            setOnClickListener { alTocar?.invoke(); dialog.dismiss() }
        }
    }

    /** Botón secundario (texto) arriba de "Cerrar". */
    fun accionSecundaria(texto: String, alTocar: () -> Unit) = apply {
        val cerrar = vista.findViewById<View>(R.id.btnSheetCerrar)
        val padre = cerrar.parent as LinearLayout
        val b = com.google.android.material.button.MaterialButton(ctx, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = texto
            setOnClickListener { alTocar(); dialog.dismiss() }
        }
        padre.addView(b, padre.indexOfChild(cerrar), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * ctx.resources.displayMetrics.density).toInt()
        })
    }

    fun mostrar() {
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
}
