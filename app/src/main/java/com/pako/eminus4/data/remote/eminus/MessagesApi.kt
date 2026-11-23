package com.pako.eminus4.data.remote.eminus

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.pako.eminus4.core.AppError
import com.pako.eminus4.data.model.MessageDetail
import com.pako.eminus4.data.model.MessageItem
import org.json.JSONObject

/** Centro de mensajes (API global, servicio centromensajes de Eminus web). */
class MessagesApi(private val http: EminusHttp, private val json: JsonMapper) {

    /** Carpeta 1 = recibidos, 2 = enviados. */
    suspend fun getMessages(folderId: Int): List<MessageItem> =
        json.list(http.get("centromensajes/GetListadoMensajes/$folderId/0", "GetListadoMensajes"))

    suspend fun getDetail(messageId: Long): MessageDetail =
        json.list<MessageDetail>(http.get("centromensajes/GetMensajeDetalle/$messageId", "GetMensajeDetalle")).firstOrNull()
            ?: throw AppError.MissingData("Mensaje no encontrado")

    /** Destinatarios posibles de un curso. Se devuelven tal cual: al enviar, Eminus espera esos objetos. */
    suspend fun getRecipients(courseId: Long): List<JsonObject> =
        json.objects(http.get("centromensajes/getDestinatariosPorCurso/$courseId", "getDestinatariosPorCurso"), key = "destinatarios")

    /** Crea un borrador vacío y devuelve su idMensaje. */
    suspend fun createDraft(parentId: Long): Long {
        val body = http.postRawJson(DRAFT_PATH, messageBody(0, null, null, null, folder = 0, parentId = parentId), "insertaActualizaMensaje (borrador)")
        return body.trim().trim('"').toLongOrNull()?.takeIf { it > 0 } ?: throw AppError.MissingData("Eminus no creó el mensaje")
    }

    /** Llena el borrador y lo manda (carpeta 1 = enviado). */
    suspend fun send(messageId: Long, subject: String, html: String, recipients: List<JsonObject>, parentId: Long) {
        http.postRawJson(DRAFT_PATH, messageBody(messageId, subject, html, recipients, folder = 1, parentId = parentId), "insertaActualizaMensaje (enviar)")
    }

    suspend fun deleteDraft(messageId: Long) {
        http.putJson("centromensajes/EliminaMensajeBorrador", JSONObject().put("idMensaje", messageId), "EliminaMensajeBorrador")
    }

    /** Adjuntos sobre el borrador, antes de enviarlo (como Eminus web). */
    suspend fun uploadFiles(messageId: Long, files: List<EminusHttp.UploadFile>) {
        if (files.isEmpty()) return
        http.upload("centromensajes/adjuntarCentroMensajes/$messageId", files, "adjuntarCentroMensajes")
    }

    /** Eminus distingue entre null y vacío: el borrador lleva nulls explícitos. */
    private fun messageBody(
        messageId: Long, subject: String?, html: String?, recipients: List<JsonObject>?, folder: Int, parentId: Long
    ): String = JsonObject().apply {
        if (subject == null) add("asunto", JsonNull.INSTANCE) else addProperty("asunto", subject)
        if (recipients == null) add("destinatarios", JsonNull.INSTANCE)
        else add("destinatarios", JsonArray().apply { recipients.forEach { add(it) } })
        add("destinatarioscc", if (recipients == null) JsonNull.INSTANCE else JsonArray())
        addProperty("idMensaje", messageId)
        if (html == null) add("mensaje", JsonNull.INSTANCE) else addProperty("mensaje", html)
        addProperty("idMensajeCarpeta", folder)
        addProperty("idMensajePadre", parentId)
    }.toString()

    private companion object {
        const val DRAFT_PATH = "centromensajes/insertaActualizaMensaje"
    }
}
