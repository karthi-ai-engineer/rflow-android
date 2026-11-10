package io.github.karthiaiengineer.rflow.live

import io.github.karthiaiengineer.rflow.core.Line
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What a session is for. */
enum class Mode { LIVE, MEETING, CONVERSATION }

/** What it listens to: the room (the microphone) or what the phone plays. */
enum class Source { ROOM, PHONE }

/** What the user chose when starting. [theirs]: Conversation's other language. */
data class SessionSpec(
    val mode: Mode,
    val source: Source = Source.ROOM,
    val target: String = "en",
    val theirs: String = "ja",
    val speak: Boolean = false,
    val saver: Boolean = true,
)

/** A caption line on screen. [lane]: Conversation's side (0: translated for me, 1: for the person across the table). */
data class Caption(val line: Line, val lane: Int = 0)

data class LiveUi(
    val running: Boolean = false,
    val spec: SessionSpec? = null,
    val status: String = "",
    val problem: String = "",
    val lines: List<Caption> = emptyList(),
    /** The line being spoken, per lane: (heard, translated). */
    val current: Map<Int, Pair<String, String>> = emptyMap(),
    val startedAt: Long = 0,
    val speaking: Boolean = false,
    /** Meeting mode: the folder the recording goes into. */
    val meeting: String = "",
    val sentSeconds: Double = 0.0,
    val savedSeconds: Double = 0.0,
)

/** The running session's state, shared by the service (writes) and the screens and the floating bar (read). */
object LiveHub {
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui

    fun reset(spec: SessionSpec, meeting: String) {
        _ui.value = LiveUi(running = true, spec = spec, status = "Starting…", startedAt = System.currentTimeMillis(), meeting = meeting)
    }

    fun edit(change: (LiveUi) -> LiveUi) = _ui.update(change)

    fun heard(lane: Int, text: String) = _ui.update { it.copy(current = it.current + (lane to (text to (it.current[lane]?.second ?: "")))) }

    fun translated(lane: Int, text: String) = _ui.update { it.copy(current = it.current + (lane to ((it.current[lane]?.first ?: "") to text))) }

    fun done(lane: Int, line: Line) = _ui.update {
        it.copy(lines = (it.lines + Caption(line, lane)).takeLast(MAX_LINES), current = it.current - lane)
    }

    fun clearCurrent(lane: Int) = _ui.update { it.copy(current = it.current - lane) }

    fun stopped() = _ui.update { it.copy(running = false, current = emptyMap(), speaking = false, status = "Stopped") }

    private const val MAX_LINES = 500
}
