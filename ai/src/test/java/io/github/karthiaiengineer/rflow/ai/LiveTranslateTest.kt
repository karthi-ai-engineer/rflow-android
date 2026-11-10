package io.github.karthiaiengineer.rflow.ai

import io.github.karthiaiengineer.rflow.core.Line
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The engine against a fake Live API: the setup, the audio sent, the lines built, the voice, and a refused key. */
class LiveTranslateTest {
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<LiveEvent>()
    private val sent = CopyOnWriteArrayList<JSONObject>()
    private var engine: LiveTranslate? = null

    @After fun stop() {
        engine?.stop()
        server.shutdown()
    }

    private fun serve(onAudio: (WebSocket, Int) -> Unit = { _, _ -> }, setup: (WebSocket) -> Unit = {
        it.send("""{"setupComplete": {}}""")
    }) {
        var frames = 0
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                sent += json
                if (json.has("setup")) setup(webSocket)
                if (json.optJSONObject("realtimeInput")?.has("audio") == true) onAudio(webSocket, ++frames)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
        server.start()
    }

    private fun start(key: String = "fake-key"): LiveTranslate {
        val url = server.url("/ws").toString().replace("http", "ws")
        return LiveTranslate(key, "en", OkHttpClient(), { events += it }, url = url, linePauseS = 60.0).also { engine = it; it.start() }
    }

    private fun voice(): ByteArray = ByteArray(3200) { if (it % 2 == 1) 20 else 0 }  // loud enough to be sent

    private fun waitFor(timeout: Long = 5000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("timed out", check())
    }

    @Test fun translatesALine() {
        val pcm = ByteArray(4800) { 1 }
        val spoke = CountDownLatch(1)
        serve(onAudio = { ws, n ->
            if (n == 3) {
                ws.send("""{"serverContent": {"inputTranscription": {"text": "来週の", "languageCode": "ja"}}}""")
                ws.send("""{"serverContent": {"inputTranscription": {"text": "リリース"}}}""")
                ws.send("""{"serverContent": {"outputTranscription": {"text": "Next week's"}}}""")
                ws.send("""{"serverContent": {"outputTranscription": {"text": " release."}, "modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "${Base64.getEncoder().encodeToString(pcm)}"}}]}}}""")
                ws.send("""{"serverContent": {"turnComplete": true}}""")
            }
        })
        val e = start()
        e.onAudio = { bytes, rate -> if (bytes.size == 4800 && rate == 24_000) spoke.countDown() }
        repeat(5) { e.feed(voice()) }
        waitFor { events.any { it is LiveEvent.Done } }
        assertEquals(Line("来週のリリース", "Next week's release.", "ja"), events.filterIsInstance<LiveEvent.Done>().single().line)
        assertTrue(spoke.await(2, TimeUnit.SECONDS))
        assertEquals(0.1, e.audioSeconds, 1e-9)

        val setup = sent.first().getJSONObject("setup")
        assertEquals("models/${Models.LIVE_TRANSLATE}", setup.getString("model"))
        assertEquals("en", setup.getJSONObject("generationConfig").getJSONObject("translationConfig").getString("targetLanguageCode"))
        val audio = sent.first { it.has("realtimeInput") }.getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals(3200, Base64.getDecoder().decode(audio.getString("data")).size)
    }

    @Test fun silenceIsNotSentAfterTwoSeconds() {
        serve()
        val e = start()
        waitFor { events.contains(LiveEvent.Status("Listening")) }
        repeat(30) { e.feed(ByteArray(3200)) }  // 3 s of digital silence
        waitFor { e.unsentSeconds > 0.9 }
        assertEquals(20, e.sentFrames)
        waitFor { sent.any { it.optJSONObject("realtimeInput")?.optBoolean("audioStreamEnd") == true } }
        e.feed(voice())  // sound again: the last 200 ms of silence go first
        waitFor { e.sentFrames == 23 }
    }

    @Test fun theKeyIsNotLoggedAndARefusalStops() {
        serve(setup = { it.close(1008, "API key not valid. Please pass a valid API key.") })
        start(key = "secret-key-123")
        waitFor { events.any { it is LiveEvent.Problem } }
        val problem = events.filterIsInstance<LiveEvent.Problem>().first()
        assertEquals(LiveTranslate.REFUSED_KEY, problem.text)
        assertTrue(problem.final)
        assertTrue(server.takeRequest().path!!.contains("key=secret-key-123"))  // the Live API takes it in the URL
        events.forEach { assertTrue(!it.toString().contains("secret-key-123")) }
    }

    @Suppress("unused") private fun Response.ignore() = Unit
}
