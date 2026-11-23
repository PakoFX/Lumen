package com.pako.eminus4.domain

import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.model.ForumDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class ForumThreadTest {

    private val now = System.currentTimeMillis()
    private fun iso(offsetDays: Int) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now + offsetDays * 86_400_000L)

    private val answer = ForumComment(idComentarioForo = 11, idPadre = 10, likes = 0, tieneLike = "No")
    private val root = ForumComment(idComentarioForo = 10, likes = 2, tieneLike = "No", totalRespuestas = 3, respuestas = listOf(answer))
    private val other = ForumComment(idComentarioForo = 20)

    @Test
    fun `foro abierto segun estado y cierre con prorroga`() {
        assertTrue(ForumThread.isOpen(null, now))
        assertTrue(ForumThread.isOpen(ForumDetail(estado = 1, fechaTermino = iso(-1), fechaProrroga = iso(2)), now))
        assertFalse(ForumThread.isOpen(ForumDetail(estado = 1, fechaTermino = iso(-1)), now))
        assertFalse(ForumThread.isOpen(ForumDetail(estado = 2, fechaTermino = iso(5)), now))
        assertTrue(ForumThread.closingText(ForumDetail(estado = 1, fechaTermino = iso(-3)), now).startsWith("Cerró"))
    }

    @Test
    fun `me gusta optimista en una respuesta conserva el hilo`() {
        val updated = ForumThread.replace(listOf(root, other), ForumThread.toggledLike(answer))
        val liked = updated.first().respuestas!!.single()
        assertTrue(liked.meGusta)
        assertEquals(1, liked.likes)
        assertEquals(2, updated.size)
        // Quitar el me gusta nunca baja de 0
        assertEquals(0, ForumThread.toggledLike(liked.copy(likes = 0)).likes)
    }

    @Test
    fun `reemplazar una raiz conserva sus respuestas`() {
        val updated = ForumThread.replace(listOf(root), ForumThread.toggledLike(root.copy(respuestas = null)))
        assertEquals(listOf(11L), updated.single().respuestas!!.map { it.idComentarioForo })
        assertEquals(3, updated.single().likes)
    }

    @Test
    fun `ver mas comentarios y respuestas sin repetir`() {
        assertEquals(listOf(10L, 20L), ForumThread.appendNew(listOf(root), listOf(root, other)).map { it.idComentarioForo })
        val merged = ForumThread.withAllAnswers(listOf(root), 10, listOf(answer, ForumComment(idComentarioForo = 12)))
        assertEquals(listOf(11L, 12L), merged.single().respuestas!!.map { it.idComentarioForo })
        assertEquals(3, merged.single().totalRespuestas)
    }

    @Test
    fun `texto a HTML escapado como el editor de Eminus`() {
        assertEquals("<p>a &lt;b&gt; &amp; c<br>d</p>", ForumThread.toHtml("a <b> & c\nd"))
    }

    @Test
    fun `encontrar mi comentario recien publicado`() {
        val mine = ForumComment(idComentarioForo = 30, idUsuario = "zs123", descripcion = "<p>Hola a todos</p>")
        val older = ForumComment(idComentarioForo = 25, idUsuario = "zs123", descripcion = "<p>Hola a todos</p>")
        val someoneElse = ForumComment(idComentarioForo = 40, idUsuario = "zs999", descripcion = "<p>Hola a todos</p>")
        val strip = { html: String -> html.replace(Regex("<[^>]+>"), "") }
        assertEquals(30L, ForumThread.findOwnComment(listOf(older, mine, someoneElse), "S123", "Hola a todos", strip))
        assertNull(ForumThread.findOwnComment(listOf(someoneElse), "S123", "Hola a todos", strip))
    }

    @Test
    fun `matricula normalizada`() {
        assertEquals("S24022198", StudentId.normalize("zs24022198"))
        assertEquals("S24022198", StudentId.normalize(" zS24022198 "))
        assertNull(StudentId.normalize("null"))
        assertNull(StudentId.normalize(""))
    }
}
