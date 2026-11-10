package io.github.karthiaiengineer.rflow.ai

import io.github.karthiaiengineer.rflow.core.Pcm
import io.github.karthiaiengineer.rflow.core.WavWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class Action(val task: String, val owner: String, val due: String)

/** A meeting's minutes, as Rflow for Windows writes them (`sst/meeting/minutes.py`). */
data class MinutesResult(
    val title: String,
    val summary: String,
    val decisions: List<String>,
    val actions: List<Action>,
    val transcript: List<Triple<String, String, String>>,  // (MM:SS, speaker, words)
    val speakers: List<String>,
    val language: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("summary", summary).put("decisions", JSONArray(decisions))
        .put("actions", JSONArray(actions.map { JSONObject().put("task", it.task).put("owner", it.owner).put("due", it.due) }))
        .put("transcript", JSONArray(transcript.map { JSONArray(listOf(it.first, it.second, it.third)) }))
        .put("speakers", JSONArray(speakers)).put("language", language)

    /** minutes.md: title, date, people; summary; decisions; action items as a checklist; the transcript. */
    fun markdown(started: String = "", minutes: Int = 0): String = buildString {
        appendLine("# $title").appendLine()
        val about = listOf(started, if (minutes > 0) "$minutes min" else "", "in person").filter { it.isNotEmpty() }
        appendLine(about.joinToString(" · ")).appendLine()
        if (speakers.isNotEmpty()) appendLine("People: ${speakers.joinToString(", ")}").appendLine()
        appendLine("## Summary").appendLine().appendLine(summary.ifBlank { "No summary." }).appendLine()
        appendLine("## Decisions").appendLine()
        if (decisions.isEmpty()) appendLine("None recorded.") else decisions.forEach { appendLine("- $it") }
        appendLine().appendLine("## Action items").appendLine()
        if (actions.isEmpty()) appendLine("None recorded.")
        actions.forEach { a ->
            val who = listOf(a.owner, if (a.due.isNotEmpty()) "due ${a.due}" else "").filter { it.isNotEmpty() }.joinToString(", ")
            appendLine("- [ ] ${a.task}" + if (who.isNotEmpty()) " ($who)" else "")
        }
        appendLine().appendLine("## Transcript").appendLine()
        if (transcript.isEmpty()) appendLine("Nobody spoke.")
        transcript.forEach { (at, who, words) -> appendLine("**[$at] $who:** $words  ") }
    }.trimEnd() + "\n"

    companion object {
        fun fromJson(json: JSONObject) = MinutesResult(
            title = json.optString("title"), summary = json.optString("summary"),
            decisions = json.optJSONArray("decisions").strings(),
            actions = json.optJSONArray("actions").objects().map { Action(it.optString("task"), it.optString("owner"), it.optString("due")) },
            transcript = (0 until (json.optJSONArray("transcript")?.length() ?: 0)).map {
                val t = json.getJSONArray("transcript").getJSONArray(it); Triple(t.optString(0), t.optString(1), t.optString(2))
            },
            speakers = json.optJSONArray("speakers").strings(), language = json.optString("language"),
        )
    }
}

class MinutesError(message: String) : Exception(message)

/**
 * After a meeting in person: the recording (one microphone, everyone in the room) → a transcript with speakers →
 * the minutes, by Gemini 3.6 Flash, in two steps as on the desktop. The audio goes through the Files API in 20-minute
 * stretches (each told the people so far and the last lines, so a voice keeps its label) and is deleted from Google as
 * soon as the answer is in. Words stay in the language they were said in; nothing the meeting didn't say. The
 * recording is never deleted, whatever fails.
 */
class Minutes(
    private val http: OkHttpClient,
    private val key: String,
    private val base: String = "https://generativelanguage.googleapis.com/v1beta",
    private val uploadUrl: String = "https://generativelanguage.googleapis.com/upload/v1beta/files",
    private val model: String = Models.MINUTES,
    private val chunkS: Double = CHUNK_S,
) {
    private val slow = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).build()

    /** [progress] says what's happening, in plain words. */
    fun write(wav: File, started: String, progress: (String) -> Unit = {}): MinutesResult {
        try {
            return run(wav, started, progress)
        } catch (e: MinutesError) {
            throw MinutesError("${e.message} $KEPT")
        } catch (e: IOException) {
            throw MinutesError("Can't reach Gemini (${e.message}). $KEPT")
        }
    }

    private fun run(wav: File, started: String, progress: (String) -> Unit): MinutesResult {
        if (key.isBlank()) throw MinutesError("The minutes need a Gemini key: add it in Settings.")
        WavWriter.repair(wav)
        val total = WavWriter.seconds(wav)
        if (total < 1) throw MinutesError("There is no recording of the meeting to write minutes from.")
        val done = Transcript()
        val spans = spans(total).toMutableList()
        var n = 0
        while (spans.isNotEmpty()) {
            val (start, end) = spans.removeAt(0)
            n++
            progress(if (total > chunkS) "Writing the transcript (${clock(start)}–${clock(end)})…" else "Writing the transcript…")
            try {
                span(wav, start, end, done)
            } catch (e: TooLong) {
                if (end - start < 2 * MIN_CHUNK_S) throw MinutesError("The meeting's transcript didn't fit in Gemini's answer.")
                val mid = (start + end) / 2
                spans.addAll(0, listOf(start to mid, mid to end))
            }
        }
        progress("Writing the minutes…")
        val data = minutes(done, started, total)
        val actions = data.optJSONArray("actions").objects()
            .map { Action(it.optString("task").squash(), it.optString("owner").squash(), it.optString("due").squash()) }
            .filter { it.task.isNotEmpty() }
        val spoken = done.languages.maxByOrNull { it.value }?.key.orEmpty()
        return MinutesResult(
            title = data.optString("title").squash().ifEmpty { "Meeting" },
            summary = data.optString("summary").trim(),
            decisions = data.optJSONArray("decisions").strings().map { it.squash() }.filter { it.isNotEmpty() },
            actions = actions,
            transcript = done.lines.map { (t, label, words) -> Triple(clock(t), done.who(label), words) },
            speakers = done.labels.map { done.who(it) },
            language = data.optString("language").trim().lowercase().ifEmpty { spoken },
        )
    }

    private class TooLong : Exception()

    private class Transcript {
        val lines = mutableListOf<Triple<Double, String, String>>()
        val labels = mutableListOf<String>()
        val names = mutableMapOf<String, String>()
        val languages = mutableMapOf<String, Double>()
        fun who(label: String) = names[label] ?: label
    }

    private fun span(wav: File, start: Double, end: Double, done: Transcript) {
        val clip = clip(wav, start, end) ?: return  // nobody spoke: nothing to send
        val file = upload(clip)
        try {
            val context = if (done.lines.isEmpty()) "" else buildString {
                append("\nThe recording so far had these people: ${done.labels.joinToString(", ") { l -> done.names[l]?.let { "$l ($it)" } ?: l }}.")
                append(" Use the same label for the same voice. The last lines before this stretch:\n")
                done.lines.takeLast(8).forEach { (t, l, w) -> append("[${clock(t)}] $l: $w\n") }
                append("This stretch starts at ${clock(start)}; its times are from its own start (00:00).")
            }
            val parts = JSONArray()
                .put(JSONObject().put("text", "Track 1: $ROOM_TRACK."))
                .put(JSONObject().put("fileData", JSONObject().put("mimeType", file.optString("mimeType", "audio/wav"))
                    .put("fileUri", file.getString("uri"))))
                .put(JSONObject().put("text", TRANSCRIPT_PROMPT.replace("{context}", context)))
            val data = generate(parts, TRANSCRIPT_SCHEMA, "minimal")
            addLines(done, data, start, end)
        } finally {
            delete(file.optString("name"))
        }
    }

    private fun addLines(done: Transcript, data: JSONObject, start: Double, end: Double) {
        val length = end - start
        val lines = data.optJSONArray("lines").objects().mapNotNull { item ->
            val text = item.optString("text").squash()
            if (text.isEmpty()) return@mapNotNull null
            val label = item.optString("speaker").squash().ifEmpty { "Speaker" }
            val at = (seconds(item.optString("time")) ?: 0.0).coerceIn(0.0, length)
            Triple(start + at, label, text)
        }.sortedBy { it.first }
        for (line in lines) {
            done.lines += line
            if (line.second !in done.labels) done.labels += line.second
        }
        for (item in data.optJSONArray("names").objects()) {
            val label = item.optString("label").trim()
            val name = item.optString("name").squash()
            val taken = done.names.filterKeys { it != label }.values.map { it.lowercase() }
            if (label.isNotEmpty() && name.isNotEmpty() && label !in done.names && name.lowercase() !in taken) done.names[label] = name
        }
        val language = data.optString("language").trim().lowercase()
        if (lines.isNotEmpty() && language.isNotEmpty()) done.languages[language] = (done.languages[language] ?: 0.0) + length
    }

    private fun minutes(done: Transcript, started: String, total: Double): JSONObject {
        val lines = done.lines.joinToString("\n") { (t, l, w) -> "[${clock(t)}] ${done.who(l)}: $w" }.ifEmpty { "(nobody spoke)" }
        val meeting = JSONObject().put("started", started).put("length", "${Math.round(total / 60)} min")
            .put("people", JSONArray(done.labels.map { done.who(it) }))
        val prompt = MINUTES_PROMPT.replace("{meeting}", meeting.toString()).replace("{transcript}", lines)
        return generate(JSONArray().put(JSONObject().put("text", prompt)), MINUTES_SCHEMA, "low")
    }

    private fun generate(parts: JSONArray, schema: JSONObject, thinking: String): JSONObject {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", schema)
                .put("maxOutputTokens", 65_536).put("thinkingConfig", JSONObject().put("thinkingLevel", thinking)))
        val answer = post("$base/models/$model:generateContent", body)
        val candidates = answer.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val reason = answer.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw MinutesError(if (reason.isNotEmpty()) "Gemini blocked the request ($reason)." else "Gemini gave no answer.")
        }
        val candidate = candidates.getJSONObject(0)
        when (val finish = candidate.optString("finishReason", "STOP")) {
            "MAX_TOKENS" -> throw TooLong()
            "STOP", "FINISH_REASON_UNSPECIFIED", "" -> Unit
            else -> throw MinutesError("Gemini stopped before the end ($finish).")
        }
        val text = candidate.optJSONObject("content")?.optJSONArray("parts").objects()
            .filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
        return runCatching { JSONObject(text) }.getOrElse { throw MinutesError("Gemini's answer couldn't be read.") }
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url(url).header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON)).build()
        slow.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw refused(r.code, text)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /** The Files API, a resumable upload in one piece; the upload address carries its own authorisation. */
    private fun upload(wav: ByteArray): JSONObject {
        val start = Request.Builder().url(uploadUrl).header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", wav.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", "audio/wav")
            .post(JSONObject().put("file", JSONObject().put("display_name", "rflow-meeting")).toString().toRequestBody(JSON)).build()
        val address = slow.newCall(start).execute().use { r ->
            if (!r.isSuccessful) throw refused(r.code, r.body?.string().orEmpty())
            r.header("x-goog-upload-url") ?: throw MinutesError("Gemini's Files API gave no upload address.")
        }
        val send = Request.Builder().url(address).header("X-Goog-Upload-Command", "upload, finalize")
            .header("X-Goog-Upload-Offset", "0").post(wav.toRequestBody("audio/wav".toMediaType())).build()
        var file = slow.newCall(send).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw refused(r.code, text)
            runCatching { JSONObject(text).getJSONObject("file") }.getOrNull()
        }
        if (file == null || file.optString("uri").isEmpty()) throw MinutesError("Gemini's Files API kept no file.")
        val deadline = System.currentTimeMillis() + 120_000
        while (file!!.optString("state") == "PROCESSING" && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val r = slow.newCall(Request.Builder().url("$base/${file.optString("name")}").header("x-goog-api-key", key).build()).execute()
            r.use { if (it.isSuccessful) file = JSONObject(it.body!!.string()).let { f -> merge(file!!, f) } }
        }
        if (file!!.optString("state") == "FAILED") throw MinutesError("Gemini couldn't read the uploaded recording.")
        return file!!
    }

    /** Off Google at once (it would keep the file 48 hours). Best effort. */
    private fun delete(name: String) {
        if (name.isEmpty()) return
        runCatching {
            slow.newCall(Request.Builder().url("$base/$name").header("x-goog-api-key", key).delete().build()).execute().close()
        }
    }

    private fun refused(status: Int, body: String): MinutesError {
        val said = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        val text = (said ?: body).lowercase()
        return MinutesError(when {
            status in setOf(401, 403) || "api key" in text -> "Gemini didn't accept the key: check it in Settings."
            status == 429 || "quota" in text -> "Gemini's quota for this key is used up for now: try again later."
            status in 500..599 -> "Gemini is busy or failing right now ($status): try again in a while."
            else -> "Gemini refused the request ($status${said?.let { ": $it" } ?: ""})."
        })
    }

    /** The WAV's [start, end) as its own WAV; null when nobody spoke in it (no sample above SILENT_PEAK). */
    private fun clip(wav: File, start: Double, end: Double): ByteArray? {
        val from = 44 + (start * Pcm.RATE).toLong() * 2
        val to = minOf(wav.length(), 44 + (end * Pcm.RATE).toLong() * 2)
        if (to <= from) return null
        val pcm = ByteArray((to - from).toInt())
        RandomAccessFile(wav, "r").use { it.seek(from); it.readFully(pcm) }
        if (Pcm.silent(pcm, SILENT_PEAK)) return null
        return WavWriter.header(Pcm.RATE, pcm.size.toLong()) + pcm
    }

    private fun spans(total: Double): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        var start = 0.0
        while (start < total) {
            var end = minOf(total, start + chunkS)
            if (total - end < minOf(60.0, chunkS / 4)) end = total
            out += start to end
            start = end
        }
        return out
    }

    companion object {
        const val CHUNK_S = 20 * 60.0
        const val MIN_CHUNK_S = 5 * 60.0
        const val SILENT_PEAK = 64
        const val KEPT = "The recording is kept on the phone: the minutes can be written again later."
        private val JSON = "application/json".toMediaType()
        private const val ROOM_TRACK = "the phone's microphone in the meeting room: everyone at the meeting, the user of this phone among them"

        fun clock(seconds: Double): String {
            val s = seconds.toInt().coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        }

        fun seconds(text: String): Double? {
            val parts = text.trim().split(":").map { it.trim().toDoubleOrNull() ?: return null }
            return when (parts.size) { 2 -> parts[0] * 60 + parts[1]; 3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]; else -> null }
        }

        private fun merge(a: JSONObject, b: JSONObject): JSONObject {
            val out = JSONObject(a.toString())
            b.keys().forEach { out.put(it, b.get(it)) }
            return out
        }

        val TRANSCRIPT_PROMPT = """You are writing the transcript of a meeting in person, in a room, from its recording.
Track 1 is its only track.

Rules:
1. Write down what was said, in the language it was said in: Japanese in Japanese script, Tamil in Tamil script, English in English, any other language as spoken. A sentence that mixes languages stays mixed. Never translate, summarise or correct what was said; leave out only fillers (um, uh, えーと, あの) and false starts.
2. One line per turn: when the speaker changes, a new line. A long turn may be split at a sentence end, about every 30 seconds.
3. time: when the line starts, as MM:SS from the start of this track (00:00).
4. speaker: everyone is on the one microphone track, the user of this phone among them; never use "You": the user's voice can't be told from the others'. Tell people apart by their voices and label them "Speaker 1", "Speaker 2"... in the order they first speak, and keep the same label for the same voice all the way through. Always use these labels in the lines, even when you learn a name.
5. names: when the meeting makes a person's name clear (they introduce themselves, or are called by name and answer), give their label and the name as it was said. Only names that were said; never guess one.
6. Voices far from the microphone are quieter: still write what they said; only words you can't make out are [inaudible].
7. Only what is in the recording. Never guess at words you can't hear: write [inaudible] for a part that can't be made out. Silence, music and noise give no line; if nobody speaks, lines is empty.
8. language: the main language spoken, as a two-letter code (ja, en, ta...).
{context}"""

        val MINUTES_PROMPT = """You are writing the minutes of a meeting in person, in a room, from its transcript.

Rules:
1. Only what the transcript shows. Never invent a decision, a task, a name, a date or a number.
2. title: a short title for the meeting, under 10 words, from what it was about.
3. summary: what was discussed, in a few short paragraphs (a few sentences for a short meeting).
4. decisions: what was agreed or decided, each in one sentence. Empty when nothing was decided.
5. actions: the tasks someone agreed to do or was asked to do. owner: who will do it, as the transcript names them, only when the meeting said who; otherwise "". due: when it is due, as it was said ("Friday", "by 3 November"), only when the meeting said when; otherwise "". Never guess an owner or a date.
6. Write the minutes in the language most of the meeting was spoken in, in its own script; keep names, terms and numbers exactly as in the transcript. language: that language's two-letter code.
7. There are no screenshots: use the transcript alone.
8. If the transcript is empty or too short to say anything, say so in the summary and leave the lists empty.

The meeting: {meeting}

Transcript ([MM:SS] speaker: words):
{transcript}"""

        private fun str(description: String? = null) = JSONObject().put("type", "string").apply { description?.let { put("description", it) } }

        val TRANSCRIPT_SCHEMA: JSONObject get() = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("language", str("The main language spoken, a two-letter code."))
            .put("lines", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("time", str("MM:SS from the start of the track."))
                    .put("speaker", str("\"Speaker 1\", \"Speaker 2\"...")).put("text", str("The words said, in the language and script said.")))
                .put("required", JSONArray(listOf("time", "speaker", "text")))))
            .put("names", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("label", str("\"Speaker 1\"...")).put("name", str("The name as it was said in the meeting.")))
                .put("required", JSONArray(listOf("label", "name"))))))
            .put("required", JSONArray(listOf("language", "lines", "names")))

        val MINUTES_SCHEMA: JSONObject get() = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("title", str()).put("summary", str())
            .put("decisions", JSONObject().put("type", "array").put("items", str()))
            .put("actions", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")
                .put("properties", JSONObject().put("task", str())
                    .put("owner", str("Who will do it, only if said; else empty.")).put("due", str("When it is due, as said, only if said; else empty.")))
                .put("required", JSONArray(listOf("task", "owner", "due")))))
            .put("language", str("The minutes' language, a two-letter code.")))
            .put("required", JSONArray(listOf("title", "summary", "decisions", "actions", "language")))
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun String.squash() = split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
