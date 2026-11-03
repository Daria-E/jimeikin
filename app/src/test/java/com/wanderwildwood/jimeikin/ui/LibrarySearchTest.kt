package com.wanderwildwood.jimeikin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up names throughout. */
class LibrarySearchTest {

    private fun song(id: String, title: String, artist: String, album: String? = null, source: String = "LOCAL_FILE") =
        SongUiModel(id = id, title = title, artist = artist, album = album, sourceType = source)

    private fun describe(r: SearchResult) = when (r) {
        is SearchResult.Song -> "song:" + r.song.title
        is SearchResult.Album -> "album:" + r.album.title
        is SearchResult.LibraryArtist -> "artist:" + r.artist.name
        is SearchResult.OnlineArtist -> "online-artist:" + r.artist.name
    }

    @Test
    fun theExactArtistLeadsEvenWithoutTheirThe() {
        val out = buildSearchResults(
            query = "glass orchard",
            librarySongs = listOf(song("s1", "Glass Orchard Blues", "Ada Whitlock")),
            libraryAlbums = listOf(AlbumUiModel("a1", "Glass Orchard", "Mara Quill", "LOCAL_FILE")),
            libraryArtists = listOf(
                ArtistUiModel("ar1", "Glass Orchardists", 3, 1),
                ArtistUiModel("ar2", "The Glass Orchard", 5, 2),
            ),
            onlineSongs = emptyList(),
            onlineAlbums = emptyList(),
            onlineArtists = emptyList(),
        )
        assertEquals("artist:The Glass Orchard", describe(out.first()))
    }

    @Test
    fun anExactOnlineArtistLeadsThePhonesSongsAndAlbums() {
        val out = buildSearchResults(
            query = "Tomas Reyes",
            librarySongs = listOf(song("s1", "Lantern", "Tomas Reyes & Ada Whitlock")),
            libraryAlbums = listOf(AlbumUiModel("a1", "Tomas Reyes Live", "Tomas Reyes & Ada Whitlock", "LOCAL_FILE")),
            libraryArtists = emptyList(),
            onlineSongs = listOf(song("v1", "Tide Clock", "Tomas Reyes", source = "ONLINE_SEARCH")),
            onlineAlbums = emptyList(),
            onlineArtists = listOf(OnlineArtistUiModel("b2", "Tomas Reyes Trio"), OnlineArtistUiModel("b1", "Tomás Reyes")),
        )
        assertEquals("online-artist:Tomás Reyes", describe(out.first()))
        // The near name is still offered, in YouTube's own place.
        assertTrue(out.any { describe(it) == "online-artist:Tomas Reyes Trio" })
    }

    @Test
    fun theLibrarysOwnCopyOfTheArtistIsTheOneKept() {
        val out = buildSearchResults(
            query = "mara quill",
            librarySongs = emptyList(),
            libraryAlbums = emptyList(),
            libraryArtists = listOf(ArtistUiModel("ar1", "Mara Quill", 4, 1)),
            onlineSongs = emptyList(),
            onlineAlbums = emptyList(),
            onlineArtists = listOf(OnlineArtistUiModel("b1", "Mara Quill")),
        )
        assertEquals(listOf("artist:Mara Quill"), out.map(::describe))
    }

    @Test
    fun exactMeansTheSameNameHoweverSpelled() {
        assertTrue(isExactArtist("beatles", "The Beatles"))
        assertTrue(isExactArtist("bjork", "Björk"))
        assertTrue(isExactArtist("iron and wine", "Iron & Wine"))
        assertFalse(isExactArtist("iron", "Iron & Wine"))
        assertFalse(isExactArtist("!!", "!!!"))
    }
}
