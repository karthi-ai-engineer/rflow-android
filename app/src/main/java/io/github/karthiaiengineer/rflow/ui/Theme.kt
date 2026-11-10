package io.github.karthiaiengineer.rflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Obsidian Signal, Rflow's look: the desktop's colours (`sst/theme.py`), each with one job. */
@Immutable
data class Signal(
    val base: Color, val well: Color, val cardA: Color, val cardB: Color, val rim: Color,
    val text: Color, val text2: Color, val text3: Color,
    val iris: Color, val violet: Color, val live: Color, val ok: Color, val warn: Color, val err: Color,
    val primary: Color, val onPrimary: Color, val edge: Color,
)

val DarkSignal = Signal(
    base = Color(0xFF191C22), well = Color(0xFF101216), cardA = Color(0xFF1E2128), cardB = Color(0xFF15171C),
    rim = Color(0xFF6B7386), text = Color(0xFFECEEF3), text2 = Color(0xFFA6ACBA), text3 = Color(0xFF8A91A1),
    iris = Color(0xFF8C9DFF), violet = Color(0xFFB48CFF), live = Color(0xFFFF6B6B), ok = Color(0xFF5BE3A6),
    warn = Color(0xFFFFC56B), err = Color(0xFFFF7D93), primary = Color(0xFF8C9DFF), onPrimary = Color(0xFF0B0D13),
    edge = Color(0x0FFFFFFF),
)

val LightSignal = Signal(
    base = Color(0xFFE8EBF1), well = Color(0xFFDFE3EA), cardA = Color(0xFFF3F5F9), cardB = Color(0xFFE0E4EB),
    rim = Color(0xFF787F91), text = Color(0xFF161920), text2 = Color(0xFF3A4050), text3 = Color(0xFF5E6575),
    iris = Color(0xFF3F4ACB), violet = Color(0xFF6A3ED4), live = Color(0xFFB42F2F), ok = Color(0xFF0B6B42),
    warn = Color(0xFF8A4E00), err = Color(0xFFB02640), primary = Color(0xFF4651D2), onPrimary = Color(0xFFFFFFFF),
    edge = Color(0xB3FFFFFF),
)

val LocalSignal = staticCompositionLocalOf { DarkSignal }

@Composable
fun RflowTheme(dark: Boolean, content: @Composable () -> Unit) {
    val s = if (dark) DarkSignal else LightSignal
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = s.primary, onPrimary = s.onPrimary, secondary = s.violet,
        background = s.base, onBackground = s.text, surface = s.cardA, onSurface = s.text,
        surfaceVariant = s.well, onSurfaceVariant = s.text2, surfaceContainerHighest = s.well,
        outline = s.rim, outlineVariant = s.cardB, error = s.err,
    )
    CompositionLocalProvider(LocalSignal provides s) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
