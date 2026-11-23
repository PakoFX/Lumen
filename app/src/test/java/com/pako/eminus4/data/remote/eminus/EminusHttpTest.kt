package com.pako.eminus4.data.remote.eminus

import com.pako.eminus4.core.AppError
import com.pako.eminus4.core.isSessionExpired
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class EminusHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var http: EminusHttp
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        http = EminusHttp(OkHttpClient(), server.url("/api/").toString(), logger = { logs += it })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `200 devuelve el cuerpo`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":1}]"""))
        assertEquals("""[{"id":1}]""", http.get("Cursos/x", "cursos"))
        assertEquals("/api/Cursos/x", server.takeRequest().path)
    }

    @Test
    fun `204 devuelve texto vacio`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals("", http.get("x", "t"))
    }

    @Test
    fun `401 es sesion expirada`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val error = captureError { http.get("x", "t") }
        assertTrue(error is AppError.SessionExpired)
        assertTrue(error.isSessionExpired())
    }

    @Test
    fun `500 es error del servidor con su codigo`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("detalle interno"))
        val error = captureError { http.get("x", "t") }
        assertEquals(500, (error as AppError.Server).code)
    }

    @Test
    fun `sin respuesta es error de red`() = runTest {
        server.shutdown()
        val error = captureError { http.get("x", "t") }
        assertTrue(error is AppError.Network)
    }

    @Test
    fun `postJson manda JSON y metodo POST`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        http.postJson("Contenido/Inserta", JSONObject().put("Texto", "hola"), "t")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("""{"Texto":"hola"}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `el log nunca incluye el cuerpo de la respuesta`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("matricula S12345678"))
        captureError { http.get("x", "perfil") }
        assertTrue(logs.none { it.contains("S12345678") })
    }

    private suspend fun captureError(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("Se esperaba un error")
        throw IllegalStateException()
    }
}
