package io.github.karthiaiengineer.rflow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.karthiaiengineer.rflow.core.ThemeChoice
import io.github.karthiaiengineer.rflow.live.LiveHub
import io.github.karthiaiengineer.rflow.live.LiveService
import io.github.karthiaiengineer.rflow.live.Mode
import io.github.karthiaiengineer.rflow.live.SessionSpec
import io.github.karthiaiengineer.rflow.live.Source
import io.github.karthiaiengineer.rflow.ui.Actions
import io.github.karthiaiengineer.rflow.ui.DarkSignal
import io.github.karthiaiengineer.rflow.ui.LightSignal
import io.github.karthiaiengineer.rflow.ui.Page
import io.github.karthiaiengineer.rflow.ui.RflowScreen
import io.github.karthiaiengineer.rflow.ui.RflowTheme
import io.github.karthiaiengineer.rflow.ui.Screen
import io.github.karthiaiengineer.rflow.update.Installer

class MainActivity : ComponentActivity() {
    private val model: MainModel by viewModels()

    /** The session waiting for a permission or Android's consent. */
    private var pending: SessionSpec? = null

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val spec = pending ?: return@registerForActivityResult
        if (granted[Manifest.permission.RECORD_AUDIO] == false) {
            pending = null
            Toast.makeText(this, "Rflow needs the microphone for this.", Toast.LENGTH_LONG).show()
        } else {
            launch(spec)
        }
    }

    private val askProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spec = pending ?: return@registerForActivityResult
        pending = null
        if (result.resultCode == RESULT_OK && result.data != null) {
            LiveService.start(this, spec, result.resultCode, result.data)
        } else {
            Toast.makeText(this, "Without Android's OK, Rflow can't hear what the phone plays.", Toast.LENGTH_LONG).show()
        }
    }

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
            val live by LiveHub.ui.collectAsStateWithLifecycle()
            val prefs by model.prefs.collectAsStateWithLifecycle()
            val meetings by model.meetings.collectAsStateWithLifecycle()
            val minutes by model.meetingStates.collectAsStateWithLifecycle()
            val testServer by model.testServer.collectAsStateWithLifecycle()
            LaunchedEffect(failure) { failure?.let(model::installFailed) }
            var page by rememberSaveable { mutableStateOf(Page.HOME) }
            var meetingId by rememberSaveable { mutableStateOf("") }
            BackHandler(enabled = page != Page.HOME) { page = if (page == Page.MEETING) Page.MEETINGS else Page.HOME }
            val meeting = if (page == Page.MEETING) meetings.firstOrNull { it.id == meetingId } ?: model.meeting(meetingId) else null
            RflowTheme(dark) {
                RflowScreen(
                    screen = Screen(
                        page = page, version = BuildConfig.VERSION_NAME, key = key, theme = theme, update = update,
                        live = live, prefs = prefs, meetings = meetings, meeting = meeting,
                        meetingLive = if (meeting != null && meeting.minutes == null) model.meetingLive(meetingId) else emptyList(),
                        minutes = minutes, debug = BuildConfig.DEBUG, testServer = testServer,
                    ),
                    onPage = { page = it },
                    actions = Actions(
                        saveKey = model::saveKey, testKey = model::testKey, removeKey = model::removeKey,
                        setTheme = model::setTheme, checkForUpdate = { model.checkForUpdate() },
                        installUpdate = { (update as? UpdateState.Available)?.let { model.installUpdate(it.release) } },
                        allowInstalls = model::allowInstalls,
                        setTarget = model::setTarget, setMine = model::setMine, setTheirs = model::setTheirs,
                        setSpeak = model::setSpeak, setSaver = model::setSaver,
                        startLive = { source -> begin(model.spec(Mode.LIVE, source)) },
                        startMeeting = { begin(model.spec(Mode.MEETING)) },
                        startConversation = { begin(model.spec(Mode.CONVERSATION)) },
                        clearConversation = model::clearConversation,
                        stop = model::stopSession,
                        openMeeting = { meetingId = it; page = Page.MEETING },
                        writeMinutes = model::writeMinutes,
                        shareMinutes = ::share,
                        deleteMeeting = { model.deleteMeeting(it); page = Page.MEETINGS },
                        setTestServer = model::setTestServer,
                    ),
                )
            }
        }
    }

    /** The microphone (and notifications) first; for what the phone plays, Android's consent too. */
    private fun begin(spec: SessionSpec) {
        pending = spec
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray()) else launch(spec)
    }

    private fun launch(spec: SessionSpec) {
        if (spec.source == Source.PHONE) {
            if (!Settings.canDrawOverlays(this)) {
                // The floating captions need "Display over other apps"; without it the captions show in Rflow only.
                Toast.makeText(this, "Allow \"Display over other apps\" for captions over the video, then start again.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                pending = null
                return
            }
            askProjection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else {
            pending = null
            LiveService.start(this, spec)
        }
    }

    private fun share(id: String) {
        val text = model.minutesText(id) ?: return
        val title = text.lineSequence().firstOrNull()?.removePrefix("# ").orEmpty()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), "Share the minutes"))
    }

    override fun onResume() {
        super.onResume()
        model.resumeInstall()  // back from Android's "Install unknown apps" switch
        model.refreshMeetings()
    }
}
