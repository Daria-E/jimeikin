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

    private fun find(
        sources: Set<SearchSource>,
        songs: List<SongUiModel> = emptyList(),
        albums: List<AlbumUiModel> = emptyList(),
        artists: List<ArtistUiModel> = emptyList(),
        online: List<SongUiModel> = emptyList(),
    ) = buildSearchResults(
        query = "lantern",
        librarySongs = songs,
        libraryAlbums = albums,
        libraryArtists = artists,
        onlineSongs = online,
        onlineAlbums = emptyList(),
        onlineArtists = emptyList(),
        sources = sources,
    ).map(::describe)

    @Test
    fun onlyTheChosenSourcesAreLookedIn() {
        val songs = listOf(
            song("s1", "Lantern on the card", "Ada Whitlock"),
            song("s2", "Lantern on the server", "Mara Quill", source = "SUBSONIC"),
        )
        val online = listOf(song("v1", "Lantern on YouTube", "Tomas Reyes", source = "ONLINE_SEARCH"))
        assertEquals(
            listOf("song:Lantern on the card"),
            find(setOf(SearchSource.PHONE), songs, online = online),
        )
        assertEquals(
            listOf("song:Lantern on the server"),
            find(setOf(SearchSource.SERVER), songs, online = online),
        )
        assertEquals(
            listOf("song:Lantern on YouTube"),
            find(setOf(SearchSource.ONLINE), songs, online = online),
        )
    }

    @Test
    fun aSongKeptFromTheServerIsFoundWithEitherOn() {
        val kept = listOf(song("s1", "Lantern", "Mara Quill", source = "SUBSONIC_DOWNLOAD"))
        assertEquals(listOf("song:Lantern"), find(setOf(SearchSource.PHONE), kept))
        assertEquals(listOf("song:Lantern"), find(setOf(SearchSource.SERVER), kept))
        assertTrue(find(setOf(SearchSource.ONLINE), kept).isEmpty())
    }

    @Test
    fun anArtistMarkedAsTheServersIsStillThePhonesWhenTheirSongsAreThere() {
        // The mark names what streams, so one on both is marked as the server's.
        val artist = ArtistUiModel("ar1", "Lantern Choir", 2, 1, origin = Origin.SERVER)
        val album = AlbumUiModel("a1", "Lantern Songs", "Lantern Choir", "SUBSONIC", origin = Origin.SERVER)
        val songs = listOf(
            song("s1", "First", "Lantern Choir", album = "Lantern Songs"),
            song("s2", "Second", "Lantern Choir", album = "Lantern Songs", source = "SUBSONIC"),
        )
        val found = find(setOf(SearchSource.PHONE), songs, listOf(album), listOf(artist))
        assertTrue("artist:Lantern Choir" in found)
        assertTrue("album:Lantern Songs" in found)
    }

    @Test
    fun aServerArtistWithNothingOnThePhoneIsNotThePhones() {
        val artist = ArtistUiModel("ar1", "Lantern Choir", 1, 1, origin = Origin.SERVER)
        val songs = listOf(song("s1", "Elsewhere", "Lantern Choir", source = "SUBSONIC"))
        assertTrue(find(setOf(SearchSource.PHONE), songs, artists = listOf(artist)).isEmpty())
    }

    @Test
    fun theLastSourceOnCannotBeTurnedOff() {
        val offered = listOf(SearchSource.PHONE, SearchSource.ONLINE)
        val on = setOf(SearchSource.PHONE)
        assertEquals(on, toggledSearchSource(on, SearchSource.PHONE, offered))
        assertEquals(setOf(SearchSource.PHONE, SearchSource.ONLINE), toggledSearchSource(on, SearchSource.ONLINE, offered))
    }

    @Test
    fun aSourceWithNoToggleIsNotFilteredOn() {
        // No server connected: its toggle is gone, and what was stored about it means nothing.
        val on = searchSourcesOn(setOf(SearchSource.PHONE), listOf(SearchSource.PHONE, SearchSource.ONLINE))
        assertEquals(setOf(SearchSource.PHONE, SearchSource.SERVER), on)
    }

    @Test
    fun onlyAForgottenSourceOnMeansAllOfThem() {
        val on = searchSourcesOn(setOf(SearchSource.SERVER), listOf(SearchSource.PHONE, SearchSource.ONLINE))
        assertEquals(SearchSource.entries.toSet(), on)
    }
}
