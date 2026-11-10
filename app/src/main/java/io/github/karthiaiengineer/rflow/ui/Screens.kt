package io.github.karthiaiengineer.rflow.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.karthiaiengineer.rflow.KeyState
import io.github.karthiaiengineer.rflow.UpdateState
import io.github.karthiaiengineer.rflow.ai.KeyCheck
import io.github.karthiaiengineer.rflow.core.Languages
import io.github.karthiaiengineer.rflow.core.ThemeChoice
import io.github.karthiaiengineer.rflow.live.LiveUi
import io.github.karthiaiengineer.rflow.live.Mode
import io.github.karthiaiengineer.rflow.live.Source
import io.github.karthiaiengineer.rflow.meeting.Meeting
import io.github.karthiaiengineer.rflow.meeting.MinutesState

enum class Page { HOME, LIVE, MEETINGS, MEETING, CONVERSATION, SETTINGS }

/** What the screens need from the model and the activity, so they can be previewed and tested with plain values. */
class Actions(
    val saveKey: (String) -> Unit = {},
    val testKey: () -> Unit = {},
    val removeKey: () -> Unit = {},
    val setTheme: (ThemeChoice) -> Unit = {},
    val checkForUpdate: () -> Unit = {},
    val installUpdate: () -> Unit = {},
    val allowInstalls: () -> Unit = {},
    val setTarget: (String) -> Unit = {},
    val setMine: (String) -> Unit = {},
    val setTheirs: (String) -> Unit = {},
    val setSpeak: (Boolean) -> Unit = {},
    val setSaver: (Boolean) -> Unit = {},
    val startLive: (Source) -> Unit = {},
    val startMeeting: () -> Unit = {},
    val startConversation: () -> Unit = {},
    val clearConversation: () -> Unit = {},
    val stop: () -> Unit = {},
    val openMeeting: (String) -> Unit = {},
    val writeMinutes: (String) -> Unit = {},
    val shareMinutes: (String) -> Unit = {},
    val deleteMeeting: (String) -> Unit = {},
    val setTestServer: (String) -> Unit = {},
)

/** Everything the screens show. */
data class Screen(
    val page: Page,
    val version: String,
    val key: KeyState,
    val theme: ThemeChoice,
    val update: UpdateState,
    val live: LiveUi,
    val prefs: Prefs,
    val meetings: List<Meeting> = emptyList(),
    val meeting: Meeting? = null,
    val meetingLive: List<String> = emptyList(),
    val minutes: Map<String, MinutesState> = emptyMap(),
    val debug: Boolean = false,
    val testServer: String = "",
)

@Composable
fun RflowScreen(screen: Screen, onPage: (Page) -> Unit, actions: Actions) {
    val s = LocalSignal.current
    val page = screen.page
    val live = screen.live
    Column(Modifier.fillMaxSize().background(s.base).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != Page.HOME) {
                TextButton(onClick = { onPage(if (page == Page.MEETING) Page.MEETINGS else Page.HOME) }) { Text("‹ Back", color = s.text2) }
            } else {
                Box(Modifier.size(28.dp).background(Brush.linearGradient(listOf(s.iris, s.violet)), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) { Text("R", color = s.onPrimary, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text("Rflow", color = s.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (page == Page.HOME) TextButton(onClick = { onPage(Page.SETTINGS) }) { Text("Settings", color = s.text2) }
        }
        if (page == Page.CONVERSATION) {
            ConversationPage(live, screen.prefs, screen.key.saved, actions) { onPage(Page.HOME) }
            return@Column
        }
        // Pages with live captions fill the screen (the captions scroll themselves); the others scroll.
        val captions = page == Page.LIVE || (page == Page.MEETINGS && live.running && live.spec?.mode == Mode.MEETING)
        val body = Modifier.fillMaxSize().padding(horizontal = 20.dp).let { if (captions) it else it.verticalScroll(rememberScrollState()) }
        Column(body, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (page) {
                Page.HOME -> Home(screen, actions, onPage)
                Page.LIVE -> LivePage(live, screen.prefs, screen.key.saved, actions)
                Page.MEETINGS -> MeetingsPage(live, screen.prefs, screen.key.saved, screen.meetings, screen.minutes, actions)
                Page.MEETING -> MeetingPage(screen.meeting, screen.meeting?.let { screen.minutes[it.id] }, screen.meetingLive, actions)
                Page.SETTINGS -> SettingsPage(screen, actions)
                Page.CONVERSATION -> Unit
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ColumnScope.Home(screen: Screen, actions: Actions, onPage: (Page) -> Unit) {
    val s = LocalSignal.current
    UpdateBanner(screen.update, actions, onHome = true)
    Text("Work in any language.", color = s.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    Text("Understand the people around you as they speak, and get your meetings written up.", color = s.text2, fontSize = 16.sp)
    if (!screen.key.saved) {
        Card(highlight = true) {
            Heading("Add your Gemini key")
            Body("Rflow uses your own Google Gemini key, as on Windows. It stays on this phone.")
            Button(onClick = { onPage(Page.SETTINGS) }, colors = primary()) { Text("Add the key") }
        }
    }
    val running = screen.live.running
    val mode = screen.live.spec?.mode
    Feature("Live translation", "The people around you, or a video on the phone, read in ${Languages.name(screen.prefs.target)} as it's said.",
        s.iris, if (running && mode == Mode.LIVE) "Live" else "") { onPage(Page.LIVE) }
    Feature("Meeting mode", "Lay the phone on the table: it translates live, records, and writes the minutes.",
        s.violet, if (running && mode == Mode.MEETING) "Recording" else "") { onPage(Page.MEETINGS) }
    Feature("Conversation", "Two people, two languages, one phone between you.",
        s.ok, if (running && mode == Mode.CONVERSATION) "Live" else "") { onPage(Page.CONVERSATION) }
}

@Composable
private fun Feature(title: String, text: String, lamp: Color, tag: String, onClick: () -> Unit) {
    val s = LocalSignal.current
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lamp(lamp)
            Spacer(Modifier.width(10.dp))
            Text(title, color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (tag.isNotEmpty()) Text(tag.uppercase(), color = s.live, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, s.live, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
            else Text("›", color = s.text3, fontSize = 22.sp)
        }
        Text(text, color = s.text2)
    }
}

@Composable
private fun ColumnScope.SettingsPage(screen: Screen, actions: Actions) {
    val s = LocalSignal.current
    val context = LocalContext.current
    val key = screen.key
    Title("Settings")

    Card {
        Heading("Google Gemini key")
        var typed by rememberSaveable { mutableStateOf("") }
        if (key.saved) {
            Body("Saved on this phone, sealed by Android's Keystore.")
        } else {
            Body("Paste the key from Google AI Studio. It never leaves this phone except to Gemini.")
            OutlinedTextField(
                value = typed, onValueChange = { typed = it }, singleLine = true, label = { Text("Key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            key.testing -> Body("Testing…")
            key.result == KeyCheck.Works -> Text("● The key works", color = s.ok)
            key.result == KeyCheck.Rejected -> Text("● Gemini doesn't accept this key", color = s.err)
            key.result is KeyCheck.Failed -> Text("● ${key.result.reason}", color = s.warn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (key.saved) {
                Button(onClick = actions.testKey, enabled = !key.testing, colors = primary()) { Text("Test key") }
                OutlinedButton(onClick = actions.removeKey) { Text("Remove", color = s.text2) }
            } else {
                Button(onClick = { actions.saveKey(typed); typed = "" }, enabled = typed.isNotBlank(), colors = primary()) { Text("Save") }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }) { Text("Get a key", color = s.iris) }
            }
        }
    }

    Card {
        Heading("Look")
        Row(Modifier.background(s.well, RoundedCornerShape(12.dp)).padding(4.dp)) {
            for ((choice, label) in listOf(ThemeChoice.SYSTEM to "Phone", ThemeChoice.LIGHT to "Light", ThemeChoice.DARK to "Dark")) {
                val on = choice == screen.theme
                TextButton(
                    onClick = { actions.setTheme(choice) },
                    modifier = Modifier.weight(1f).background(if (on) s.cardA else Color.Transparent, RoundedCornerShape(9.dp)),
                ) { Text(label, color = if (on) s.text else s.text3) }
            }
        }
    }

    Card {
        Heading("Updates")
        Body("Rflow ${screen.version}. It checks GitHub for a new version every time it opens.")
        UpdateBanner(screen.update, actions, onHome = false)
        OutlinedButton(onClick = actions.checkForUpdate, enabled = screen.update !is UpdateState.Checking &&
            screen.update !is UpdateState.Downloading) { Text("Check for updates", color = s.text) }
    }

    Card {
        Heading("Privacy")
        Body("Your voice goes to Google Gemini only while live translation or a meeting runs, and the recording to write the minutes. Meetings stay on this phone; nothing is backed up.", s.text2, 14)
    }

    if (screen.debug) Card {
        Heading("Test server (debug build)")
        var server by rememberSaveable { mutableStateOf(screen.testServer) }
        Body("A server on the laptop standing in for Gemini, e.g. http://10.0.2.2:8765. Blank: Google.", s.text3, 13)
        OutlinedTextField(value = server, onValueChange = { server = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { actions.setTestServer(server) }, colors = primary()) { Text("Use it") }
    }
}

/** The update, wherever it shows: offered, downloading, waiting for Android's permission, or why it failed. */
@Composable
private fun UpdateBanner(update: UpdateState, actions: Actions, onHome: Boolean) {
    val s = LocalSignal.current
    when (update) {
        is UpdateState.Available -> Card(highlight = true) {
            Heading("Rflow ${update.release.version} is out")
            if (update.release.notes.isNotBlank()) Body(update.release.notes.take(300), s.text2, 14)
            Button(onClick = actions.installUpdate, colors = primary()) { Text("Update to ${update.release.version}") }
        }
        is UpdateState.Downloading -> Card(highlight = true) {
            Body("Downloading Rflow ${update.release.version}…", s.text)
            LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth(), color = s.primary, trackColor = s.well)
        }
        is UpdateState.NeedsPermission -> Card(highlight = true) {
            Heading("Allow Rflow to install its updates")
            Body("Android asks once. Turn on \"Allow from this source\", then come back.", s.text2, 14)
            Button(onClick = actions.allowInstalls, colors = primary()) { Text("Open the setting") }
        }
        is UpdateState.Installing -> Card(highlight = true) { Body("Tap Update in Android's prompt to finish.", s.text) }
        is UpdateState.Failed -> if (!onHome) Body(update.message, s.err, 14)
        UpdateState.UpToDate -> if (!onHome) Text("● Up to date", color = s.ok, fontSize = 14.sp)
        UpdateState.Checking -> if (!onHome) Body("Checking…", s.text2, 14)
        UpdateState.Idle -> Unit
    }
}
