package com.pako.eminus4.data.repository

import android.content.Context
import android.util.Log
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.remote.eminus.await
import com.pako.eminus4.data.remote.miuv.HtmlElements
import com.pako.eminus4.data.remote.miuv.MiuvHttp
import com.pako.eminus4.data.remote.miuv.SchedulePageParser
import com.pako.eminus4.data.remote.miuv.SchedulePdfParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request

/**
 * Horario de clases de MiUV. La página (`szihorc.aspx`) solo trae la hora de inicio; el PDF de su
 * botón "Imprimir" trae el rango completo. Todo usa la sesión de MiUV del último inicio de sesión.
 */
class ScheduleRepository(
    private val appContext: Context,
    private val sessionStore: SessionStore,
    private val backgroundScope: CoroutineScope
) {
    enum class ReloadResult { OK, NO_SESSION, NO_CLASSES, ERROR }

    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Avisa cuando el horario guardado cambió en segundo plano (p. ej. llegaron las horas de término). */
    val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    fun saved(): List<ClaseHorario>? = sessionStore.getSchedule()

    /** Tras el login: completa las horas de término sin hacer esperar a la persona. */
    fun completeEndTimesInBackground() {
        backgroundScope.launch {
            val base = sessionStore.getSchedule().orEmpty().ifEmpty { return@launch }
            val completed = runCatchingLogged("Horario PDF") { fetchEndTimes(base) } ?: return@launch
            sessionStore.saveSchedule(completed)
            _updates.tryEmit(Unit)
        }
    }

    /** "Volver a cargar horario": página + horas de término. */
    suspend fun reload(): ReloadResult = withContext(Dispatchers.IO) {
        try {
            val html = MiuvHttp.fetchPage(MiuvHttp.client(), MiuvHttp.SCHEDULE_URL)
            if (html == null || !html.contains("__VIEWSTATE")) return@withContext ReloadResult.NO_SESSION
            val schedule = SchedulePageParser.parse(html)
            Log.i(TAG, "Recargar horario: ${schedule.size} materias, ${schedule.sumOf { it.sesiones.size }} sesiones")
            if (schedule.isEmpty()) return@withContext ReloadResult.NO_CLASSES
            val completed = runCatchingLogged("Horario PDF") { fetchEndTimes(schedule) }
            sessionStore.saveSchedule(completed ?: schedule)
            ReloadResult.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Recargar horario: ${e.javaClass.simpleName}")
            ReloadResult.ERROR
        }
    }

    /** Repite el botón "Imprimir" (postback con los campos ocultos), lee el PDF y completa las horas. */
    private suspend fun fetchEndTimes(base: List<ClaseHorario>): List<ClaseHorario>? = withContext(Dispatchers.IO) {
        val client = MiuvHttp.client(readTimeoutSeconds = 40)   // el reporte puede tardar
        val html = MiuvHttp.fetchPage(client, MiuvHttp.SCHEDULE_URL) ?: error("sesión de MiUV caducada")
        val hidden = HtmlElements.hiddenFields(html)
        check(hidden.any { it.first == "__VIEWSTATE" }) { "sin __VIEWSTATE" }
        val form = FormBody.Builder().apply {
            hidden.forEach { (name, value) -> add(name, value) }
            add("ctl00\$content\$btnDetalle", "Imprimir")
        }.build()

        val pdf = downloadPdf(client, Request.Builder().url(MiuvHttp.SCHEDULE_URL).post(form).build()) ?: return@withContext null
        PDFBoxResourceLoader.init(appContext)
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        val result = SchedulePdfParser.applyEndTimes(base, text)
        Log.i(TAG, "Horario PDF: hora de término en ${result.completed}/${result.total} sesiones")
        result.schedule.takeIf { result.completed > 0 }
    }

    private suspend fun downloadPdf(client: okhttp3.OkHttpClient, request: Request): ByteArray? {
        repeat(PDF_ATTEMPTS) { attempt ->
            val bytes = client.newCall(request).await().use { r ->
                r.body?.bytes()?.takeIf { r.isSuccessful && it.size > 4 && String(it, 0, 5) == "%PDF-" }
                    .also { if (it == null) Log.w(TAG, "Horario PDF: intento ${attempt + 1} → HTTP ${r.code}") }
            }
            if (bytes != null) return bytes
            delay(PDF_RETRY_DELAY_MS)
        }
        return null
    }

    private inline fun <T> runCatchingLogged(tag: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$tag: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "MIUV_SYNC"
        const val PDF_ATTEMPTS = 2
        const val PDF_RETRY_DELAY_MS = 2_500L
    }
}
