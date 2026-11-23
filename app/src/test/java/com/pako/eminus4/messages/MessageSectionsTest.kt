package com.pako.eminus4.messages

import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.ui.messages.MessageSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSectionsTest {

    private fun message(id: Long, course: String?, date: String, unread: Boolean) =
        MessageItem(id, fechaEnviado = date, leido = if (unread) 0 else 1, curso = course)

    private val inbox = listOf(
        message(1, "BASES DE DATOS (27873)", "2026-11-20T10:00:00", unread = false),
        message(2, "REDES (39014)", "2026-11-19T10:00:00", unread = true),
        message(3, null, "2026-11-21T10:00:00", unread = false)
    )

    @Test
    fun `todos arriba y luego materias con no leidos primero`() {
        val sections = MessageSections.build(inbox, countUnread = true) { null }
        assertEquals(MessageSections.ALL, sections.first().key)
        assertEquals(3, sections.first().total)
        // Primero la que tiene no leídos; luego por mensaje más reciente
        assertEquals(listOf("REDES", "General", "BASES DE DATOS"), sections.drop(1).map { it.key })
        assertEquals("Bases de Datos", sections.last().title)
    }

    @Test
    fun `en enviados no se cuentan no leidos y sin mensajes no hay secciones`() {
        assertTrue(MessageSections.build(inbox, countUnread = false) { null }.all { it.unread == 0 })
        assertTrue(MessageSections.build(emptyList(), countUnread = true) { null }.isEmpty())
    }

    @Test
    fun `mensajes sin materia van a General y filtrar por seccion`() {
        assertEquals("General", MessageSections.subjectOf(inbox[2]))
        assertEquals(3, MessageSections.messagesOf(inbox, MessageSections.ALL).size)
        assertEquals(listOf(3L), MessageSections.messagesOf(inbox, "General").map { it.idMensaje })
    }
}
