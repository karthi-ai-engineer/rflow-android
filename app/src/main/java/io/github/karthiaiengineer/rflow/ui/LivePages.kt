package io.github.karthiaiengineer.rflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.karthiaiengineer.rflow.live.Caption
import io.github.karthiaiengineer.rflow.live.LiveUi
import io.github.karthiaiengineer.rflow.live.Mode
import io.github.karthiaiengineer.rflow.live.Source
import kotlinx.coroutines.delay

/** The live translation choices, kept between sessions. */
data class Prefs(val target: String, val mine: String, val theirs: String, val speak: Boolean, val saver: Boolean)

/** Live translation: the room, or what the phone plays; captions as people speak. */
@Composable
fun ColumnScope.LivePage(ui: LiveUi, prefs: Prefs, hasKey: Boolean, actions: Actions) {
    val s = LocalSignal.current
    val mine = ui.spec?.mode == Mode.LIVE
    val running = ui.running && mine
    val busy = ui.running && !mine
    var source by remember { androidx.compose.runtime.mutableStateOf(Source.ROOM) }
    Title("Live translation")
    if (!running) {
        Card {
            Row(Modifier.fillMaxWidth().background(s.well, RoundedCornerShape(12.dp)).padding(4.dp)) {
                for ((choice, label) in listOf(Source.ROOM to "The room", Source.PHONE to "What the phone plays")) {
                    val on = choice == source
                    TextButton(onClick = { source = choice },
                        modifier = Modifier.weight(1f).background(if (on) s.cardA else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(9.dp)),
                    ) { Text(label, color = if (on) s.text else s.text3, fontSize = 14.sp) }
                }
            }
            Body(if (source == Source.ROOM) "The microphone hears the people around you; any language they speak is translated."
            else "Videos, podcasts and lectures playing on this phone, captioned in a bar over the app. Android asks to allow it each time.")
            LanguagePicker("Translate into", prefs.target, onPick = actions.setTarget)
            Toggle("Speak the translation", "Heard in Gemini's voice. Earphones keep the room quiet.", prefs.speak, onChange = actions.setSpeak)
            Toggle("Cost saver", "Only speech is sent; stops after 15 minutes without words.", prefs.saver, onChange = actions.setSaver)
        }
    }
    if (!hasKey) Body("Add your Gemini key in Settings first.", s.warn)
    if (busy) Body("Another session is running: stop it first.", s.warn)
    StartStop(running, "Start", onStart = { actions.startLive(source) }, onStop = actions.stop, enabled = hasKey && !busy)
    if (mine || ui.lines.isNotEmpty() && ui.spec?.mode == Mode.LIVE) Captions(ui, lane = 0, live = running)
}

/** The status line, the problem if any, and the captions: finished lines and the one being said. */
@Composable
fun ColumnScope.Captions(ui: LiveUi, lane: Int, live: Boolean, compact: Boolean = false) {
    val s = LocalSignal.current
    val clip = LocalClipboardManager.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live) { while (live) { now = System.currentTimeMillis(); delay(1000) } }
    if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
        if (live) { Lamp(s.live); Spacer(Modifier.width(8.dp)) }
        Text(if (live) "${clock(now - ui.startedAt)} · ${ui.status}" else ui.status, color = s.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (ui.speaking) Text("speaking", color = s.violet, fontSize = 12.sp)
        val lines = ui.lines.filter { it.lane == lane }
        if (lines.isNotEmpty()) TextButton(onClick = {
            clip.setText(AnnotatedString(lines.joinToString("\n\n") { "${it.line.source}\n${it.line.translation}" }))
        }) { Text("Copy", color = s.iris, fontSize = 13.sp) }
    }
    if (ui.problem.isNotEmpty()) Body(ui.problem, s.err, 14)
    CaptionList(ui.lines.filter { it.lane == lane }, ui.current[lane], compact)
}

@Composable
private fun ColumnScope.CaptionList(lines: List<Caption>, current: Pair<String, String>?, compact: Boolean) {
    val s = LocalSignal.current
    val state = rememberLazyListState()
    val count = lines.size + if (current != null) 1 else 0
    LaunchedEffect(count, current?.second?.length) { if (count > 0) state.animateScrollToItem(count - 1) }
    LazyColumn(
        state = state, verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().weight(1f, fill = true).background(s.well, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        if (count == 0) item { Body("Captions show here as people speak.", s.text3, 14) }
        items(lines) { c -> CaptionItem(c.line.source, c.line.translation, done = true, compact) }
        if (current != null) item { CaptionItem(current.first, current.second, done = false, compact) }
    }
}

@Composable
private fun CaptionItem(source: String, translation: String, done: Boolean, compact: Boolean) {
    val s = LocalSignal.current
    Column {
        if (source.isNotEmpty() && !compact) Text(source, color = s.text3, fontSize = 13.sp)
        if (translation.isNotEmpty()) Text(translation + if (done) "" else " ▍", color = if (done) s.text else s.iris,
            fontSize = if (compact) 20.sp else 17.sp, fontWeight = if (done) FontWeight.Normal else FontWeight.Medium, lineHeight = 24.sp)
        else if (!done) Text("…", color = s.text3)
    }
}

/**
 * Conversation: two people, two languages, one phone on the table. The top half is upside down for the person across;
 * each half shows what the other said, in its reader's language.
 */
@Composable
fun ConversationPage(ui: LiveUi, prefs: Prefs, hasKey: Boolean, actions: Actions, back: () -> Unit) {
    val s = LocalSignal.current
    val mine = ui.spec?.mode == Mode.CONVERSATION
    val running = ui.running && mine
    val busy = ui.running && !mine
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!running && !(mine && ui.lines.isNotEmpty())) {
            Title("Conversation")
            Card {
                Body("Lay the phone between you. Each of you reads the other in your own language, and hears it too with Speak the translation.")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LanguagePicker("You speak", prefs.mine, onPick = actions.setMine)
                    LanguagePicker("They speak", prefs.theirs, onPick = actions.setTheirs)
                }
                Toggle("Speak the translation", "Each translation said aloud in Gemini's voice.", prefs.speak, onChange = actions.setSpeak)
            }
            if (!hasKey) Body("Add your Gemini key in Settings first.", s.warn)
            if (busy) Body("Another session is running: stop it first.", s.warn)
            StartStop(false, "Start the conversation", onStart = actions.startConversation, onStop = actions.stop, enabled = hasKey && !busy && prefs.mine != prefs.theirs)
            Spacer(Modifier.weight(1f))
            return@Column
        }
        // Theirs, upside down: lane 1 is translated into their language.
        Box(Modifier.weight(1f).fillMaxWidth().rotate(180f)) {
            Column(Modifier.fillMaxSize()) { Captions(ui, lane = 1, live = running, compact = true) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${io.github.karthiaiengineer.rflow.core.Languages.name(prefs.mine)} ⇄ ${io.github.karthiaiengineer.rflow.core.Languages.name(prefs.theirs)}",
                color = s.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (running) OutlinedButton(onClick = actions.stop) { Text("Stop", color = s.live) }
            else OutlinedButton(onClick = { actions.clearConversation(); back() }) { Text("Done", color = s.text) }
        }
        if (ui.problem.isNotEmpty()) Body(ui.problem, s.err, 14)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) { Captions(ui, lane = 0, live = running, compact = true) }
        }
        Spacer(Modifier.height(8.dp))
    }
}
