package com.pako.eminus4

import com.pako.eminus4.data.remote.eminus.ActivitiesApi
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.remote.eminus.JsonMapper
import com.pako.eminus4.data.repository.DeliveryRepository
import com.pako.eminus4.data.repository.DeliveryRepository.Outcome
import com.pako.eminus4.data.repository.DeliveryRepository.Step
import com.pako.eminus4.domain.DeliveryRules
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** El flujo de entregas contra un Eminus falso: nunca toca el real. */
class DeliveryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DeliveryRepository
    private lateinit var file: File

    private val open = """{"idActividad":7,"titulo":"Tarea","estadoAct":2}"""

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val http = EminusHttp(OkHttpClient(), server.url("/api/").toString(), logger = {})
        repository = DeliveryRepository(ActivitiesApi(http, JsonMapper()))
        file = File.createTempFile("entrega", ".pdf").apply { writeText("contenido") }
    }

    @After
    fun tearDown() {
        server.shutdown()
        file.delete()
    }

    private fun enqueue(vararg bodies: String) = bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
    private fun submit(existing: Long = 0L, steps: MutableList<Step> = mutableListOf()) = runBlocking {
        repository.submit(1, 7, existing, EminusHttp.UploadFile(file, "tarea.pdf", "application/pdf"), "<p>hola</p>") { steps += it }
    }

    @Test
    fun `entrega nueva verifica, crea borrador, sube y confirma en ese orden`() {
        enqueue(open, """[{"salida":"55"}]""", "{}", "{}")
        val steps = mutableListOf<Step>()
        assertEquals(Outcome.Done, submit(steps = steps))
        assertEquals(listOf(Step.CHECKING, Step.CREATING_DRAFT, Step.UPLOADING, Step.CONFIRMING), steps)
        val paths = (1..4).map { server.takeRequest() }.map { it.path + " " + it.body.readUtf8().take(200) }
        assertTrue(paths.toString(), paths[0].contains("getActividadEstudiante/1/7"))
        assertTrue(paths.toString(), paths[1].contains("InsertaActividadEntrega"))
        assertTrue(paths.toString(), paths[2].contains("idActividadEntregas=55"))
        assertTrue(paths.toString(), paths[3].contains("\"visible\":1") && paths[3].contains("hola"))
    }

    @Test
    fun `entrega existente no crea otro borrador`() {
        enqueue(open, "{}", "{}")
        val steps = mutableListOf<Step>()
        assertEquals(Outcome.Done, submit(existing = 9, steps = steps))
        assertEquals(listOf(Step.CHECKING, Step.UPLOADING, Step.CONFIRMING), steps)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `actividad cerrada no sube nada`() {
        enqueue("""{"idActividad":7,"titulo":"Tarea","estadoAct":5}""")
        val outcome = submit()
        assertTrue(outcome is Outcome.Blocked)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `por equipos se manda a Eminus web`() {
        enqueue("""{"idActividad":7,"titulo":"Tarea","estadoAct":2,"porEquipos":1}""")
        val outcome = submit() as Outcome.Blocked
        assertTrue(outcome.reason.contains("equipo"))
    }

    @Test
    fun `si falla la confirmacion se sabe en que paso`() {
        enqueue(open, "{}")
        server.enqueue(MockResponse().setResponseCode(500))
        val outcome = submit(existing = 9) as Outcome.Failed
        assertEquals(Step.CONFIRMING, outcome.step)
    }

    @Test
    fun `texto de la entrega`() {
        assertEquals("<p>Entrega: a&amp;b.pdf</p>", DeliveryRules.deliveryHtml(null, "", null, "a&b.pdf"))
        assertEquals("<p>uno</p><p>&lt;dos&gt;</p>", DeliveryRules.deliveryHtml(null, "uno\n<dos>", null, "x"))
        // Comentario sin tocar: se reenvía el HTML original intacto
        assertEquals("<p><a href='x'>liga</a></p>", DeliveryRules.deliveryHtml("<p><a href='x'>liga</a></p>", "liga", "liga", "x"))
    }
}
