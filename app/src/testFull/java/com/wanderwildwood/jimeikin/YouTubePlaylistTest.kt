package com.wanderwildwood.jimeikin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaylistTest {

    private val video = "aB3_dEf-9Xy"
    private val list = "PLmadeUpList_01-x"

    @Test fun playlistLinksNameTheirList() {
        for (address in listOf(
            "https://music.youtube.com/playlist?list=$list",
            "https://www.youtube.com/playlist?list=$list&si=q",
            "https://m.youtube.com/playlist?list=$list",
        )) {
            val link = YouTubeLink.parse(address)!!
            assertNull(link.videoId)
            assertEquals("https://www.youtube.com/playlist?list=$list", YouTubePlaylist.address(link))
        }
    }

    @Test fun aSongInAPlaylistKeepsTheSong() {
        val link = YouTubeLink.parse("https://music.youtube.com/watch?v=$video&list=$list&index=4")!!
        assertEquals("https://www.youtube.com/watch?v=$video&list=$list", YouTubePlaylist.address(link))
        val short = YouTubeLink.parse("https://youtu.be/$video?list=$list")!!
        assertEquals("https://www.youtube.com/watch?v=$video&list=$list", YouTubePlaylist.address(short))
    }

    @Test fun aMixKeepsItsSong() {
        val link = YouTubeLink.parse("https://www.youtube.com/watch?v=$video&list=RD$video")!!
        assertEquals("https://www.youtube.com/watch?v=$video&list=RD$video", YouTubePlaylist.address(link))
    }

    @Test fun aSongAloneHasNoPlaylist() {
        assertNull(YouTubePlaylist.address(YouTubeLink.parse("https://www.youtube.com/watch?v=$video")!!))
    }

    @Test fun playlistWithoutAListIsNoLink() {
        assertNull(YouTubeLink.parse("https://www.youtube.com/playlist"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/playlist?list="))
    }

    @Test fun restRunsFromTheSongRoundToTheStart() {
        val songs = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("d", "e", "a", "b"), YouTubePlaylist.rest("c", songs) { it })
        assertEquals(listOf("b", "c", "d", "e"), YouTubePlaylist.rest("a", songs) { it })
        assertEquals(listOf("a", "b", "c", "d"), YouTubePlaylist.rest("e", songs) { it })
    }

    @Test fun restWithoutTheSongIsTheWholePlaylist() {
        val songs = listOf("a", "b", "c")
        assertEquals(songs, YouTubePlaylist.rest(null, songs) { it })
        assertEquals(songs, YouTubePlaylist.rest("z", songs) { it })
    }

    @Test fun restNeverRepeatsASong() {
        assertEquals(listOf("d", "a", "b"), YouTubePlaylist.rest("c", listOf("a", "b", "c", "d", "a", "c", "b")) { it })
        assertEquals(listOf("a", "b"), YouTubePlaylist.rest(null, listOf("a", "a", "b")) { it })
    }

    @Test fun newOnesSkipsWhatIsThereAndStopsAtTheRoom() {
        assertEquals(listOf("c", "d"), YouTubePlaylist.newOnes(listOf("a", "b"), listOf("a", "c", "c", "d", "e"), 2) { it })
        assertEquals(emptyList<String>(), YouTubePlaylist.newOnes(emptyList(), listOf("a"), 0) { it })
    }

    @Test fun topicChannelsReadAsTheArtist() {
        assertEquals("Ada Whitlock", YouTubePlaylist.artist("Ada Whitlock - Topic"))
        assertEquals("Tomas Reyes", YouTubePlaylist.artist(" Tomas Reyes "))
        assertEquals("", YouTubePlaylist.artist(null))
    }

    @Test fun listedSongsGiveTheirId() {
        assertEquals(video, YouTubePlaylist.videoIdOf("https://www.youtube.com/watch?v=$video"))
        assertNull(YouTubePlaylist.videoIdOf("https://www.youtube.com/channel/UCxyz"))
        assertNull(YouTubePlaylist.videoIdOf(null))
    }
}
