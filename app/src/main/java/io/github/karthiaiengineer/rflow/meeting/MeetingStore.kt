package io.github.karthiaiengineer.rflow.meeting

import android.content.Context
import io.github.karthiaiengineer.rflow.ai.Minutes
import io.github.karthiaiengineer.rflow.ai.MinutesError
import io.github.karthiaiengineer.rflow.ai.MinutesResult
import io.github.karthiaiengineer.rflow.core.KeyVault
import io.github.karthiaiengineer.rflow.core.Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A meeting on the phone: its folder, when, how long, and its minutes once written. */
data class Meeting(val dir: File, val started: String, val seconds: Double, val minutes: MinutesResult?) {
    val id: String get() = dir.name
    val title: String get() = minutes?.title ?: "Meeting"
}

/** Where the minutes stand: being written (what's happening) or why they failed. */
sealed interface MinutesState {
    data class Writing(val step: String) : MinutesState
    data class Failed(val message: String) : MinutesState
}

/**
 * The meetings, each a folder in the app's own storage (never shared, never backed up): room.wav (the recording, never
 * deleted unless the user deletes the meeting), meeting.json, live.txt (the live translation as it was shown),
 * minutes.json and minutes.md.
 */
class MeetingStore(
    private val context: Context,
    private val http: OkHttpClient,
    private val vault: KeyVault,
    private val endpoints: () -> Pair<String, String>,  // (REST base, upload URL)
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, MinutesState>>(emptyMap())
    val states: StateFlow<Map<String, MinutesState>> = _states
    private val _version = MutableStateFlow(0)
    /** Changes when a meeting is added, finished, deleted or gets its minutes. */
    val version: StateFlow<Int> = _version

    fun list(): List<Meeting> = root(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
        ?.mapNotNull { load(it) }.orEmpty()

    fun get(id: String): Meeting? = File(root(context), id).takeIf { it.isDirectory }?.let(::load)

    private fun load(dir: File): Meeting? {
        val meta = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull() ?: return null
        val minutes = runCatching { MinutesResult.fromJson(JSONObject(File(dir, MINUTES_JSON).readText())) }.getOrNull()
        return Meeting(dir, meta.optString("started"), meta.optDouble("seconds", 0.0), minutes)
    }

    fun liveLines(id: String): List<String> = runCatching { File(File(root(context), id), LIVE).readLines() }.getOrDefault(emptyList())

    /** Gemini writes the transcript and the minutes; the recording stays whatever happens. */
    fun writeMinutes(dir: File) {
        val id = dir.name
        if (_states.value[id] is MinutesState.Writing) return
        _states.update { it + (id to MinutesState.Writing("Starting…")) }
        scope.launch {
            try {
                val meta = JSONObject(File(dir, META).readText())
                val (base, upload) = endpoints()
                val result = Minutes(http, vault.read().orEmpty(), base, upload).write(File(dir, ROOM), meta.optString("started")) { step ->
                    _states.update { it + (id to MinutesState.Writing(step)) }
                }
                File(dir, MINUTES_JSON).writeText(result.toJson().toString(2))
                File(dir, MINUTES_MD).writeText(result.markdown(meta.optString("started"), Math.round(meta.optDouble("seconds", 0.0) / 60).toInt()))
                _states.update { it - id }
            } catch (e: MinutesError) {
                _states.update { it + (id to MinutesState.Failed(e.message.orEmpty())) }
            } catch (e: Exception) {
                _states.update { it + (id to MinutesState.Failed("The minutes couldn't be written (${e.message}). ${Minutes.KEPT}")) }
            }
            _version.update { it + 1 }
        }
    }

    fun delete(id: String) {
        File(root(context), id).deleteRecursively()
        _states.update { it - id }
        _version.update { it + 1 }
    }

    fun markdown(id: String): String? = runCatching { File(File(root(context), id), MINUTES_MD).readText() }.getOrNull()

    companion object {
        const val ROOM = "room.wav"
        const val META = "meeting.json"
        const val LIVE = "live.txt"
        const val MINUTES_JSON = "minutes.json"
        const val MINUTES_MD = "minutes.md"

        fun root(context: Context) = File(context.filesDir, "meetings").apply { mkdirs() }

        /** A new meeting's folder, named by when it starts. */
        fun create(context: Context, target: String): File {
            val now = Date()
            val dir = File(root(context), SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)).apply { mkdirs() }
            val started = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.ENGLISH).format(now)
            File(dir, META).writeText(JSONObject().put("started", started).put("seconds", 0).put("target", target).put("in_person", true).toString(2))
            return dir
        }

        fun appendLine(dir: File, line: Line) = runCatching {
            File(dir, LIVE).appendText("${line.source}\t${line.translation}\n".replace("\r", ""))
        }

        fun finish(dir: File, seconds: Double) = runCatching {
            val meta = JSONObject(File(dir, META).readText()).put("seconds", seconds)
            File(dir, META).writeText(meta.toString(2))
        }
    }
}
