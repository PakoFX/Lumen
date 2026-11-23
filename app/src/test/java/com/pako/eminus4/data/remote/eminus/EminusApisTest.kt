package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.core.AppError
import com.pako.eminus4.data.repository.MessageRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Las APIs por área piden lo mismo que Eminus web y entienden sus respuestas (servidor falso). */
class EminusApisTest {

    private lateinit var server: MockWebServer
    private lateinit var http: EminusHttp
    private val json = JsonMapper()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        http = EminusHttp(OkHttpClient(), server.url("/api/").toString(), logger = {})
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `elementos sin unidad se piden con unidad 0, solo visibles y en orden`() = runTest {
        respond("""[{"idElemento":2,"posicion":1,"visible":1},{"idElemento":1,"posicion":0,"visible":1},{"idElemento":3,"visible":0}]""")
        val elements = CourseSectionsApi(http, json).getElements(137933, 0)
        assertEquals("/api/Contenido/getElementos/137933/0", server.takeRequest().path)
        assertEquals(listOf(1L, 2L), elements.map { it.idElemento })
    }

    @Test
    fun `seguimiento junta las entradas del usuario`() = runTest {
        respond(
            """[{"idUsuario":"zs1","detalle":{"actividades":[{"id":1,"titulo":"A"}]}},
                {"idUsuario":"ZS1","detalle":{"actividades":[{"id":1,"titulo":"A"},{"id":2,"titulo":"B"}],"foros":[{"id":9,"titulo":"F"}]}},
                {"idUsuario":"otro","detalle":{"actividades":[{"id":5,"titulo":"X"}]}}]"""
        )
        val tracking = CourseSectionsApi(http, json).getTracking(1, "zs1")!!
        assertEquals(listOf("A", "B"), tracking.detalle!!.actividades!!.map { it.titulo })
        assertEquals(1, tracking.detalle!!.foros!!.size)
    }

    @Test
    fun `destinatarios dentro de un objeto y objeto sin la llave da lista vacia`() = runTest {
        respond("""{"destinatarios":[{"idUsuario":"a"},{"idUsuario":"b"}]}""")
        respond("""{"otraCosa":1}""")
        val api = MessagesApi(http, json)
        assertEquals(2, api.getRecipients(1).size)
        assertTrue(api.getRecipients(1).isEmpty())
    }

    @Test
    fun `examenes con 204 o lista vacia`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        respond("[]")
        val api = ExamsApi(http, json)
        assertTrue(api.getExams(1).isEmpty())
        assertTrue(api.getExams(1).isEmpty())
    }

    @Test
    fun `numero de entrega en sus tres formas`() {
        assertEquals(123L, ActivitiesApi.parseOutputId("123"))
        assertEquals(45L, ActivitiesApi.parseOutputId("""{"salida":45}"""))
        assertEquals(7L, ActivitiesApi.parseOutputId("""[{"salida":"7"}]"""))
        assertEquals(0L, ActivitiesApi.parseOutputId("nada"))
    }

    @Test
    fun `comentario de foro devuelve el id mas alto`() = runTest {
        respond("""[{"idComentarioForo":10},{"idComentarioForo":12}]""")
        assertEquals(12L, ForumsApi(http, json).postComment(1, 2, "<p>hola</p>", 0))
    }

    @Test
    fun `comentario de foro sin id no truena`() = runTest {
        respond("ok")
        assertNull(ForumsApi(http, json).postComment(1, 2, "x", 0))
    }

    @Test
    fun `enviar mensaje borra el borrador si falla el envio`() = runTest {
        respond("\"555\"")            // borrador creado
        respond("", code = 500)       // falla el envío
        respond("")                   // borrar borrador
        val result = MessageRepository(MessagesApi(http, json)).send("Asunto", "<p>Hola</p>", emptyList(), 0, emptyList())
        assertTrue(result.exceptionOrNull() is AppError.Server)
        server.takeRequest(); server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("PUT", delete.method)
        assertEquals("/api/centromensajes/EliminaMensajeBorrador", delete.path)
        assertEquals("""{"idMensaje":555}""", delete.body.readUtf8())
    }

    @Test
    fun `json con forma inesperada es error de formato`() = runTest {
        respond("{no es json")
        val result = runCatching { ExamsApi(http, json).getExams(1) }
        assertTrue(result.exceptionOrNull() is AppError.Parsing)
    }
}
