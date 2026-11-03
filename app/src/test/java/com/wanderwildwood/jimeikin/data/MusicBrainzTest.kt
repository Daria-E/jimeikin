package com.wanderwildwood.jimeikin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up artists and albums, in MusicBrainz's answer shapes. */
class MusicBrainzTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Mara Quill",
        album: String = "Glass Orchard",
        albumId: String = "${UNTAGGED_ALBUM_PREFIX}music/glass orchard:glassorchard",
        track: Int? = null,
        year: Int? = null,
        length: Long? = null,
    ) = SongEntity(id, title, artist, album, albumId, null, track, length, "LOCAL_FILE", id, releaseYear = year)

    private val searchJson = """
    {"releases":[
      {"id":"r-live","score":100,"title":"Glass Orchard (Live)","track-count":3,"artist-credit":[{"name":"Mara Quill"}]},
      {"id":"r-other","score":100,"title":"Glass Orchard","track-count":3,"artist-credit":[{"name":"Tomas Reyes"}]},
      {"id":"r-deluxe","score":95,"title":"Glass Orchard","track-count":5,"artist-credit":[{"name":"Mara Quill","joinphrase":" & "},{"name":"Dov Ash"}]},
      {"id":"r-main","score":98,"title":"Glass Orchard","track-count":3,"artist-credit":[{"name":"Mara Quill"}]},
      {"id":"r-low","score":40,"title":"Glass Orchard","track-count":3,"artist-credit":[{"name":"Mara Quill"}]}
    ]}
    """

    private val releaseJson = """
    {"id":"r-main","title":"Glass Orchard","date":"2019-04-02","artist-credit":[{"name":"Mara Quill"}],
     "media":[
       {"position":1,"tracks":[
         {"position":1,"title":"Lantern","length":201000},
         {"position":2,"title":"Tide Clock","length":185000}]},
       {"position":2,"tracks":[
         {"position":1,"title":"Orchard Song","length":null}]}
     ]}
    """

    private val album = MusicBrainzMatcher.LocalAlbum(
        albumId = "a1",
        title = "Glass Orchard",
        artist = "Mara Quill",
        tracks = listOf(
            MusicBrainzMatcher.LocalTrack("s1", "Lantern", 200_500),
            MusicBrainzMatcher.LocalTrack("s2", "Tide Clock (2020 Remaster)", null),
            MusicBrainzMatcher.LocalTrack("s3", "Orchard Song", 300_000),
        ),
    )

    @Test
    fun readsSearchResultsAndCredits() {
        val c = MusicBrainzParse.candidates(searchJson)
        assertEquals(5, c.size)
        assertEquals("Mara Quill & Dov Ash", c[2].artist)
        assertTrue(MusicBrainzParse.candidates("not json").isEmpty())
    }

    @Test
    fun picksTheSameTitleAndArtistWithTheSameTrackCountFirst() {
        val picked = MusicBrainzMatcher.pick(album, MusicBrainzParse.candidates(searchJson))
        assertEquals(listOf("r-main", "r-deluxe"), picked.map { it.id })
    }

    @Test
    fun placesSongsOnTheRelease() {
        val release = MusicBrainzParse.release(releaseJson)!!
        assertEquals(2019, release.year)
        val match = MusicBrainzMatcher.fit(album, release)!!
        assertEquals("Mara Quill", match.artist)
        assertEquals(1 to 1, match.tracks["s1"])
        assertEquals(1 to 2, match.tracks["s2"])
        // No length on MusicBrainz's side: the title alone places it.
        assertEquals(2 to 1, match.tracks["s3"])
    }

    @Test
    fun aDifferentRecordingOfTheSameTitleIsNotPlaced() {
        val release = MusicBrainzParse.release(releaseJson)!!
        val far = album.copy(tracks = listOf(
            MusicBrainzMatcher.LocalTrack("s1", "Lantern", 420_000),
            MusicBrainzMatcher.LocalTrack("s2", "Tide Clock", 185_000),
            MusicBrainzMatcher.LocalTrack("s3", "Orchard Song", null),
        ))
        val match = MusicBrainzMatcher.fit(far, release)!!
        assertNull(match.tracks["s1"])
        assertEquals(2, match.tracks.size)
    }

    @Test
    fun tooFewSongsOnTheReleaseIsNoMatch() {
        val release = MusicBrainzParse.release(releaseJson)!!
        val other = album.copy(tracks = listOf(
            MusicBrainzMatcher.LocalTrack("s1", "Lantern", null),
            MusicBrainzMatcher.LocalTrack("s2", "Paper Boats", null),
            MusicBrainzMatcher.LocalTrack("s3", "Cold Frame", null),
        ))
        assertNull(MusicBrainzMatcher.fit(other, release))
    }

    @Test
    fun onlyAlbumsWithGapsAreLookedUp() {
        val tagged = "LOCAL_FILE:maraquill:quietrooms"
        val songs = listOf(
            song("u1", "Lantern"), song("u2", "Tide Clock"),
            song("t1", "Door", album = "Quiet Rooms", albumId = tagged, track = 1, year = 2021),
            song("t2", "Hall", album = "Quiet Rooms", albumId = tagged, track = 2, year = 2021),
        )
        val rows = mapOf(tagged to AlbumEntity(tagged, "Quiet Rooms", "Mara Quill", "LOCAL_FILE"))
        val todo = MusicBrainzMatcher.albumsToLookUp(songs, rows)
        assertEquals(listOf("${UNTAGGED_ALBUM_PREFIX}music/glass orchard:glassorchard"), todo.map { it.albumId })
        assertEquals("Mara Quill", todo.single().artist)
        assertTrue(MusicBrainzMatcher.query(todo.single()).contains("artist:\"Mara Quill\""))
    }

    @Test
    fun aMixIsSearchedByTitleAndTrackCount() {
        val id = "${UNTAGGED_ALBUM_PREFIX}music/summer:summer"
        val songs = listOf("Ada Whitlock", "Tomas Reyes", "Mara Quill").mapIndexed { i, a ->
            song("m$i", "Song $i", artist = a, album = "Summer \"Mix\"", albumId = id)
        }
        val todo = MusicBrainzMatcher.albumsToLookUp(songs, emptyMap()).single()
        assertNull(todo.artist)
        assertEquals("release:\"Summer \\\"Mix\\\"\" AND tracks:3", MusicBrainzMatcher.query(todo))
    }

    @Test
    fun keptMatchesFillGapsOnlyAndSurviveASaveAndRead() {
        val id = "${UNTAGGED_ALBUM_PREFIX}music/glass orchard:glassorchard"
        val match = MusicBrainzMatcher.AlbumMatch("r-main", "Mara Quill", 2019, mapOf("u1" to (1 to 1), "u2" to (2 to 4)))
        val kept = MusicBrainzMatches.parse(
            MusicBrainzMatches.EMPTY.with(id, MusicBrainzMatches.Entry("sig", match)).toJson(),
        )
        assertNotNull(kept.entry(id))
        assertEquals("Mara Quill", kept.albumArtist(id))
        // A tagged album keeps its own album artist.
        assertNull(kept.albumArtist("LOCAL_FILE:maraquill:glassorchard"))

        val filled = kept.fill(song("u2", "Tide Clock"))
        assertEquals(4, filled.trackNumber)
        assertEquals(2, filled.discNumber)
        assertEquals(2019, filled.releaseYear)

        val ownTags = kept.fill(song("u1", "Lantern", track = 7, year = 2001))
        assertEquals(7, ownTags.trackNumber)
        assertNull(ownTags.discNumber)
        assertEquals(2001, ownTags.releaseYear)
    }

    @Test
    fun theSignatureChangesWithTheSongs() {
        val more = album.copy(tracks = album.tracks + MusicBrainzMatcher.LocalTrack("s4", "Cold Frame", null))
        assertTrue(album.signature != more.signature)
        assertEquals(album.signature, album.copy(tracks = album.tracks.reversed()).signature)
    }
}
