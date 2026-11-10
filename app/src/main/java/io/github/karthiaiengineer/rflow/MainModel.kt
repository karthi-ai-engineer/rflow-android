package io.github.karthiaiengineer.rflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.karthiaiengineer.rflow.ai.KeyCheck
import io.github.karthiaiengineer.rflow.core.Release
import io.github.karthiaiengineer.rflow.core.ThemeChoice
import io.github.karthiaiengineer.rflow.core.Version
import io.github.karthiaiengineer.rflow.update.Installer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where an update stands, for the banner on Home and the row in Settings. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class NeedsPermission(val release: Release, val apk: File) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** The key's state: none saved, saved, being tested, or what the test found. */
data class KeyState(val saved: Boolean, val testing: Boolean = false, val result: KeyCheck? = null)

class MainModel(app: Application) : AndroidViewModel(app) {
    private val rflow = app as RflowApp
    val installed: Version = Version.parse(BuildConfig.VERSION_NAME)!!

    private val _key = MutableStateFlow(KeyState(saved = rflow.vault.read() != null))
    val key: StateFlow<KeyState> = _key

    private val _theme = MutableStateFlow(rflow.settings.theme)
    val theme: StateFlow<ThemeChoice> = _theme

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update
    private var updating: Job? = null

    fun setTheme(choice: ThemeChoice) {
        rflow.settings.theme = choice
        _theme.value = choice
    }

    /** Saves the key (sealed by the Keystore) and tests it straight away, as the desktop's key field does. */
    fun saveKey(text: String) {
        val key = text.trim()
        if (key.isEmpty()) return
        rflow.vault.write(key)
        _key.value = KeyState(saved = true)
        testKey()
    }

    fun testKey() {
        val key = rflow.vault.read() ?: return
        _key.value = _key.value.copy(testing = true, result = null)
        viewModelScope.launch {
            val result = rflow.gemini.check(key)
            _key.value = KeyState(saved = true, result = result)
        }
    }

    fun removeKey() {
        rflow.vault.clear()
        _key.value = KeyState(saved = false)
    }

    /** On every open, and from "Check for updates". Quiet when [quiet]: no "up to date" or errors on Home. */
    fun checkForUpdate(quiet: Boolean = false) {
        if (updating?.isActive == true) return
        updating = viewModelScope.launch {
            if (!quiet) _update.value = UpdateState.Checking
            _update.value = try {
                rflow.releases.newerThan(installed)?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
            } catch (e: Exception) {
                if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn't reach GitHub: ${e.message}")
            }
        }
    }

    /** One tap: download, check the checksum, hand it to Android (which asks "Update?" once more). */
    fun installUpdate(release: Release) {
        if (updating?.isActive == true) return
        val app = getApplication<Application>()
        updating = viewModelScope.launch {
            try {
                _update.value = UpdateState.Downloading(release, 0f)
                val apk = rflow.releases.download(release, File(app.cacheDir, "updates/Rflow.apk")) {
                    _update.value = UpdateState.Downloading(release, it)
                }
                handOver(release, apk)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: "The update didn't download")
            }
        }
    }

    /** After the user allowed installs in Android's settings and came back. */
    fun resumeInstall() {
        val state = _update.value
        if (state is UpdateState.NeedsPermission && Installer.allowed(getApplication())) handOver(state.release, state.apk)
    }

    fun allowInstalls() = Installer.askToAllow(getApplication())

    private fun handOver(release: Release, apk: File) {
        val app = getApplication<Application>()
        if (!Installer.allowed(app)) {
            _update.value = UpdateState.NeedsPermission(release, apk)
            return
        }
        _update.value = UpdateState.Installing(release)
        try {
            Installer.install(app, apk)
        } catch (e: Exception) {
            _update.value = UpdateState.Failed(e.message ?: "The update didn't install")
        }
    }

    fun installFailed(message: String) {
        _update.value = UpdateState.Failed(message)
    }
}
