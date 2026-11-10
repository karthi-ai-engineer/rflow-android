package io.github.karthiaiengineer.rflow.ai

import io.github.karthiaiengineer.rflow.core.Line
import io.github.karthiaiengineer.rflow.core.LineBuilder
import io.github.karthiaiengineer.rflow.core.Pcm
import io.github.karthiaiengineer.rflow.core.SpeechGate
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a live translation engine says, from its own threads. */
sealed interface LiveEvent {
    /** The words heard in the line so far (they may still change). */
    data class Heard(val text: String, val language: String) : LiveEvent
    /** Their translation so far. */
    data class Translated(val text: String) : LiveEvent
    /** The line is finished; the next one starts. */
    data class Done(val line: Line) : LiveEvent
    data class Status(val text: String) : LiveEvent
    /** Something went wrong, in plain words; [final]: the engine stopped (trying again won't help). */
    data class Problem(val text: String, val final: Boolean) : LiveEvent
}

/**
 * Gemini Live Translate over the Live API's WebSocket, as Rflow for Windows (`sst/live/gemini.py`):
 *
 *     setup   {"setup": {"model", "generationConfig": {"responseModalities": ["AUDIO"], "translationConfig":
 *              {"targetLanguageCode", "echoTargetLanguage"}}, "inputAudioTranscription": {}, "outputAudioTranscription": {},
 *              "sessionResumption": {"handle"}}}
 *     audio   {"realtimeInput": {"audio": {"data": base64 PCM16 16 kHz, "mimeType": "audio/pcm;rate=16000"}}}
 *     pause   {"realtimeInput": {"audioStreamEnd": true}} after 2 s of silence; nothing is sent until sound comes back
 *     answers serverContent.inputTranscription / outputTranscription (text), modelTurn.parts[].inlineData (the voice,
 *             24 kHz PCM16), turnComplete; sessionResumptionUpdate.newHandle; goAway
 *
 * A connection lives ~10 minutes: a new one is opened at a pause before that and takes up the same session with the
 * resumption handle. The key and the handle are never logged. Feed it 100 ms frames from any thread.
 */
class LiveTranslate(
    private val key: String,
    val target: String,
    private val http: OkHttpClient,
    private val onEvent: (LiveEvent) -> Unit,
    private val url: String = URL,
    private val model: String = Models.LIVE_TRANSLATE,
    private val echoTarget: Boolean = false,
    private val linePauseS: Double = 1.5,
    private val reconnectS: Double = 540.0,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
) {
    /** The cost saver: only speech is sent. May be flipped while running. */
    @Volatile var saving = false
    /** The translation spoken (PCM16 mono at its rate), when it should be heard. */
    @Volatile var onAudio: ((ByteArray, Int) -> Unit)? = null

    private val frames = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)
    @Volatile private var stopped = false
    private var thread: Thread? = null
    private val lock = Any()
    private val line = LineBuilder()
    private var lastText = 0.0
    @Volatile private var voiceAt = 0.0
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed: String? = null
    @Volatile private var goAway = false
    private var opened = 0.0
    private var handle = ""
    private var resume = true
    private var skipping = false
    private var ended = false
    private var quietBytes = 0
    private val preroll = ArrayDeque<ByteArray>()
    private var gate: SpeechGate? = null
    private var quota = 0

    // What was spent, for the usage card: numbers only
    @Volatile var sentFrames = 0; private set
    @Volatile var audioSeconds = 0.0; private set
    @Volatile var unsentSeconds = 0.0; private set
    @Volatile var dropped = 0; private set
    @Volatile var lastWordsAt = 0.0; private set

    fun start() {
        stopped = false
        lastWordsAt = clock()
        thread = Thread(::run, "live-translate").apply { isDaemon = true; start() }
    }

    /** A 100 ms frame of 16 kHz PCM16. Never blocks the capture: when the network falls behind, the oldest goes. */
    fun feed(frame: ByteArray) {
        if (Pcm.level(frame) >= VOICE_LEVEL) voiceAt = clock()
        while (!frames.offer(frame)) {
            frames.poll()?.let { dropped++ }
        }
    }

    fun stop() {
        stopped = true
        ws?.close(1000, null)
        thread?.interrupt()
        thread?.join(3000)
    }

    // -- the connection

    private fun run() {
        var backoff = 1.0
        while (!stopped) {
            var inSetup = true
            val resuming = handle.isNotEmpty()
            try {
                status("Connecting to Google…")
                val socket = open()
                inSetup = false
                backoff = 1.0
                quota = 0
                status("Listening")
                send(socket)
                if (handle.isEmpty()) finishLine()
                socket.close(1000, null)
                ws = null
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                ws?.cancel()
                ws = null
                if (stopped) break
                val reason = scrub(e.message ?: e.javaClass.simpleName)
                if (inSetup && resuming) { handle = ""; continue }  // an old handle: a new session at once
                if (inSetup && resume && reason.contains("sessionResumption", ignoreCase = true)) {
                    resume = false; handle = ""; continue
                }
                val (message, wait) = judge(reason, backoff)
                if (!(wait != null && !inSetup && skipping)) onEvent(LiveEvent.Problem(message, wait == null))
                if (wait == null) break
                status("Reconnecting…")
                try { Thread.sleep((wait * 1000).toLong()) } catch (_: InterruptedException) { break }
                backoff = minOf(backoff * 2, 30.0)
            }
        }
        finishLine()
        status("Stopped")
    }

    private fun open(): WebSocket {
        goAway = false
        closed = null
        val ready = CountDownLatch(1)
        var refused: String? = null
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = message(text, ready)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = message(bytes.utf8(), ready)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return  // an old connection closing late
                closed = "Google closed the connection ($code): $reason"
                refused = closed
                ready.countDown()
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket && ws != null) return
                val body = runCatching { response?.body?.string() }.getOrNull().orEmpty()
                closed = if (response != null) "HTTP ${response.code}: $body" else (t.message ?: t.javaClass.simpleName)
                refused = closed
                ready.countDown()
            }
        }
        val socket = http.newWebSocket(Request.Builder().url("$url?key=$key").build(), listener)
        ws = socket
        val setup = JSONObject().put("setup", JSONObject().apply {
            put("model", "models/$model")
            put("generationConfig", JSONObject()
                .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                .put("translationConfig", JSONObject().put("targetLanguageCode", target).put("echoTargetLanguage", echoTarget)))
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            if (resume) put("sessionResumption", if (handle.isNotEmpty()) JSONObject().put("handle", handle) else JSONObject())
        })
        socket.send(setup.toString())
        ended = false
        if (!ready.await(15, TimeUnit.SECONDS)) throw java.io.IOException("Google didn't answer (timed out)")
        refused?.let { throw java.io.IOException(it) }
        opened = clock()
        return socket
    }

    private fun message(raw: String, ready: CountDownLatch) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.has("setupComplete")) {
            ready.countDown()
            return
        }
        handle(json)
    }

    private fun send(socket: WebSocket) {
        while (!stopped) {
            closed?.let { throw java.io.IOException(it) }
            if (goAway || due()) return
            val frame = frames.poll(200, TimeUnit.MILLISECONDS)
            checkPause()
            if (frame != null) stream(socket, frame)
        }
    }

    private fun due(): Boolean {
        val age = clock() - opened
        if (age <= reconnectS) return false
        if (skipping || age > reconnectS + 45) return true
        val pending = synchronized(lock) { line.pending }
        return !pending && clock() - voiceAt >= 0.3
    }

    /** Sends a frame unless it is more of a long silence (or, with the saver, not speech): Google bills every second. */
    private fun stream(socket: WebSocket, frame: ByteArray) {
        val seconds = frame.size / 32_000.0
        quietBytes = if (Pcm.silent(frame)) quietBytes + frame.size else 0
        val save = saving
        val held = if (save) !(gate ?: SpeechGate().also { gate = it }).process(frame) else quietBytes > SILENCE_SEND_S * 32_000
        val keep = if (save) SpeechGate.PREROLL_FRAMES else PREROLL_FRAMES
        if (held) {
            if (!skipping) {
                skipping = true
                socket.send("""{"realtimeInput": {"audioStreamEnd": true}}""")
                ended = true
            }
            preroll.addLast(frame)
            while (preroll.size > keep) preroll.removeFirst()
            unsentSeconds += seconds
            return
        }
        if (skipping) {
            skipping = false
            for (before in preroll) {
                sendAudio(socket, before)
                unsentSeconds -= before.size / 32_000.0
            }
            preroll.clear()
        }
        sendAudio(socket, frame)
    }

    private fun sendAudio(socket: WebSocket, frame: ByteArray) {
        val data = java.util.Base64.getEncoder().encodeToString(frame)
        socket.send("""{"realtimeInput": {"audio": {"data": "$data", "mimeType": "audio/pcm;rate=16000"}}}""")
        ended = false
        sentFrames++
    }

    // -- the answers

    private fun handle(message: JSONObject) {
        if (message.has("goAway")) goAway = true
        message.optJSONObject("sessionResumptionUpdate")?.let {
            if (it.optBoolean("resumable") && it.optString("newHandle").isNotEmpty()) handle = it.optString("newHandle")
        }
        val content = message.optJSONObject("serverContent") ?: return
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) parts.optJSONObject(i)?.optJSONObject("inlineData")?.let(::voice)
        }
        content.optJSONObject("inputTranscription")?.let { heard ->
            val text = heard.optString("text")
            if (text.isNotEmpty()) add(heard = true, piece = text, language = heard.optString("languageCode"))
        }
        content.optJSONObject("outputTranscription")?.let { said ->
            val text = said.optString("text")
            if (text.isNotEmpty()) add(heard = false, piece = text, language = said.optString("languageCode"))
        }
        if (content.optBoolean("turnComplete")) finishLine()
    }

    private fun voice(blob: JSONObject) {
        val mime = blob.optString("mimeType", "audio/pcm")
        val data = blob.optString("data")
        if (data.isEmpty() || !mime.startsWith("audio/")) return
        val pcm = runCatching { java.util.Base64.getMimeDecoder().decode(data) }.getOrNull() ?: return
        val rate = Regex("""rate=(\d+)""").find(mime)?.groupValues?.get(1)?.toInt() ?: Pcm.VOICE_RATE
        audioSeconds += pcm.size / (2.0 * rate)
        runCatching { onAudio?.invoke(pcm, rate) }
    }

    private fun add(heard: Boolean, piece: String, language: String) {
        val now = clock()
        lastWordsAt = now
        val event: LiveEvent
        val cut: Pair<Int, Int>?
        synchronized(lock) {
            if (heard) line.addSource(piece, language) else line.addTranslation(piece)
            lastText = now
            event = if (heard) LiveEvent.Heard(LineBuilder.tidy(line.source), line.language)
            else LiveEvent.Translated(LineBuilder.tidy(line.translation))
            cut = line.findCut()
        }
        onEvent(event)
        if (cut != null) finishLine(cut)
    }

    private fun checkPause() {
        val due = synchronized(lock) { line.pending && clock() - lastText >= linePauseS }
        if (due) finishLine()
    }

    private fun finishLine(cut: Pair<Int, Int>? = null) {
        val done: Line?
        val rest: Pair<String, String>
        val language: String
        synchronized(lock) {
            done = line.finish(cut)
            rest = line.source to line.translation
            language = line.language
        }
        done?.let { onEvent(LiveEvent.Done(it)) }
        if (rest.first.isNotEmpty()) onEvent(LiveEvent.Heard(LineBuilder.tidy(rest.first), language))
        if (rest.second.isNotEmpty()) onEvent(LiveEvent.Translated(LineBuilder.tidy(rest.second)))
    }

    private fun status(text: String) = onEvent(LiveEvent.Status(text))

    private fun scrub(text: String) = if (key.isNotEmpty()) text.replace(key, "***") else text

    /** The problem in plain words, and how long to wait before trying again (null: it won't help; stop). */
    private fun judge(reason: String, backoff: Double): Pair<String, Double?> {
        val raw = reason.lowercase()
        return when {
            "http 402" in raw || CREDIT.containsMatchIn(raw) || ("billing" in raw && "quota" !in raw) -> NO_CREDIT to null
            "http 401" in raw || "http 403" in raw || "api key" in raw || "api_key" in raw || "unauthenticated" in raw ->
                REFUSED_KEY to null
            "not found" in raw || "is not supported" in raw || "http 404" in raw || "permission" in raw -> NO_MODEL to null
            "http 429" in raw || "quota" in raw || "resource_exhausted" in raw || "rate limit" in raw -> when {
                Regex("""per[ _]?day|daily|\brpd\b""").containsMatchIn(raw) -> QUOTA_DAY to null
                ++quota >= 3 -> QUOTA_STOP to null
                else -> QUOTA_NOW to 30.0
            }
            "(1007)" in raw || "invalid json" in raw || "unknown name" in raw || "invalid argument" in raw -> NOT_ACCEPTED to null
            "unable to resolve" in raw || "timed out" in raw || "failed to connect" in raw || "network" in raw ->
                "Can't reach Google: check the internet connection. Trying again shortly." to backoff
            else -> "The connection to Google broke: trying again shortly." to backoff
        }
    }

    companion object {
        const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val QUEUE_FRAMES = 50  // 5 s
        const val SILENCE_SEND_S = 2.0
        const val PREROLL_FRAMES = 2
        val VOICE_LEVEL = Math.pow(10.0, -45.0 / 20)

        const val REFUSED_KEY = "Gemini didn't accept the key: check it in Settings."
        const val NO_CREDIT = "Gemini credit used up: add credit in Google AI Studio."
        const val NO_MODEL = "This key can't use Gemini Live Translate (a preview model) yet."
        const val NOT_ACCEPTED = "Google didn't accept Rflow's request: Rflow needs an update."
        const val QUOTA_DAY = "Gemini's daily quota for this key is used up: live translation works again tomorrow."
        const val QUOTA_NOW = "Gemini's quota for this key is used up for now: trying again shortly."
        const val QUOTA_STOP = "Gemini's quota for this key is used up: check its plan and billing in Google AI Studio."
        private val CREDIT = Regex("""prepay|credits? (are |is )?(depleted|exhausted|used up)|out of credits?|insufficient[_ ](balance|funds)|credit balance|payment required""")
    }
}
