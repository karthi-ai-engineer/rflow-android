package io.github.karthiaiengineer.rflow.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.karthiaiengineer.rflow.core.ThemeChoice

enum class Page { HOME, SETTINGS }

/** What the screens need from the model, so they can be previewed and tested with plain values. */
class Actions(
    val saveKey: (String) -> Unit = {},
    val testKey: () -> Unit = {},
    val removeKey: () -> Unit = {},
    val setTheme: (ThemeChoice) -> Unit = {},
    val checkForUpdate: () -> Unit = {},
    val installUpdate: () -> Unit = {},
    val allowInstalls: () -> Unit = {},
)

@Composable
fun RflowScreen(
    page: Page, onPage: (Page) -> Unit, version: String, key: KeyState, theme: ThemeChoice, update: UpdateState,
    actions: Actions,
) {
    val s = LocalSignal.current
    Column(Modifier.fillMaxSize().background(s.base).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page == Page.SETTINGS) {
                TextButton(onClick = { onPage(Page.HOME) }) { Text("‹ Back", color = s.text2) }
            } else {
                Box(Modifier.size(28.dp).background(Brush.linearGradient(listOf(s.iris, s.violet)), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) { Text("R", color = s.onPrimary, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text("Rflow", color = s.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (page == Page.HOME) TextButton(onClick = { onPage(Page.SETTINGS) }) { Text("Settings", color = s.text2) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (page) {
                Page.HOME -> Home(key, update, actions) { onPage(Page.SETTINGS) }
                Page.SETTINGS -> SettingsPage(version, key, theme, update, actions)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Home(key: KeyState, update: UpdateState, actions: Actions, openSettings: () -> Unit) {
    val s = LocalSignal.current
    UpdateBanner(update, actions, onHome = true)
    Text("Work in any language.", color = s.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    Text("Understand the people around you as they speak, and get your meetings written up.", color = s.text2, fontSize = 16.sp)
    if (!key.saved) {
        Card {
            Text("Add your Gemini key", color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("Rflow uses your own Google Gemini key, as on Windows. It stays on this phone.", color = s.text2)
            Button(onClick = openSettings, colors = primary()) { Text("Add the key") }
        }
    }
    Feature("Live translation", "Japanese spoken in the room, read in English as it's said.", s.iris, "Coming next")
    Feature("Meeting mode", "Lay the phone on the table: it translates live, records, and writes the minutes.", s.violet, "Soon")
}

@Composable
private fun Feature(title: String, text: String, lamp: Color, tag: String) {
    val s = LocalSignal.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(lamp, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(title, color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(tag.uppercase(), color = s.text3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, s.cardB, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Text(text, color = s.text2)
    }
}

@Composable
private fun SettingsPage(version: String, key: KeyState, theme: ThemeChoice, update: UpdateState, actions: Actions) {
    val s = LocalSignal.current
    val context = LocalContext.current
    Text("Settings", color = s.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)

    Card {
        Text("Google Gemini key", color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        var typed by rememberSaveable { mutableStateOf("") }
        if (key.saved) {
            Text("Saved on this phone, sealed by Android's Keystore.", color = s.text2)
        } else {
            Text("Paste the key from Google AI Studio. It never leaves this phone except to Gemini.", color = s.text2)
            OutlinedTextField(
                value = typed, onValueChange = { typed = it }, singleLine = true, label = { Text("Key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            key.testing -> Text("Testing…", color = s.text2)
            key.result == KeyCheck.Works -> Text("● The key works", color = s.ok)
            key.result == KeyCheck.Rejected -> Text("● Gemini doesn't accept this key", color = s.err)
            key.result is KeyCheck.Failed -> Text("● ${key.result.reason}", color = s.warn)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (key.saved) {
                Button(onClick = actions.testKey, enabled = !key.testing, colors = primary()) { Text("Test key") }
                OutlinedButton(onClick = actions.removeKey) { Text("Remove", color = s.text2) }
            } else {
                Button(onClick = { actions.saveKey(typed); typed = "" }, enabled = typed.isNotBlank(), colors = primary()) {
                    Text("Save")
                }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }) { Text("Get a key", color = s.iris) }
            }
        }
    }

    Card {
        Text("Look", color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Row(Modifier.background(s.well, RoundedCornerShape(12.dp)).padding(4.dp)) {
            for ((choice, label) in listOf(ThemeChoice.SYSTEM to "Phone", ThemeChoice.LIGHT to "Light", ThemeChoice.DARK to "Dark")) {
                val on = choice == theme
                TextButton(
                    onClick = { actions.setTheme(choice) },
                    modifier = Modifier.weight(1f).background(if (on) s.cardA else Color.Transparent, RoundedCornerShape(9.dp)),
                ) { Text(label, color = if (on) s.text else s.text3) }
            }
        }
    }

    Card {
        Text("Updates", color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text("Rflow $version. It checks GitHub for a new version every time it opens.", color = s.text2)
        UpdateBanner(update, actions, onHome = false)
        OutlinedButton(onClick = actions.checkForUpdate, enabled = update !is UpdateState.Checking &&
            update !is UpdateState.Downloading) { Text("Check for updates", color = s.text) }
    }
}

/** The update, wherever it shows: offered, downloading, waiting for Android's permission, or why it failed. */
@Composable
private fun UpdateBanner(update: UpdateState, actions: Actions, onHome: Boolean) {
    val s = LocalSignal.current
    when (update) {
        is UpdateState.Available -> Card(highlight = true) {
            Text("Rflow ${update.release.version} is out", color = s.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            if (update.release.notes.isNotBlank()) Text(update.release.notes.take(300), color = s.text2, fontSize = 14.sp)
            Button(onClick = actions.installUpdate, colors = primary()) { Text("Update to ${update.release.version}") }
        }
        is UpdateState.Downloading -> Card(highlight = true) {
            Text("Downloading Rflow ${update.release.version}…", color = s.text)
            LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth(), color = s.primary,
                trackColor = s.well)
        }
        is UpdateState.NeedsPermission -> Card(highlight = true) {
            Text("Allow Rflow to install its updates", color = s.text, fontWeight = FontWeight.SemiBold)
            Text("Android asks once. Turn on \"Allow from this source\", then come back.", color = s.text2, fontSize = 14.sp)
            Button(onClick = actions.allowInstalls, colors = primary()) { Text("Open the setting") }
        }
        is UpdateState.Installing -> Card(highlight = true) {
            Text("Tap Update in Android's prompt to finish.", color = s.text)
        }
        is UpdateState.Failed -> if (!onHome) Text(update.message, color = s.err, fontSize = 14.sp)
        UpdateState.UpToDate -> if (!onHome) Text("● Up to date", color = s.ok, fontSize = 14.sp)
        UpdateState.Checking -> if (!onHome) Text("Checking…", color = s.text2, fontSize = 14.sp)
        UpdateState.Idle -> Unit
    }
}

@Composable
private fun Card(highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val s = LocalSignal.current
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(s.cardA, s.cardB)), RoundedCornerShape(18.dp))
            .border(1.dp, if (highlight) s.iris else s.edge, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun primary() = LocalSignal.current.let { ButtonDefaults.buttonColors(containerColor = it.primary, contentColor = it.onPrimary) }

@Composable
fun rememberPage() = remember { mutableStateOf(Page.HOME) }
