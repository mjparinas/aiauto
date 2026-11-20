package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    @Test fun comparesVersions() {
        assertTrue(Versions.isNewer("v0.2.1", "0.2.0"))
        assertTrue(Versions.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Versions.isNewer("v1.0", "0.99.99"))
        assertFalse(Versions.isNewer("v0.2.0", "0.2.0"))
        assertFalse(Versions.isNewer("v0.2", "0.2.0"))
        assertFalse(Versions.isNewer("v0.1.9", "0.2.0"))
        assertFalse(Versions.isNewer("v0.3.0-beta", "0.3.0"))
    }

    private fun release(tag: String, prerelease: Boolean = false, apk: Boolean = true) = """
        {"tag_name":"$tag","html_url":"https://github.com/mjparinas/aiauto/releases/tag/$tag","draft":false,
         "prerelease":$prerelease,"assets":[${if (apk) """{"name":"aiauto-$tag.apk","size":123,"browser_download_url":"https://x/aiauto-$tag.apk"}""" else ""}],
         "body":"notes"}
    """.trimIndent()

    @Test fun findsANewerApk() {
        val update = UpdateSource().parse(release("v0.3.0"), "0.2.0")!!
        assertEquals("0.3.0", update.version)
        assertEquals("https://x/aiauto-v0.3.0.apk", update.apkUrl)
        assertEquals(123, update.apkSize)
    }

    @Test fun ignoresSameVersionPrereleasesAndReleasesWithoutAnApk() {
        assertNull(UpdateSource().parse(release("v0.2.0"), "0.2.0"))
        assertNull(UpdateSource().parse(release("v0.3.0", prerelease = true), "0.2.0"))
        assertNull(UpdateSource().parse(release("v0.3.0", apk = false), "0.2.0"))
    }
}
