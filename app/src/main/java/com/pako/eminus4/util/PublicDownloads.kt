package com.pako.eminus4.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.pako.eminus4.appContainer

/**
 * "Descargar": guarda un archivo de Eminus en la carpeta Descargas del teléfono con el sistema de
 * descargas de Android (con el token de la sesión). Lo que queda en Descargas ya es de la persona:
 * cerrar sesión no lo borra.
 */
object PublicDownloads {

    fun enqueue(context: Context, url: String, fileName: String, description: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription(description)
                context.appContainer.sessionStore.getAccessToken()?.takeIf { it.isNotBlank() }
                    ?.let { addRequestHeader("Authorization", "Bearer $it") }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "Descargando: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error al descargar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
