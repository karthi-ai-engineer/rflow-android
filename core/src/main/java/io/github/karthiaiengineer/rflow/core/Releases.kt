package io.github.karthiaiengineer.rflow.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A release on GitHub that carries the app: the version, the APK and its published checksum. */
data class Release(val version: Version, val apkUrl: String, val apkSize: Long, val sha256Url: String, val notes: String)

/**
 * Updates from GitHub Releases, as Rflow for Windows does: the newest release's `Rflow.apk` and `Rflow.apk.sha256`
 * (the asset names the website uses too). The installing is Android's (the app's `Installer`); this finds, downloads
 * and checks the file.
 */
class Releases(
    private val http: OkHttpClient,
    private val latestUrl: String = "https://api.github.com/repos/karthi-ai-engineer/rflow-android/releases/latest",
) {
    /** The newest release if it's newer than [installed]; null when there's none, it isn't newer, or it has no APK. */
    suspend fun newerThan(installed: Version): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(latestUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null  // no release yet
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            parse(response.body!!.string())?.takeIf { it.version > installed }
        }
    }

    /**
     * Downloads the APK into [target] and checks it against the published SHA-256; a file that doesn't match is deleted
     * and never installed. [progress] gets 0..1.
     */
    suspend fun download(release: Release, target: File, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val expected = http.newCall(Request.Builder().url(release.sha256Url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The checksum didn't download (${response.code})")
            response.body!!.string().trim().split(Regex("\\s+")).first().lowercase()
        }
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The update didn't download (${response.code})")
            val body = response.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            throw IOException("The download doesn't match its checksum; it was deleted")
        }
        target
    }

    companion object {
        const val APK = "Rflow.apk"
        const val SHA256 = "Rflow.apk.sha256"

        /** The release from GitHub's JSON; null for a draft, a pre-release, an odd tag or a release without both files. */
        fun parse(json: String): Release? {
            val release = JSONObject(json)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val version = Version.parse(release.optString("tag_name")) ?: return null
            val assets = release.optJSONArray("assets") ?: return null
            var apk: JSONObject? = null
            var sum: JSONObject? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.optString("name")) {
                    APK -> apk = asset
                    SHA256 -> sum = asset
                }
            }
            if (apk == null || sum == null) return null
            return Release(
                version = version,
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                sha256Url = sum.getString("browser_download_url"),
                notes = release.optString("body"),
            )
        }
    }
}
