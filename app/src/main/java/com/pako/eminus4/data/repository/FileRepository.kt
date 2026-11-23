package com.pako.eminus4.data.repository

import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.remote.eminus.EminusHttp
import java.io.File

/**
 * Archivos de Eminus para verlos dentro de la app. Se guardan en `cache/temp_docs`
 * (se borra al cerrar sesión) y no se vuelven a descargar si ya están.
 */
class FileRepository(private val http: EminusHttp, private val cacheDir: File) {

    suspend fun get(url: String, fileName: String): Result<File> {
        val target = File(File(cacheDir, FOLDER).apply { mkdirs() }, safeName(fileName))
        if (target.exists() && target.length() > 0) return Result.success(target)
        return resultOf { http.download(url, target, "descarga") }
            .onFailure { target.delete() }   // no dejar archivos a medias que luego parezcan buenos
    }

    companion object {
        const val FOLDER = "temp_docs"

        /** Nombre seguro para la caché (sin caracteres no válidos). */
        fun safeName(fileName: String) = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
