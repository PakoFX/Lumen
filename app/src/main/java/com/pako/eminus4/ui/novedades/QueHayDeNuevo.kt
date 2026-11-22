package com.pako.eminus4.ui.novedades

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.pako.eminus4.AppUpdater
import com.pako.eminus4.R

/**
 * Hoja "¿Qué hay de nuevo?":
 *  - [mostrarSiHayNovedades]: una sola vez tras actualizar (las versiones que el usuario no ha visto).
 *  - [mostrarTodo]: desde Perfil, todas las versiones (sin molestar).
 */
object QueHayDeNuevo {

    private const val PREFS = "que_hay_de_nuevo"
    private const val KEY_VISTA = "ultima_version_vista"

    fun versionActual(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    /**
     * Muestra lo nuevo si esta versión no se había visto. [alCerrar] se llama siempre
     * (haya o no hoja), para encadenar otros avisos sin que se encimen.
     */
    fun mostrarSiHayNovedades(activity: Activity, alCerrar: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val actual = versionActual(activity)
        val vista = prefs.getString(KEY_VISTA, null)
        if (actual.isBlank() || vista == actual) { alCerrar(); return }
        prefs.edit().putString(KEY_VISTA, actual).apply()

        // Versiones que no ha visto: más nuevas que la última vista y no más nuevas que la instalada
        val pendientes = Changelog.versiones.filter { v ->
            (vista == null || AppUpdater.esVersionMayor(v.version, vista)) &&
                !AppUpdater.esVersionMayor(v.version, actual)
        }.let { lista ->
            // Instalación nueva: solo la versión actual (no todo el historial)
            if (vista == null) lista.take(1) else lista
        }
        if (pendientes.isEmpty()) { alCerrar(); return }
        mostrar(activity, pendientes, "Versión $actual", alCerrar)
    }

    /** Todas las versiones (desde Perfil). */
    fun mostrarTodo(ctx: Context) {
        mostrar(ctx, Changelog.versiones, "Versión ${versionActual(ctx)} · historial de cambios") {}
    }

    private fun mostrar(ctx: Context, versiones: List<Changelog.Version>, subtitulo: String, alCerrar: () -> Unit) {
        val dialog = BottomSheetDialog(ctx)
        val vista = LayoutInflater.from(ctx).inflate(R.layout.sheet_que_hay_de_nuevo, null)
        vista.findViewById<TextView>(R.id.txtSubtituloNovedadesVersion).text = subtitulo

        val cont = vista.findViewById<LinearLayout>(R.id.layoutVersiones)
        val inflater = LayoutInflater.from(ctx)
        versiones.forEachIndexed { i, v ->
            // Encabezado de versión solo si hay más de una
            if (versiones.size > 1) {
                cont.addView(TextView(ctx).apply {
                    text = "VERSIÓN ${v.version} · ${v.fecha.uppercase()}"
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(ctx, if (i == 0) R.color.brand_uv_green else R.color.text_tertiary))
                    val d = resources.displayMetrics.density
                    setPadding(0, ((if (i == 0) 8 else 20) * d).toInt(), 0, (4 * d).toInt())
                })
            }
            v.puntos.forEach { p ->
                val fila = inflater.inflate(R.layout.item_novedad_version, cont, false)
                fila.findViewById<ImageView>(R.id.imgPunto).setImageResource(p.icono)
                fila.findViewById<TextView>(R.id.txtPuntoTitulo).text = p.titulo
                fila.findViewById<TextView>(R.id.txtPuntoDetalle).text = p.detalle
                cont.addView(fila)
            }
        }

        // Alto máximo: la lista se desplaza y el botón queda siempre visible
        val maxAlto = (ctx.resources.displayMetrics.heightPixels * 0.55).toInt()
        val scroll = vista.findViewById<View>(R.id.scrollNovedadesVersion)
        scroll.post { if (scroll.height > maxAlto) scroll.layoutParams = scroll.layoutParams.apply { height = maxAlto } }

        vista.findViewById<View>(R.id.btnEntendido).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { alCerrar() }
        dialog.setContentView(vista)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }
}
