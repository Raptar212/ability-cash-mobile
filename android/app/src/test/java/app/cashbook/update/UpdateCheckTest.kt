package app.cashbook.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Разбор `releases/latest` GitHub (org.json — из Android, поэтому Robolectric). */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckTest {
    private val hash = "AB".repeat(32)
    private val base = "https://github.com/Raptar212/ability-cash-mobile/releases/download/v1.2.3/"

    private fun json(
        tag: String = "v1.2.3",
        name: String = "Cashbook-1.2.3.apk",
        url: String = base + name,
        digest: String = "sha256:$hash",
        extra: String = "",
    ) = """{"tag_name":"$tag",$extra"assets":[{"name":"notes.txt","browser_download_url":"${base}notes.txt"},
        {"name":"$name","browser_download_url":"$url","digest":"$digest"}]}"""

    @Test fun parsesGoodRelease() {
        assertEquals(Release("1.2.3", base + "Cashbook-1.2.3.apk", hash.lowercase()), UpdateCheck.parse(json()))
    }

    @Test fun rejectsUnusableReleases() {
        assertNull(UpdateCheck.parse(json(extra = "\"draft\":true,")))
        assertNull(UpdateCheck.parse(json(extra = "\"prerelease\":true,")))
        assertNull(UpdateCheck.parse(json(tag = "1.2.3"))) // без «v»
        assertNull(UpdateCheck.parse(json(tag = "v1.2"))) // не три числа
        assertNull(UpdateCheck.parse(json(name = "Cashbook-1.2.4.apk"))) // APK другой версии
        assertNull(UpdateCheck.parse(json(url = "https://evil.example/Cashbook-1.2.3.apk"))) // чужой адрес
        assertNull(UpdateCheck.parse(json(digest = ""))) // без SHA-256
        assertNull(UpdateCheck.parse(json(digest = "sha256:1234")))
        assertNull(UpdateCheck.parse("не json"))
        assertNull(UpdateCheck.parse("""{"tag_name":"v1.2.3"}""")) // без файлов
    }

    @Test fun tagVersionWithoutApk() {
        assertEquals("1.2.3", UpdateCheck.tagVersion("""{"tag_name":"v1.2.3","assets":[]}"""))
        assertNull(UpdateCheck.tagVersion("""{"tag_name":"v1.2.3","prerelease":true}"""))
    }

    @Test fun comparesVersions() {
        assertTrue(UpdateCheck.isNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.9")) // числа, не строки
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.99.99"))
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.0-debug")) // суффикс не считается
        assertTrue(UpdateCheck.isNewer("1.0.0", "0.1")) // старая схема версии — обновить
    }

    @Test fun sha256OfStream() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateCheck.sha256("abc".byteInputStream()),
        )
    }
}
