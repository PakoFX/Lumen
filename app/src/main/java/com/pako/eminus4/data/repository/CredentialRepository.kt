package com.pako.eminus4.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.data.remote.miuv.CredentialPageParser
import com.pako.eminus4.data.remote.miuv.MiuvHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Credencial digital: volver a traer la foto oficial de MiUV con la sesión del último inicio de sesión. */
class CredentialRepository(private val appContext: Context, private val sessionStore: SessionStore) {

    enum class PhotoResult { OK, NO_SESSION, NO_PHOTO, ERROR }

    suspend fun reloadPhoto(): PhotoResult = withContext(Dispatchers.IO) {
        try {
            fetchAndSavePhoto()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recargar foto: ${e.javaClass.simpleName}")
            PhotoResult.ERROR
        }
    }

    private suspend fun fetchAndSavePhoto(): PhotoResult {
        val credential = sessionStore.getCurrentUserCredential() ?: return PhotoResult.ERROR
        val html = MiuvHttp.fetchPage(MiuvHttp.client(), MiuvHttp.CREDENTIAL_URL) ?: return PhotoResult.NO_SESSION
        if (!CredentialPageParser.isCredentialPage(html)) return PhotoResult.NO_SESSION
        val bytes = CredentialPageParser.photoBase64(html)?.let { Base64.decode(it, Base64.DEFAULT) } ?: return PhotoResult.NO_PHOTO

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < MIN_PHOTO_SIDE || bounds.outHeight < MIN_PHOTO_SIDE) return PhotoResult.NO_PHOTO

        val file = writePhoto(credential.getMatriculaOficial(), bytes)
        sessionStore.saveStudentCredential(credential.copy(photo = "file://" + file.absolutePath))
        Log.i(TAG, "Foto recargada: ${bounds.outWidth}×${bounds.outHeight}")
        return PhotoResult.OK
    }

    /** Un archivo nuevo por persona y por carga: la caché de imágenes nunca muestra la foto de otra cuenta. */
    private fun writePhoto(studentId: String, bytes: ByteArray): File {
        val dir = File(appContext.filesDir, PHOTO_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safeId = studentId.uppercase().replace(Regex("[^A-Z0-9]"), "_")
        return File(dir, "foto_${safeId}_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
    }

    private companion object {
        const val TAG = "MIUV_SYNC"
        const val PHOTO_DIR = "credencial"
        const val MIN_PHOTO_SIDE = 40
    }
}
