package io.github.karthiaiengineer.rflow.ai

import io.github.karthiaiengineer.rflow.core.WavWriter
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The minutes against a fake Gemini: upload, transcript, minutes, the file deleted, and failures that keep the recording. */
class MinutesTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val wav = File.createTempFile("room", ".wav")

    @After fun stop() {
        server.shutdown()
        wav.delete()
    }

    private fun recording(seconds: Int, loud: Boolean = true) = WavWriter(wav).use { w ->
        repeat(seconds * 10) { w.write(ByteArray(3200) { i -> if (loud && i % 2 == 1) 30 else 0 }) }
    }

    private fun answer(json: JSONObject) = MockResponse().setBody(JSONObject().put("candidates", JSONArray().put(
        JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", json.toString())))))).toString())

    private fun serve(generate: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                val path = request.path!!
                return when {
                    path.startsWith("/upload/v1beta/files") && request.getHeader("X-Goog-Upload-Command") == "start" ->
                        MockResponse().setHeader("x-goog-upload-url", server.url("/uploading").toString())
                    path.startsWith("/uploading") ->
                        MockResponse().setBody("""{"file": {"name": "files/abc", "uri": "https://files/abc", "mimeType": "audio/wav", "state": "ACTIVE"}}""")
                    request.method == "DELETE" -> MockResponse().setBody("{}")
                    path.contains(":generateContent") -> generate(request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun minutes(chunk: Double = Minutes.CHUNK_S) =
        Minutes(OkHttpClient(), "fake-key", server.url("/v1beta").toString().trimEnd('/'), server.url("/upload/v1beta/files").toString(), chunkS = chunk)

    @Test fun writesTheMinutes() {
        recording(3)
        var calls = 0
        serve { request ->
            calls++
            val body = request.body.readUtf8()
            if (calls == 1) {
                assertTrue(body.contains("https://files/abc"))
                assertTrue(body.contains("meeting in person"))
                answer(JSONObject().put("language", "ja")
                    .put("lines", JSONArray().put(JSONObject().put("time", "00:01").put("speaker", "Speaker 1").put("text", "リリースは木曜日にします。")))
                    .put("names", JSONArray().put(JSONObject().put("label", "Speaker 1").put("name", "Tanaka"))))
            } else {
                assertTrue(body.contains("[00:01] Tanaka: リリースは木曜日にします。"))
                answer(JSONObject().put("title", "Release plan").put("summary", "The release moves.")
                    .put("decisions", JSONArray().put("Release on Thursday."))
                    .put("actions", JSONArray().put(JSONObject().put("task", "Tell the team").put("owner", "Tanaka").put("due", "")))
                    .put("language", "ja"))
            }
        }
        val result = minutes().write(wav, "10 November 2026, 10:00")
        assertEquals("Release plan", result.title)
        assertEquals(listOf("Tanaka"), result.speakers)
        assertEquals(Triple("00:01", "Tanaka", "リリースは木曜日にします。"), result.transcript.single())
        assertEquals(Action("Tell the team", "Tanaka", ""), result.actions.single())
        assertTrue("the upload is deleted from Google", requests.contains("DELETE /v1beta/files/abc"))
        val md = result.markdown("10 November 2026, 10:00", 0)
        assertTrue(md.contains("- [ ] Tell the team (Tanaka)"))
        assertEquals(result, MinutesResult.fromJson(result.toJson()))
    }

    @Test fun aLongMeetingGoesInStretches() {
        recording(10)
        var transcripts = 0
        serve { request ->
            val body = request.body.readUtf8()
            if (body.contains("transcript of a meeting")) {
                transcripts++
                if (transcripts == 2) assertTrue("the next stretch knows the last lines", body.contains("[00:01] Speaker 1: one"))
                answer(JSONObject().put("language", "en").put("names", JSONArray())
                    .put("lines", JSONArray().put(JSONObject().put("time", "00:01").put("speaker", "Speaker 1").put("text", if (transcripts == 1) "one" else "two"))))
            } else answer(JSONObject().put("title", "T").put("summary", "S").put("decisions", JSONArray()).put("actions", JSONArray()).put("language", "en"))
        }
        val result = minutes(chunk = 5.0).write(wav, "")
        assertEquals(2, transcripts)
        assertEquals(listOf("00:01", "00:06"), result.transcript.map { it.first })
    }

    @Test fun failuresSaySoAndKeepTheRecording() {
        recording(2)
        serve { MockResponse().setResponseCode(400).setBody("""{"error": {"message": "API key not valid"}}""") }
        try {
            minutes().write(wav, "")
            fail("a refused key must fail")
        } catch (e: MinutesError) {
            assertTrue(e.message!!.startsWith("Gemini didn't accept the key"))
            assertTrue(e.message!!.endsWith(Minutes.KEPT))
        }
        assertTrue(wav.exists())
    }

    @Test fun aSilentRecordingSendsNothing() {
        recording(2, loud = false)
        serve { answer(JSONObject().put("title", "T").put("summary", "Nobody spoke.").put("decisions", JSONArray()).put("actions", JSONArray()).put("language", "")) }
        val result = minutes().write(wav, "")
        assertFalse(requests.any { it.contains("upload") })
        assertTrue(result.transcript.isEmpty())
    }
}
