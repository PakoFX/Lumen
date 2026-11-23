package com.pako.eminus4.courses

import com.google.gson.Gson
import com.pako.eminus4.data.local.CourseCache
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.remote.eminus.CoursesApi
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.remote.eminus.JsonMapper
import com.pako.eminus4.data.repository.CourseRepository
import com.pako.eminus4.domain.CourseCatalog
import com.pako.eminus4.domain.CourseCatalog.Section
import com.pako.eminus4.ui.courses.CoursesUiState
import com.pako.eminus4.ui.courses.CoursesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoursesTest {

    private val future = System.currentTimeMillis() / 1000 + 86_400 * 30
    private val past = System.currentTimeMillis() / 1000 - 86_400 * 30

    private fun course(id: Long, name: String, end: Long, archived: Int = 0): Course =
        Gson().fromJson("""{"idCurso":$id,"nombre":"$name","fechaTerminoEpoch":$end,"archivado":$archived}""", Course::class.java)

    private val courses = listOf(
        course(1, "BASES DE DATOS (27873)", future),
        course(2, "DISEÑO DE SOFTWARE (11983)", future),
        course(3, "LOGICA (23335)", past),
        course(4, "REDES (39014)", past, archived = 1)
    )

    private class FakeCache(var courses: List<Course> = emptyList()) : CourseCache {
        var expired: Boolean? = null
        override fun getCachedCourses() = courses
        override fun saveCachedCourses(courses: List<Course>) { this.courses = courses }
        override fun setSessionExpired(expired: Boolean) { this.expired = expired }
    }

    private lateinit var server: MockWebServer
    private lateinit var api: CoursesApi

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        val http = EminusHttp(OkHttpClient(), server.url("/api/").toString(), logger = {})
        api = CoursesApi(http, JsonMapper(), allCoursesUrl = server.url("/api8/Course/getAllCourses").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun coursesResponse(n: Int) = MockResponse().setBody(
        """{"contenido":[${(1..n).joinToString(",") { """{"curso":{"idCurso":$it,"nombre":"C$it","fechaTerminoEpoch":0}}""" }}]}"""
    )

    // ---------- Reglas de apartados y búsqueda ----------

    @Test
    fun `apartados cuentan activos, todos y archivados`() {
        val counts = CourseCatalog.select(courses, Section.ACTIVE, "").counts
        assertEquals(CourseCatalog.Counts(active = 2, all = 4, archived = 1), counts)
    }

    @Test
    fun `todos pone activos, luego cerrados y al final archivados`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), CourseCatalog.select(courses, Section.ALL, "").courses.map { it.idCurso })
    }

    @Test
    fun `busqueda sin acentos ni mayusculas y por varias palabras o NRC`() {
        assertEquals(listOf(2L), CourseCatalog.select(courses, Section.ALL, "diseno").courses.map { it.idCurso })
        assertEquals(listOf(1L), CourseCatalog.select(courses, Section.ALL, "bases 27873").courses.map { it.idCurso })
        assertTrue(CourseCatalog.select(courses, Section.ALL, "quimica").courses.isEmpty())
    }

    @Test
    fun `en activos el buscador no aplica`() {
        assertEquals(2, CourseCatalog.select(courses, Section.ACTIVE, "quimica").courses.size)
    }

    // ---------- Repositorio ----------

    @Test
    fun `varias pantallas a la vez comparten una sola peticion`() = runTest {
        server.enqueue(coursesResponse(3))
        val cache = FakeCache()
        val repository = CourseRepository(api, cache)
        val results = withContext(Dispatchers.Default) {
            (1..3).map { async { repository.refresh() } }.awaitAll()
        }
        assertTrue(results.all { it.getOrNull()?.size == 3 })
        assertEquals(1, server.requestCount)
        assertEquals(3, cache.courses.size)
        assertEquals(false, cache.expired)
    }

    @Test
    fun `recargar a la fuerza vuelve a pedir`() = runTest {
        server.enqueue(coursesResponse(1)); server.enqueue(coursesResponse(2))
        val repository = CourseRepository(api, FakeCache())
        repository.refresh()
        assertEquals(2, repository.refresh(force = true).getOrThrow().size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `sesion caducada se marca y conserva la copia local`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val cache = FakeCache(courses)
        val result = CourseRepository(api, cache).refresh()
        assertTrue(result.isFailure)
        assertEquals(true, cache.expired)
        assertEquals(4, cache.courses.size)
    }

    // ---------- ViewModel ----------

    @Test
    fun `sin conexion muestra lo guardado con el aviso de sin conexion`() = runTest {
        server.shutdown()
        val viewModel = CoursesViewModel(CourseRepository(api, FakeCache(courses)))
        waitUntil { !viewModel.state.value.loading }
        assertEquals(4, viewModel.state.value.all.size)
        assertEquals(CoursesUiState.LoadFailure.OFFLINE, viewModel.state.value.loadFailure)
    }

    @Test
    fun `archivar que falla en Eminus se revierte`() = runTest {
        server.enqueue(coursesResponse(2))
        server.enqueue(MockResponse().setResponseCode(500))
        val viewModel = CoursesViewModel(CourseRepository(api, FakeCache()))
        waitUntil { !viewModel.state.value.loading }
        val first = viewModel.state.value.all.first()
        viewModel.setArchived(first, archived = true)
        waitUntil { server.requestCount == 2 && viewModel.state.value.all.first().archivado == 0 }
        assertEquals(0, viewModel.state.value.all.first().archivado)
        assertNull(viewModel.state.value.loadFailure)
    }

    /** Las peticiones van por OkHttp (otro hilo): se espera a que el estado llegue. */
    private suspend fun waitUntil(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }
        assertTrue("tiempo agotado", condition())
    }
}
