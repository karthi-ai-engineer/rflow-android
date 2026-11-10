package io.github.karthiaiengineer.rflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.karthiaiengineer.rflow.core.Languages

@Composable
fun Card(highlight: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val s = LocalSignal.current
    Column(
        modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(s.cardA, s.cardB)), RoundedCornerShape(18.dp))
            .border(1.dp, if (highlight) s.iris else s.edge, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun primary() = LocalSignal.current.let { ButtonDefaults.buttonColors(containerColor = it.primary, contentColor = it.onPrimary) }

@Composable
fun Title(text: String) = Text(text, color = LocalSignal.current.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Heading(text: String) = Text(text, color = LocalSignal.current.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)

@Composable
fun Body(text: String, color: Color = LocalSignal.current.text2, size: Int = 15) = Text(text, color = color, fontSize = size.sp)

@Composable
fun Lamp(color: Color) = Box(Modifier.size(8.dp).background(color, CircleShape))

/** A labelled switch row, as the desktop's toggles. */
@Composable
fun Toggle(label: String, hint: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val s = LocalSignal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = s.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (hint.isNotEmpty()) Text(hint, color = s.text3, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = s.primary, checkedThumbColor = s.onPrimary, uncheckedTrackColor = s.well,
            uncheckedThumbColor = s.text3, uncheckedBorderColor = s.cardB))
    }
}

/** A language, chosen from the list live translation knows. */
@Composable
fun LanguagePicker(label: String, code: String, enabled: Boolean = true, onPick: (String) -> Unit) {
    val s = LocalSignal.current
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = s.text3, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Box {
            Button(
                onClick = { open = true }, enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = s.well, contentColor = s.text),
                shape = RoundedCornerShape(12.dp),
            ) { Text(Languages.name(code) + "  ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((name, c) in Languages.ALL) {
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(c) })
                }
            }
        }
    }
}

/** The big Start / Stop button. */
@Composable
fun StartStop(running: Boolean, start: String, onStart: () -> Unit, onStop: () -> Unit, enabled: Boolean = true) {
    val s = LocalSignal.current
    Button(
        onClick = if (running) onStop else onStart, enabled = enabled || running,
        modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
        colors = if (running) ButtonDefaults.buttonColors(containerColor = s.live, contentColor = Color.White) else primary(),
    ) { Text(if (running) "Stop" else start, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
