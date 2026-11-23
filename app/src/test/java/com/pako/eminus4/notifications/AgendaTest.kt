package com.pako.eminus4.notifications

import com.pako.eminus4.data.local.CourseCache
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.remote.eminus.ActivitiesApi
import com.pako.eminus4.data.remote.eminus.CoursesApi
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.remote.eminus.ExamsApi
import com.pako.eminus4.data.remote.eminus.ForumsApi
import com.pako.eminus4.data.remote.eminus.JsonMapper
import com.pako.eminus4.data.remote.eminus.MessagesApi
import com.pako.eminus4.data.repository.AgendaRepository
import com.pako.eminus4.data.repository.CourseRepository
import com.pako.eminus4.data.repository.MessageRepository
import com.pako.eminus4.notificaciones.ReminderUpdater
import com.pako.eminus4.ui.notifications.NotificationsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgendaTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()

    private class FakeCache : CourseCache {
        var courses: List<Course> = emptyList()
        override fun getCachedCourses() = courses
        override fun saveCachedCourses(courses: List<Course>) { this.courses = courses }
        override fun setSessionExpired(expired: Boolean) {}
    }

    private class FakeReminders : ReminderUpdater {
        val calls = mutableListOf<String>()
        override fun tasks(activities: List<CourseActivityItem>) { calls += "tasks:${activities.size}" }
        override fun exams(exams: List<ExamItem>) { calls += "exams:${exams.size}" }
        override fun forums(forums: List<ForumItem>) { calls += "forums:${forums.size}" }
    }

    private lateinit var repository: AgendaRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                routes.entries.firstOrNull { request.path!!.contains(it.key) }?.value ?: MockResponse().setResponseCode(404)
        }
        server.start()
        val http = EminusHttp(OkHttpClient(), server.url("/api/").toString(), logger = {})
        val json = JsonMapper()
        val courses = CourseRepository(CoursesApi(http, json, server.url("/api8/getAllCourses").toString()), FakeCache())
        repository = AgendaRepository(courses, ActivitiesApi(http, json), ExamsApi(http, json), ForumsApi(http, json),
            MessageRepository(MessagesApi(http, json)))
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun body(json: String) = MockResponse().setBody(json)

    private fun twoActiveCourses() {
        routes["getAllCourses"] = body(
            """{"contenido":[{"curso":{"idCurso":1,"nombre":"A","fechaTerminoEpoch":0,"examenes":1}},
                {"curso":{"idCurso":2,"nombre":"B","fechaTerminoEpoch":0}}]}"""
        )
        routes["getActividadesEstudiante/1"] = body("""[{"idActividad":10,"titulo":"Tarea"}]""")
    }

    @Test
    fun `agenda completa solo si todas las materias respondieron`() = runTest {
        twoActiveCourses()
        routes["getActividadesEstudiante/2"] = MockResponse().setResponseCode(500)
        val agenda = withContext(Dispatchers.Default) { repository.loadAgenda() }
        assertEquals(1, agenda.activities.size)
        assertEquals(1, agenda.coursesWithErrors)
        assertFalse(agenda.isComplete)
    }

    @Test
    fun `sin agenda completa no se tocan los recordatorios`() = runTest {
        twoActiveCourses()
        routes["getActividadesEstudiante/2"] = MockResponse().setResponseCode(500)
        val reminders = FakeReminders()
        val viewModel = NotificationsViewModel(repository, reminders)
        viewModel.load()
        waitUntil { viewModel.state.value.exams != null && viewModel.state.value.unreadMessages != null }
        assertTrue(reminders.calls.isEmpty())
    }

    @Test
    fun `con todo completo se programan recordatorios y se guarda la foto para Pendientes`() = runTest {
        twoActiveCourses()
        routes["getActividadesEstudiante/2"] = body("[]")
        routes["getExamenesEst/1"] = body("[]")
        routes["GetListadoMensajes"] = body("""[{"idMensaje":5,"leido":0},{"idMensaje":6,"leido":1}]""")
        val reminders = FakeReminders()
        val viewModel = NotificationsViewModel(repository, reminders)
        viewModel.load()
        waitUntil { viewModel.state.value.unreadMessages != null && viewModel.state.value.exams != null && viewModel.state.value.forums != null }
        assertTrue("tasks:1" in reminders.calls)
        assertTrue("exams:0" in reminders.calls)
        assertEquals(1, repository.snapshot.unreadMessages.size)
        assertTrue(repository.snapshot.hasData)
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        }
        assertTrue("tiempo agotado", condition())
    }
}
