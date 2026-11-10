package io.github.karthiaiengineer.rflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.karthiaiengineer.rflow.core.ThemeChoice
import io.github.karthiaiengineer.rflow.ui.Actions
import io.github.karthiaiengineer.rflow.ui.DarkSignal
import io.github.karthiaiengineer.rflow.ui.LightSignal
import io.github.karthiaiengineer.rflow.ui.Page
import io.github.karthiaiengineer.rflow.ui.RflowScreen
import io.github.karthiaiengineer.rflow.ui.RflowTheme
import io.github.karthiaiengineer.rflow.ui.rememberPage
import io.github.karthiaiengineer.rflow.update.Installer

class MainActivity : ComponentActivity() {
    private val model: MainModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) model.checkForUpdate(quiet = true)  // as the desktop: look for a new version on open
        setContent {
            val theme by model.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.DARK -> true
                ThemeChoice.LIGHT -> false
            }
            LaunchedEffect(dark) {
                val bar = (if (dark) DarkSignal else LightSignal).base.toArgb()
                val style = if (dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val key by model.key.collectAsStateWithLifecycle()
            val update by model.update.collectAsStateWithLifecycle()
            val failure by Installer.failure.collectAsStateWithLifecycle()
            LaunchedEffect(failure) { failure?.let(model::installFailed) }
            var page by rememberPage()
            BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
            RflowTheme(dark) {
                RflowScreen(
                    page = page, onPage = { page = it }, version = BuildConfig.VERSION_NAME,
                    key = key, theme = theme, update = update,
                    actions = Actions(
                        saveKey = model::saveKey, testKey = model::testKey, removeKey = model::removeKey,
                        setTheme = model::setTheme, checkForUpdate = { model.checkForUpdate() },
                        installUpdate = {
                            (update as? UpdateState.Available)?.let { model.installUpdate(it.release) }
                        },
                        allowInstalls = model::allowInstalls,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.resumeInstall()  // back from Android's "Install unknown apps" switch
    }
}
