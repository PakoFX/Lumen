package com.pako.eminus4

import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.repository.FileRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: File
    private lateinit var repository: FileRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cache = Files.createTempDirectory("cache").toFile()
        repository = FileRepository(EminusHttp(OkHttpClient(), server.url("/").toString(), logger = {}), cache)
    }

    @After
    fun tearDown() {
        server.shutdown()
        cache.deleteRecursively()
    }

    @Test
    fun `la segunda vez sale de la cache sin descargar`() = runBlocking {
        server.enqueue(MockResponse().setBody("PDF"))
        val url = server.url("/archivo").toString()
        val first = repository.get(url, "Tarea 1 (final).pdf").getOrThrow()
        assertEquals("Tarea_1__final_.pdf", first.name)
        assertEquals("PDF", repository.get(url, "Tarea 1 (final).pdf").getOrThrow().readText())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `si falla no deja un archivo a medias`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = repository.get(server.url("/archivo").toString(), "x.pdf")
        assertTrue(result.isFailure)
        assertFalse(File(cache, "temp_docs/x.pdf").exists())
    }
}
