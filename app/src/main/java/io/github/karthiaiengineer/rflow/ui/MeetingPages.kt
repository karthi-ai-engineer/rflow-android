package io.github.karthiaiengineer.rflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.karthiaiengineer.rflow.live.LiveUi
import io.github.karthiaiengineer.rflow.live.Mode
import io.github.karthiaiengineer.rflow.meeting.Meeting
import io.github.karthiaiengineer.rflow.meeting.MinutesState

/** Meeting mode: start one (the phone on the table), or open one for its minutes. While one runs: live captions. */
@Composable
fun ColumnScope.MeetingsPage(ui: LiveUi, prefs: Prefs, hasKey: Boolean, meetings: List<Meeting>, states: Map<String, MinutesState>,
                             actions: Actions) {
    val s = LocalSignal.current
    val mine = ui.spec?.mode == Mode.MEETING
    val running = ui.running && mine
    val busy = ui.running && !mine
    Title("Meeting mode")
    if (!running) {
        Card {
            Body("Lay the phone on the table. It records the room, translates live as people speak, and writes the minutes when the meeting ends: who said what, a summary, decisions and action items.")
            LanguagePicker("Translate into", prefs.target, onPick = actions.setTarget)
            Toggle("Speak the translation", "Use earphones, so the room and the recording don't hear it.", prefs.speak, onChange = actions.setSpeak)
        }
        if (!hasKey) Body("Add your Gemini key in Settings first.", s.warn)
        if (busy) Body("Another session is running: stop it first.", s.warn)
    }
    StartStop(running, "Start the meeting", onStart = actions.startMeeting, onStop = actions.stop, enabled = hasKey && !busy)
    if (running) {
        Body("Recording. The minutes are written when you stop.", s.text3, 13)
        Captions(ui, lane = 0, live = true)
        return
    }
    if (meetings.isNotEmpty()) Heading("Meetings")
    for (m in meetings) {
        val state = states[m.id]
        Card(modifier = Modifier.clickable { actions.openMeeting(m.id) }) {
            Text(m.title, color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Body("${m.started} · ${Math.round(m.seconds / 60)} min", s.text3, 13)
            when {
                state is MinutesState.Writing -> Body(state.step, s.iris, 13)
                state is MinutesState.Failed -> Body("The minutes weren't written: open it to try again.", s.err, 13)
                m.minutes == null -> Body("No minutes yet.", s.text3, 13)
            }
        }
    }
}

/** One meeting: its minutes (summary, decisions, action items, transcript), Share, Write again, Delete. */
@Composable
fun ColumnScope.MeetingPage(meeting: Meeting?, state: MinutesState?, live: List<String>, actions: Actions) {
    val s = LocalSignal.current
    if (meeting == null) { Body("This meeting is gone."); return }
    var confirm by remember { mutableStateOf(false) }
    val minutes = meeting.minutes
    Title(meeting.title)
    Body("${meeting.started} · ${Math.round(meeting.seconds / 60)} min · in person", s.text3, 13)
    when (state) {
        is MinutesState.Writing -> Card {
            Body(state.step, s.text)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = s.primary, trackColor = s.well)
            Body("Gemini listens to the whole recording: a few minutes for an hour's meeting.", s.text3, 13)
        }
        is MinutesState.Failed -> Card(highlight = true) {
            Body(state.message, s.err)
            Button(onClick = { actions.writeMinutes(meeting.id) }, colors = primary()) { Text("Write the minutes again") }
        }
        null -> if (minutes == null) Card {
            Body("The minutes haven't been written.")
            Button(onClick = { actions.writeMinutes(meeting.id) }, colors = primary()) { Text("Write the minutes") }
        }
    }
    if (minutes != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { actions.shareMinutes(meeting.id) }, colors = primary()) { Text("Share") }
            OutlinedButton(onClick = { actions.writeMinutes(meeting.id) }, enabled = state !is MinutesState.Writing) { Text("Write again", color = s.text2) }
        }
        if (minutes.speakers.isNotEmpty()) Body("People: ${minutes.speakers.joinToString(", ")}", s.text2, 14)
        Card { Heading("Summary"); Body(minutes.summary.ifBlank { "No summary." }, s.text) }
        Card {
            Heading("Decisions")
            if (minutes.decisions.isEmpty()) Body("None recorded.", s.text3)
            minutes.decisions.forEach { Body("• $it", s.text) }
        }
        Card {
            Heading("Action items")
            if (minutes.actions.isEmpty()) Body("None recorded.", s.text3)
            minutes.actions.forEach { a ->
                val who = listOf(a.owner, if (a.due.isNotEmpty()) "due ${a.due}" else "").filter { it.isNotEmpty() }.joinToString(", ")
                Row(verticalAlignment = Alignment.Top) {
                    Text("☐", color = s.iris); Spacer(Modifier.width(8.dp))
                    Body(a.task + if (who.isNotEmpty()) "  ($who)" else "", s.text)
                }
            }
        }
        Card {
            Heading("Transcript")
            if (minutes.transcript.isEmpty()) Body("Nobody spoke.", s.text3)
            minutes.transcript.forEach { (at, who, words) ->
                Text("[$at] $who", color = s.text3, fontSize = 12.sp)
                Body(words, s.text, 14)
            }
        }
    } else if (live.isNotEmpty()) {
        Card {
            Heading("Live translation")
            live.forEach { row ->
                val (src, tr) = row.split('\t').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                if (src.isNotEmpty()) Text(src, color = s.text3, fontSize = 13.sp)
                Body(tr, s.text, 15)
            }
        }
    }
    TextButton(onClick = { confirm = true }) { Text("Delete this meeting", color = s.err) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete the meeting?") },
        text = { Text("Its recording and minutes are deleted from this phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirm = false; actions.deleteMeeting(meeting.id) }) { Text("Delete", color = s.err) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep it") } },
    )
}
