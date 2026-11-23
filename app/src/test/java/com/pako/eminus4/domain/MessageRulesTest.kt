package com.pako.eminus4.domain

import com.google.gson.JsonObject
import com.pako.eminus4.data.model.MessageDetail
import com.pako.eminus4.data.model.MessageSender
import com.pako.eminus4.domain.MessageDraft.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRulesTest {

    private fun detail(senderId: String?, courseId: Long?) =
        MessageDetail(idMensaje = 1, remitente = MessageSender(idUsuario = senderId, idCurso = courseId))

    @Test
    fun `responder solo a otra persona de un curso vigente`() {
        assertTrue(MessageRules.canReply(detail("zs999", 5), "S123", setOf(5L)))
        assertFalse(MessageRules.canReply(detail("zs123", 5), "S123", setOf(5L)))     // es mío
        assertFalse(MessageRules.canReply(detail("zs999", 6), "S123", setOf(5L)))     // curso concluido
        assertFalse(MessageRules.canReply(detail("zs999", null), "S123", setOf(5L)))  // sin curso
    }

    @Test
    fun `validacion en orden y asunto de respuesta`() {
        assertEquals(Problem.NO_COURSE, MessageDraft.validate(false, 1, "a", "b"))
        assertEquals(Problem.NO_RECIPIENTS, MessageDraft.validate(true, 0, "a", "b"))
        assertEquals(Problem.NO_SUBJECT, MessageDraft.validate(true, 1, " ", "b"))
        assertEquals(Problem.NO_BODY, MessageDraft.validate(true, 1, "a", ""))
        assertNull(MessageDraft.validate(true, 1, "a", "b"))
        assertEquals("RE: Tarea 3", MessageDraft.replySubject("Tarea 3"))
        assertEquals("Re: Tarea 3", MessageDraft.replySubject("Re: Tarea 3"))
    }

    @Test
    fun `texto a html en parrafos`() {
        assertEquals("<p>hola<br>que tal</p><p>adios &amp; &lt;3</p>", MessageDraft.toHtml("hola\nque tal\n\nadios & <3"))
    }

    @Test
    fun `destinatarios sin uno mismo y ordenados por nombre`() {
        fun person(id: String, name: String) = JsonObject().apply { addProperty("idUsuario", id); addProperty("nombre", name) }
        val list = listOf(person("zs3", "ZETA ALGO"), person("zs1", "YO MISMO"), person("zs2", "ANA LOPEZ"))
        val selectable = MessageDraft.selectable(list, "S1")
        assertEquals(listOf("Ana Lopez", "Zeta Algo"), selectable.map(MessageDraft::recipientName))
    }
}
