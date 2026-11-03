package com.wanderwildwood.jimeikin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinkTest {

    private val video = "aB3_dEf-9Xy"
    private val list = "PLmadeUpList_01-x"

    @Test fun watchLink() {
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("https://www.youtube.com/watch?v=$video"))
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("http://youtube.com/watch?v=$video"))
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("https://m.youtube.com/watch?v=$video&t=42s"))
    }

    @Test fun musicLink() {
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("https://music.youtube.com/watch?v=$video&si=abc123"))
    }

    @Test fun shortLink() {
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("https://youtu.be/$video"))
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("https://youtu.be/$video?si=zzz&t=5"))
    }

    @Test fun videoInsideAPlaylist() {
        assertEquals(YouTubeLink(video, list), YouTubeLink.parse("https://www.youtube.com/watch?v=$video&list=$list&index=3"))
        assertEquals(YouTubeLink(video, list), YouTubeLink.parse("https://youtu.be/$video?list=$list"))
    }

    @Test fun playlistOnly() {
        assertEquals(YouTubeLink(null, list), YouTubeLink.parse("https://music.youtube.com/playlist?list=$list"))
        assertEquals(YouTubeLink(null, list), YouTubeLink.parse("https://www.youtube.com/playlist?list=$list&si=q"))
    }

    @Test fun parameterOrderAndEncoding() {
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("https://www.youtube.com/watch?feature=share&v=$video"))
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("  https://www.youtube.com/watch?v=%61B3_dEf-9Xy  "))
    }

    @Test fun hostIsCaseInsensitive() {
        assertEquals(YouTubeLink(video, null), YouTubeLink.parse("HTTPS://WWW.YouTube.com/watch?v=$video"))
    }

    @Test fun notYouTube() {
        assertNull(YouTubeLink.parse("https://example.com/watch?v=$video"))
        assertNull(YouTubeLink.parse("https://youtube.com.example.org/watch?v=$video"))
        assertNull(YouTubeLink.parse("https://notyoutu.be/$video"))
        assertNull(YouTubeLink.parse("ftp://www.youtube.com/watch?v=$video"))
        assertNull(YouTubeLink.parse("jimeikin://song?id=$video"))
    }

    @Test fun youTubeButNothingToPlay() {
        assertNull(YouTubeLink.parse("https://www.youtube.com/"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/watch"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/watch?v="))
        assertNull(YouTubeLink.parse("https://www.youtube.com/watch?v=short"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/playlist"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/@someone"))
        assertNull(YouTubeLink.parse("https://youtu.be/"))
        assertNull(YouTubeLink.parse("https://youtu.be/not an id"))
    }

    @Test fun garbage() {
        assertNull(YouTubeLink.parse(null))
        assertNull(YouTubeLink.parse(""))
        assertNull(YouTubeLink.parse("not a link at all"))
        assertNull(YouTubeLink.parse("https://"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/watch?v=$video%zz&x=%"))
    }

    @Test fun anIdCannotCarryAnythingElse() {
        assertNull(YouTubeLink.parse("https://www.youtube.com/watch?v=$video/../../x"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/watch?v=${video}x"))
    }

    @Test fun addressInSharedText() {
        assertEquals(
            "https://youtu.be/$video?si=abc",
            YouTubeLink.addressIn("Listen to this https://youtu.be/$video?si=abc."),
        )
        assertEquals(
            "https://music.youtube.com/watch?v=$video",
            YouTubeLink.addressIn("Harbour Lights - The Lanterns\nhttps://music.youtube.com/watch?v=$video"),
        )
        assertNull(YouTubeLink.addressIn("no link here"))
        assertNull(YouTubeLink.addressIn(null))
    }
}
