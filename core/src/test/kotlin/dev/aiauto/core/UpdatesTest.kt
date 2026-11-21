package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    @Test fun picksTheNextPatchVersion() {
        assertEquals("v0.4.5", Versions.nextPatch(listOf("v0.4.4", "v0.10.0-beta", "v0.3.9", "latest")))
        assertEquals("v0.10.1", Versions.nextPatch(listOf("v0.9.9", "v0.10.0")))
        assertEquals("1.2.4", Versions.nextPatch(listOf("1.2.3")))
        assertEquals("v0.1.0", Versions.nextPatch(emptyList()))
    }

    @Test fun comparesVersions() {
        assertTrue(Versions.isNewer("v0.2.1", "0.2.0"))
        assertTrue(Versions.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Versions.isNewer("v1.0", "0.99.99"))
        assertFalse(Versions.isNewer("v0.2.0", "0.2.0"))
        assertFalse(Versions.isNewer("v0.2", "0.2.0"))
        assertFalse(Versions.isNewer("v0.1.9", "0.2.0"))
        assertFalse(Versions.isNewer("v0.3.0-beta", "0.3.0"))
    }

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        apk: Boolean = true,
        apkUrl: String = "https://github.com/mjparinas/cruisecontrol/releases/download/$tag/cruise-control-$tag.apk",
    ) = """
        {"tag_name":"$tag","html_url":"https://github.com/mjparinas/aiauto/releases/tag/$tag","draft":false,
         "prerelease":$prerelease,"assets":[${if (apk) """{"name":"aiauto-$tag.apk","size":123,"browser_download_url":"$apkUrl"}""" else ""}],
         "body":"notes"}
    """.trimIndent()

    @Test fun findsANewerApk() {
        val update = UpdateSource().parse(release("v0.3.0"), "0.2.0")!!
        assertEquals("0.3.0", update.version)
        assertEquals("https://github.com/mjparinas/cruisecontrol/releases/download/v0.3.0/cruise-control-v0.3.0.apk", update.apkUrl)
        assertEquals(123, update.apkSize)
    }

    @Test fun ignoresSameVersionPrereleasesAndReleasesWithoutAnApk() {
        assertNull(UpdateSource().parse(release("v0.2.0"), "0.2.0"))
        assertNull(UpdateSource().parse(release("v0.3.0", prerelease = true), "0.2.0"))
        assertNull(UpdateSource().parse(release("v0.3.0", apk = false), "0.2.0"))
    }

    @Test fun ignoresApksServedFromAnywhereButGitHub() {
        assertNull(UpdateSource().parse(release("v0.3.0", apkUrl = "https://example.com/app.apk"), "0.2.0"))
        assertNull(UpdateSource().parse(release("v0.3.0", apkUrl = "http://github.com/a/b/app.apk"), "0.2.0"))
        assertNull(UpdateSource().parse(release("v0.3.0", apkUrl = "https://github.com.example.com/app.apk"), "0.2.0"))
    }
}
