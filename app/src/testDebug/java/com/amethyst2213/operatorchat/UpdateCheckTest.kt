package com.amethyst2213.operatorchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The update check.
 *
 * The published manifest and the APK are public, so the app can find a new
 * build WITHOUT a token - which matters, because the phone that needs the
 * update is often the one that cannot sign in any more.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class UpdateCheckTest {

    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serveManifest(version: String, versionCode: Long, sha: String = "a".repeat(64)) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = """
                    {"app":"operator-chat","latestVersion":"$version","versionCode":$versionCode,
                     "apkUrl":"/gallery/assets/apk/OperatorChat-v$version.apk",
                     "sha256":"$sha","changelog":"A newer build.","publishedAt":"2026-10-21"}
                """.trimIndent()
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
    }

    @Test
    fun `a newer build is found without a token`() {
        serveManifest("3.1", 63)
        val offer = runBlocking {
            Updater.checkLatest(context, server.url("/gallery").toString())
        }
        assertNotNull("the published build was not found", offer)
        assertEquals("3.1", offer!!.version)
        assertEquals(63L, offer.versionCode)
    }

    @Test
    fun `the same build is not offered again`() {
        serveManifest("3.1", 63)
        // The installed build in the test is version 1, so a manifest with
        // version code 1 is not newer.
        serveManifest("0.1", 1)
        val offer = runBlocking {
            Updater.checkLatest(context, server.url("/gallery").toString())
        }
        assertNull("an older build was offered", offer)
    }

    @Test
    fun `a manifest without a checksum is not offered`() {
        serveManifest("3.1", 63, sha = "")
        val offer = runBlocking {
            Updater.checkLatest(context, server.url("/gallery").toString())
        }
        assertNull("an unverifiable build was offered", offer)
    }

    @Test
    fun `an unreachable site is not a crash`() {
        val offer = runBlocking {
            Updater.checkLatest(context, "http://127.0.0.1:1/gallery")
        }
        assertNull(offer)
    }
}
