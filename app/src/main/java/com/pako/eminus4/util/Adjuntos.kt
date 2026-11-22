package com.pako.eminus4.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Archivos que el usuario elige para adjuntar (mensajes y comentarios de foro).
 * Se copian a caché antes de subirlos porque los Uri del selector pueden caducar.
 */
object Adjuntos {

    /** Límite por archivo del lado de la app (Eminus rechaza archivos muy grandes con 413). */
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_ARCHIVOS = 5

    data class Archivo(val uri: Uri, val nombre: String, val mime: String, val tamano: Long)

    /** Archivo ya copiado a caché, listo para subirse. */
    data class Listo(val archivo: File, val nombre: String, val mime: String)

    fun leer(ctx: Context, uri: Uri): Archivo {
        var nombre = "archivo"
        var tamano = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { nombre = c.getString(it) ?: nombre }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { tamano = c.getLong(it) }
            }
        }
        val mime = ctx.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(nombre.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return Archivo(uri, nombre, mime, tamano)
    }

    fun copiarACache(ctx: Context, a: Archivo): Listo {
        val dir = File(ctx.cacheDir, "adjuntos").apply { mkdirs() }
        val seguro = a.nombre.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val destino = File(dir, "${System.currentTimeMillis()}_$seguro")
        ctx.contentResolver.openInputStream(a.uri).use { input ->
            requireNotNull(input) { "No se pudo leer ${a.nombre}" }
            destino.outputStream().use { out -> input.copyTo(out) }
        }
        return Listo(destino, a.nombre, a.mime)
    }

    fun borrar(listos: List<Listo>) = listos.forEach { it.archivo.delete() }

    fun tamanoLegible(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }
}
