package com.pako.eminus4.ui.messages

import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.domain.DueDates
import com.pako.eminus4.domain.SubjectName

/**
 * Mensajes agrupados por materia (más "Todos los mensajes" arriba). Funciones puras.
 * En "Enviados" no se cuentan los no leídos (son tuyos).
 */
object MessageSections {

    /** Clave especial de la sección "Todos los mensajes" (no puede chocar con un nombre de materia). */
    const val ALL = "\u0000todos"
    private const val GENERAL = "General"

    data class Section(
        val key: String,
        val title: String,
        val total: Int,
        val unread: Int,
        val latest: MessageItem?,
        val colorHex: String?
    )

    fun subjectOf(message: MessageItem): String =
        message.curso?.let { message.getCursoLimpio() }?.takeIf { it.isNotBlank() } ?: GENERAL

    fun build(messages: List<MessageItem>, countUnread: Boolean, colorOf: (String) -> String?): List<Section> {
        if (messages.isEmpty()) return emptyList()
        fun unread(list: List<MessageItem>) = if (countUnread) list.count { it.esNoLeido } else 0
        val bySubject = messages.groupBy(::subjectOf).map { (subject, list) ->
            Section(subject, SubjectName.displayName(subject), list.size, unread(list), latest(list), colorOf(subject))
        }.sortedWith(compareByDescending<Section> { it.unread }.thenByDescending { sentAt(it.latest) })
        return listOf(Section(ALL, "Todos los mensajes", messages.size, unread(messages), latest(messages), null)) + bySubject
    }

    fun messagesOf(messages: List<MessageItem>, key: String): List<MessageItem> =
        if (key == ALL) messages else messages.filter { subjectOf(it) == key }

    fun titleOf(key: String): String = if (key == ALL) "Todos los mensajes" else SubjectName.displayName(key)

    private fun latest(list: List<MessageItem>) = list.maxByOrNull(::sentAt)

    private fun sentAt(message: MessageItem?): Long = DueDates.parseEminusDate(message?.fechaEnviado) ?: 0L
}
