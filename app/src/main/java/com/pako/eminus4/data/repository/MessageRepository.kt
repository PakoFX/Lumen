package com.pako.eminus4.data.repository

import com.google.gson.JsonObject
import com.pako.eminus4.core.resultOf
import com.pako.eminus4.data.model.MessageDetail
import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.remote.eminus.MessagesApi

/** Mensajes de Eminus. Enviar es un proceso de varios pasos que no debe dejar borradores huérfanos. */
class MessageRepository(private val api: MessagesApi) {

    suspend fun getMessages(folderId: Int): Result<List<MessageItem>> = resultOf { api.getMessages(folderId) }

    suspend fun getDetail(messageId: Long): Result<MessageDetail> = resultOf { api.getDetail(messageId) }

    suspend fun getRecipients(courseId: Long): Result<List<JsonObject>> = resultOf { api.getRecipients(courseId) }

    /**
     * Envía como Eminus web: borrador → adjuntos → enviar. Si un paso falla después de crear el
     * borrador, se borra para no dejar basura en "Borradores".
     * @param parentId 0 = mensaje nuevo; id del original = respuesta.
     */
    suspend fun send(
        subject: String, html: String, recipients: List<JsonObject>, parentId: Long,
        attachments: List<EminusHttp.UploadFile>
    ): Result<Unit> = resultOf {
        val messageId = api.createDraft(parentId)
        try {
            api.uploadFiles(messageId, attachments)
            api.send(messageId, subject, html, recipients, parentId)
        } catch (e: Exception) {
            runCatching { api.deleteDraft(messageId) }
            throw e
        }
    }
}
