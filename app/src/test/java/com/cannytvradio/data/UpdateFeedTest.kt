package com.cannytvradio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that decide whether the app offers and accepts an update. */
class UpdateFeedTest {
    private val base = "https://github.com/davbauer/CannyTVRadio/releases/download/v1.3/CannyTVRadio-1.3.apk"

    private fun feed(url: String = base, minSdk: Int = 21, abis: String = "[]", code: Int = 4) = """
        {"versionCode": $code, "versionName": "1.3",
         "changes": {"en": ["Faster"], "de": ["Schneller"]},
         "downloads": [{"target": "universal", "abis": $abis, "minSdk": $minSdk, "url": "$url",
                        "sha256": "ABCDEF", "size": 123}]}
    """.trimIndent()

    @Test fun readsTheTrustedDownload() {
        val info = UpdateFeed.parse(feed(), 34, listOf("armeabi-v7a"), "en")
        assertNotNull(info)
        assertEquals(4, info!!.versionCode)
        assertEquals("1.3", info.versionName)
        assertEquals(base, info.url)
        assertEquals("abcdef", info.sha256)
        assertEquals(123L, info.size)
        assertEquals(listOf("Faster"), info.changes)
    }

    @Test fun changesFollowTheLanguageWithEnglishAsFallback() {
        assertEquals(listOf("Schneller"), UpdateFeed.parse(feed(), 34, emptyList(), "de")!!.changes)
        assertEquals(listOf("Faster"), UpdateFeed.parse(feed(), 34, emptyList(), "fr")!!.changes)
    }

    @Test fun rejectsDownloadsFromAnywhereElse() {
        listOf(
            "https://evil.example.com/CannyTVRadio.apk",
            "https://github.com/someoneelse/CannyTVRadio/releases/download/v1.3/x.apk",
            "http://github.com/davbauer/CannyTVRadio/releases/download/v1.3/x.apk",
            "https://github.com/davbauer/CannyTVRadio/releases/download/../../other/x.apk",
            "https://github.com/davbauer/CannyTVRadio/releases/download/v1.3/x.apk?redirect=evil",
            "https://github.com/davbauer/CannyTVRadio/releases/download@evil.example.com/x.apk",
        ).forEach { assertNull(it, UpdateFeed.parse(feed(url = it), 34, emptyList(), "en")) }
    }

    @Test fun respectsAndroidVersionAndProcessor() {
        assertNull(UpdateFeed.parse(feed(minSdk = 26), 23, emptyList(), "en"))
        assertNotNull(UpdateFeed.parse(feed(minSdk = 26), 26, emptyList(), "en"))
        assertNull(UpdateFeed.parse(feed(abis = """["arm64-v8a"]"""), 34, listOf("armeabi-v7a"), "en"))
        assertNotNull(UpdateFeed.parse(feed(abis = """["arm64-v8a"]"""), 34, listOf("arm64-v8a", "armeabi-v7a"), "en"))
    }

    @Test fun onlyNewerVersionsAreOffered() {
        val info = UpdateFeed.parse(feed(code = 4), 34, emptyList(), "en")!!
        assertTrue(UpdateFeed.isNewer(info, 3))
        assertFalse(UpdateFeed.isNewer(info, 4))
        assertFalse(UpdateFeed.isNewer(info, 5))
    }

    @Test fun checksumMatchesAKnownValue() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            UpdateFeed.sha256("hello".byteInputStream()),
        )
    }

    @Test fun findsUpdateJsonInTheReleaseApiAnswer() {
        val release = org.json.JSONObject(
            """{"tag_name": "v1.3", "assets": [
                {"name": "CannyTVRadio.apk", "browser_download_url": "https://github.com/davbauer/CannyTVRadio/releases/download/v1.3/CannyTVRadio.apk"},
                {"name": "update.json", "browser_download_url": "https://github.com/davbauer/CannyTVRadio/releases/download/v1.3/update.json"}]}""",
        )
        assertEquals("https://github.com/davbauer/CannyTVRadio/releases/download/v1.3/update.json", UpdateFeed.feedUrlFromRelease(release))
    }

    @Test fun ignoresForeignUpdateJson() {
        val release = org.json.JSONObject(
            """{"assets": [{"name": "update.json", "browser_download_url": "https://evil.example.com/update.json"}]}""",
        )
        assertNull(UpdateFeed.feedUrlFromRelease(release))
        assertNull(UpdateFeed.feedUrlFromRelease(org.json.JSONObject("""{"message": "API rate limit exceeded"}""")))
    }

    @Test fun brokenFeedIsNotAnUpdate() {
        assertNull(UpdateFeed.parse("""{"versionCode": 4}""", 34, emptyList(), "en"))
    }
}
