package com.wanderwildwood.jimeikin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A YouTube link pasted into search: which links are read, and how an album or playlist is named. */
class YouTubeSearchLinkTest {

    private val video = "aB3_dEf-9Xy"
    private val list = "PLmadeUpList_01-x"
    private val albumList = "OLAK5uy_madeUpAlbumList01"
    private val albumPage = "MPREb_madeUpAlbum01"

    @Test fun songLinksAreSongs() {
        for (address in listOf(
            "https://www.youtube.com/watch?v=$video",
            "https://youtu.be/$video",
            "https://music.youtube.com/watch?v=$video&si=abc",
        )) {
            val link = YouTubeLink.parse(address)!!
            assertEquals(video, link.videoId)
            assertNull(link.albumId)
            assertFalse(link.isAlbum)
        }
    }

    @Test fun playlistLinkIsAPlaylist() {
        val link = YouTubeLink.parse("https://music.youtube.com/playlist?list=$list")!!
        assertEquals(list, link.playlistId)
        assertFalse(link.isAlbum)
    }

    @Test fun albumPageLink() {
        val link = YouTubeLink.parse("https://music.youtube.com/browse/$albumPage")!!
        assertEquals(YouTubeLink(null, null, albumPage), link)
        assertTrue(link.isAlbum)
        // Shared from the app, with its tracking on the end.
        assertEquals(albumPage, YouTubeLink.parse("https://music.youtube.com/browse/$albumPage?si=xyz")?.albumId)
        assertEquals(albumPage, YouTubeLink.parse("HTTPS://MUSIC.YouTube.com/browse/$albumPage")?.albumId)
    }

    @Test fun albumPlaylistLink() {
        for (address in listOf(
            "https://music.youtube.com/playlist?list=$albumList",
            "https://www.youtube.com/playlist?list=$albumList&si=q",
        )) {
            val link = YouTubeLink.parse(address)!!
            assertEquals(albumList, link.playlistId)
            assertNull(link.videoId)
            assertTrue(link.isAlbum)
        }
    }

    @Test fun otherBrowsePagesAreNotAlbums() {
        // An artist's page, a channel, a browse with no id, or one on plain YouTube.
        assertNull(YouTubeLink.parse("https://music.youtube.com/browse/UCmadeUpArtist01"))
        assertNull(YouTubeLink.parse("https://music.youtube.com/browse/"))
        assertNull(YouTubeLink.parse("https://music.youtube.com/browse/MPREb_"))
        assertNull(YouTubeLink.parse("https://music.youtube.com/browse/MPREb_bad id"))
        assertNull(YouTubeLink.parse("https://www.youtube.com/browse/$albumPage"))
        assertNull(YouTubeLink.parse("https://example.com/browse/$albumPage"))
    }

    @Test fun shortsAreNotRead() {
        // The link parser takes watch, youtu.be and playlist links only; a Short is a video, not music.
        assertNull(YouTubeLink.parse("https://www.youtube.com/shorts/$video"))
    }

    @Test fun pastedWithWordsAround() {
        val text = "Lantern Road by Mara Quill https://music.youtube.com/browse/$albumPage."
        assertEquals(albumPage, YouTubeLink.parse(YouTubeLink.addressIn(text))?.albumId)
    }

    @Test fun linkListIdsRoundTrip() {
        val id = YouTubePlaylist.linkListId(list)
        assertEquals(list, YouTubePlaylist.listIdOf(id))
        // A YouTube Music album, a library album and an empty mark are no link's list.
        assertNull(YouTubePlaylist.listIdOf(albumPage))
        assertNull(YouTubePlaylist.listIdOf("12345"))
        assertNull(YouTubePlaylist.listIdOf(YouTubePlaylist.linkListId("")))
    }

    @Test fun playlistTitles() {
        assertEquals("Lantern Road", YouTubePlaylist.title("Album - Lantern Road", isAlbum = true))
        assertEquals("Album - Lantern Road", YouTubePlaylist.title("Album - Lantern Road", isAlbum = false))
        assertEquals("Evening Walks", YouTubePlaylist.title("  Evening Walks ", isAlbum = false))
        assertNull(YouTubePlaylist.title("  ", isAlbum = false))
        assertNull(YouTubePlaylist.title(null, isAlbum = true))
    }

    @Test fun albumHeaderFromThePage() {
        val page = JSONObject(
            """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicResponsiveHeaderRenderer":{
                "title":{"runs":[{"text":"Lantern Road"}]},
                "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2019"}]},
                "straplineTextOne":{"runs":[{"text":"Mara Quill"}]}
              }}
            ]}}}}]}},
             "microformat":{"microformatDataRenderer":{"title":"Other Title","description":"Album • Someone Else"}}}
            """.trimIndent(),
        )
        assertEquals(Triple("Lantern Road", "Mara Quill", 2019), parseAlbumHeader(page))
    }

    @Test fun albumHeaderFromTheMicroformat() {
        val page = JSONObject("""{"microformat":{"microformatDataRenderer":{"title":"Lantern Road","description":"Album • Mara Quill"}}}""")
        assertEquals(Triple("Lantern Road", "Mara Quill", null), parseAlbumHeader(page))
        assertEquals(Triple("", null, null), parseAlbumHeader(JSONObject("{}")))
    }
}
