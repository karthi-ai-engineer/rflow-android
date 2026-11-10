package io.github.karthiaiengineer.rflow.ai

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class GeminiTest {
    private val server = MockWebServer()
    private lateinit var gemini: Gemini

    @Before fun start() {
        server.start()
        gemini = Gemini(OkHttpClient(), server.url("/v1beta").toString().trimEnd('/'))
    }

    @After fun stop() = server.shutdown()

    @Test fun aWorkingKeyIsSentInAHeaderNotTheUrl() = runTest {
        server.enqueue(MockResponse().setBody("""{"models": []}"""))
        assertEquals(KeyCheck.Works, gemini.check("  fake-key \n"))
        val request = server.takeRequest()
        assertEquals("fake-key", request.getHeader("x-goog-api-key"))
        assertFalse(request.path!!.contains("fake-key"))
    }

    @Test fun aRejectedKey() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error": {"message": "API key not valid"}}"""))
        assertEquals(KeyCheck.Rejected, gemini.check("bad"))
    }

    @Test fun otherErrorsSayWhy() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error": {"message": "The model is overloaded"}}"""))
        assertEquals(KeyCheck.Failed("The model is overloaded"), gemini.check("k"))
    }

    @Test fun noConnection() = runTest {
        server.shutdown()
        assertEquals(KeyCheck.Failed("No connection to Gemini"), gemini.check("k"))
    }
}
