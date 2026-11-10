package io.github.karthiaiengineer.rflow.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class ReleasesTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var releases: Releases
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSum = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Before fun start() {
        server.start()
        releases = Releases(OkHttpClient(), server.url("/latest").toString())
    }

    @After fun stop() = server.shutdown()

    private fun json(tag: String, prerelease: Boolean = false, assets: List<String> = listOf(Releases.APK, Releases.SHA256)) =
        """{"tag_name": "$tag", "draft": false, "prerelease": $prerelease, "body": "What's new",
           "assets": [${assets.joinToString { """{"name": "$it", "size": ${apk.size}, "browser_download_url": "${server.url("/$it")}"}""" }}]}"""

    @Test fun offersANewerRelease() = runTest {
        server.enqueue(MockResponse().setBody(json("v0.2.0")))
        val release = releases.newerThan(Version(0, 1, 0))!!
        assertEquals(Version(0, 2, 0), release.version)
        assertTrue(release.apkUrl.endsWith("/Rflow.apk"))
        assertEquals("What's new", release.notes)
    }

    @Test fun nothingWhenItIsTheSameOrOlder() = runTest {
        server.enqueue(MockResponse().setBody(json("v0.1.0")))
        assertNull(releases.newerThan(Version(0, 1, 0)))
        server.enqueue(MockResponse().setBody(json("v0.0.9")))
        assertNull(releases.newerThan(Version(0, 1, 0)))
    }

    @Test fun nothingBeforeTheFirstRelease() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(releases.newerThan(Version(0, 1, 0)))
    }

    @Test fun skipsPreReleasesAndReleasesWithoutBothFiles() {
        assertNull(Releases.parse(json("v9.0.0", prerelease = true)))
        assertNull(Releases.parse(json("v9.0.0", assets = listOf(Releases.APK))))
        assertNull(Releases.parse(json("nightly")))
    }

    @Test fun downloadsAndChecksTheApk() = runTest {
        server.enqueue(MockResponse().setBody("$apkSum  Rflow.apk\n"))
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val release = Releases.parse(json("v0.2.0"))!!
        val seen = mutableListOf<Float>()
        val file = releases.download(release, temp.root.resolve("updates/Rflow.apk")) { seen += it }
        assertTrue(file.readBytes().contentEquals(apk))
        assertEquals(1f, seen.last())
    }

    @Test fun deletesADownloadThatDoesNotMatch() = runTest {
        server.enqueue(MockResponse().setBody("0".repeat(64)))
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val target = temp.root.resolve("Rflow.apk")
        try {
            releases.download(Releases.parse(json("v0.2.0"))!!, target)
            fail("a wrong checksum must not pass")
        } catch (e: IOException) {
            assertFalse(target.exists())
        }
    }
}
