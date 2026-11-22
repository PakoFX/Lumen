package com.pako.eminus4.ui.common

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pako.eminus4.R
import com.pako.eminus4.util.Adjuntos
import com.pako.eminus4.util.mostrarIconoArchivo

/**
 * Lista de archivos elegidos para adjuntar (mensajes y comentarios de foro):
 * valida tamaño y cantidad, muestra ícono/nombre/tamaño y permite quitar.
 */
class ListaAdjuntos(
    private val ctx: Context,
    private val contenedor: LinearLayout,
    private val alCambiar: () -> Unit = {}
) {
    val archivos = mutableListOf<Adjuntos.Archivo>()

    fun agregar(uris: List<Uri>) {
        for (uri in uris) {
            if (archivos.size >= Adjuntos.MAX_ARCHIVOS) {
                Toast.makeText(ctx, "Máximo ${Adjuntos.MAX_ARCHIVOS} archivos", Toast.LENGTH_SHORT).show()
                break
            }
            val a = try { Adjuntos.leer(ctx, uri) } catch (_: Exception) { continue }
            when {
                a.tamano > Adjuntos.MAX_BYTES ->
                    Toast.makeText(ctx, "\"${a.nombre}\" pesa más de 20 MB", Toast.LENGTH_LONG).show()
                archivos.any { it.uri == uri } -> Unit
                else -> archivos += a
            }
        }
        pintar()
    }

    fun limpiar() {
        archivos.clear()
        pintar()
    }

    /** Copia todo a caché para subirlo (puede lanzar excepción si un archivo ya no se puede leer). */
    fun prepararParaSubir(): List<Adjuntos.Listo> = archivos.map { Adjuntos.copiarACache(ctx, it) }

    private fun pintar() {
        contenedor.removeAllViews()
        archivos.forEach { a ->
            val fila = LayoutInflater.from(ctx).inflate(R.layout.item_adjunto_local, contenedor, false)
            fila.findViewById<ImageView>(R.id.imgAdjuntoLocal).mostrarIconoArchivo(a.nombre)
            fila.findViewById<TextView>(R.id.txtAdjuntoNombre).text = a.nombre
            fila.findViewById<TextView>(R.id.txtAdjuntoTamano).apply {
                text = Adjuntos.tamanoLegible(a.tamano)
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            fila.findViewById<View>(R.id.btnQuitarAdjunto).setOnClickListener {
                archivos.remove(a); pintar()
            }
            contenedor.addView(fila)
        }
        contenedor.visibility = if (archivos.isEmpty()) View.GONE else View.VISIBLE
        alCambiar()
    }
}
