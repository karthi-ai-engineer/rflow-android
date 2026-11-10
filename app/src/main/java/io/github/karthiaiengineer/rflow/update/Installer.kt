package io.github.karthiaiengineer.rflow.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Hands a downloaded, checked APK to Android's installer. Android then shows its own "Update" prompt (it always does
 * for apps not from the Play Store) and restarts Rflow on the new version, settings and key kept. An APK signed with
 * another key is refused by Android, so only our own releases can ever replace the app.
 */
object Installer {
    private const val ACTION = "io.github.karthiaiengineer.rflow.INSTALL_STATUS"

    /** A failed install's reason, for the screen; null while all is well. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure

    /** False until the user allows Rflow to install updates (asked once, in Android's settings). */
    fun allowed(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for Rflow. */
    fun askToAllow(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        _failure.value = null
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android may then skip the prompt when Rflow installed the version on the phone itself.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("Rflow.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java).setAction(ACTION), flags
            )
            session.commit(status.intentSender)
        }
    }

    internal fun failed(message: String) {
        _failure.value = message
    }
}

/** Android's answers while installing: show its confirmation, or say why it failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // Android restarts the app on the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> Installer.failed("The update was cancelled")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Installer.failed("This update can't replace the installed app (signed with another key?). Reinstall it from the website.")
            else -> Installer.failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update didn't install ($status)"
            )
        }
    }
}
