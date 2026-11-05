package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Jellyfin reader against a made-up server, and the addresses and logins around it. */
class JellyfinTest {

    private fun song(id: String, album: String, albumId: String, track: Int, artist: String = "Ada Whitlock") = JSONObject()
        .put("Id", id).put("Name", "Song $id").put("Type", "Audio")
        .put("Artists", JSONArray().put(artist))
        .put("ArtistItems", JSONArray().put(JSONObject().put("Id", "ar-$artist").put("Name", artist)))
        .put("AlbumArtist", "Ada Whitlock")
        .put("Album", album).put("AlbumId", albumId)
        .put("IndexNumber", track).put("ParentIndexNumber", 1)
        .put("ProductionYear", 1911)
        .put("RunTimeTicks", 1_234_560_000L)
        .put("Container", "flac")
        .put("MediaSources", JSONArray().put(JSONObject().put("Size", 4_200_000L).put("Container", "flac")))

    private fun album(id: String, name: String, songs: Int) = JSONObject()
        .put("Id", id).put("Name", name).put("Type", "MusicAlbum")
        .put("AlbumArtist", "Ada Whitlock")
        .put("AlbumArtists", JSONArray().put(JSONObject().put("Id", "ar-ada").put("Name", "Ada Whitlock")))
        .put("ProductionYear", 1911).put("ChildCount", songs)

    /** A server of two albums and [songCount] songs, paging as Jellyfin does. */
    private inner class FakeServer(songCount: Int = 3) {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val albums = listOf(album("a1", "Field Songs", 2), album("a2", "Night Songs", songCount - 2))
        val songs = (1..songCount).map { n -> if (n <= 2) song("s$n", "Field Songs", "a1", n) else song("s$n", "Night Songs", "a2", n - 2) }
        val playlists = listOf(JSONObject().put("Id", "p1").put("Name", "Walks").put("Type", "Playlist"))

        private fun page(all: List<JSONObject>, params: Map<String, String>): SubsonicResult<JSONObject> {
            val start = params["StartIndex"]?.toInt() ?: 0
            val limit = params["Limit"]?.toInt() ?: all.size
            return SubsonicResult.Success(
                JSONObject()
                    .put("Items", JSONArray(all.drop(start).take(limit)))
                    .put("TotalRecordCount", all.size),
            )
        }

        suspend fun call(path: String, params: Map<String, String>): SubsonicResult<JSONObject> {
            calls += path to params
            if (path == "/Playlists/p1/Items") {
                return page(listOf(songs[2], JSONObject().put("Id", "v1").put("Type", "Video"), songs[0]), params)
            }
            assertEquals("/Items", path)
            assertEquals("u1", params["userId"])
            params["Ids"]?.let { id ->
                return page((albums + songs + listOf(JSONObject().put("Id", "ar-ada").put("Name", "Ada Whitlock"))).filter { it.getString("Id") == id }, params)
            }
            return when (params["IncludeItemTypes"]) {
                "MusicAlbum" -> page(
                    params["AlbumArtistIds"]?.let { artist -> albums.filter { artist == "ar-ada" } } ?: albums,
                    params,
                )
                "Audio" -> page(params["ParentId"]?.let { p -> songs.filter { it.getString("AlbumId") == p } } ?: songs, params)
                "Playlist" -> page(playlists, params)
                else -> error("unexpected query $params")
            }
        }
    }

    @Test
    fun `the whole library is read as albums and songs`() = runBlocking {
        val server = FakeServer()
        val progress = mutableListOf<SubsonicProgress>()
        val library = (JellyfinLibraryReader("u1", server::call).read { progress += it } as SubsonicResult.Success).value

        assertEquals(listOf("Field Songs", "Night Songs"), library.albums.map { it.name })
        assertEquals("ar-ada", library.albums[0].artistId)
        assertEquals(2, library.albums[0].songCount)
        assertEquals(1911, library.albums[0].year)

        assertEquals(3, library.songs.size)
        val first = library.songs[0]
        assertEquals("s1", first.id)
        assertEquals("Song s1", first.title)
        assertEquals("Ada Whitlock", first.artist)
        assertEquals("a1", first.albumId)
        assertEquals("Field Songs", first.album)
        assertEquals(1, first.trackNumber)
        assertEquals(1, first.discNumber)
        assertEquals(123_456L, first.durationMillis)
        assertEquals(4_200_000L, first.sizeBytes)
        assertEquals("flac", first.suffix)
        assertEquals(SubsonicProgress.Songs(3, 3), progress.last())
    }

    @Test
    fun `a large library is read in pages of five hundred`() = runBlocking {
        val server = FakeServer(songCount = 1203)
        val library = (JellyfinLibraryReader("u1", server::call).read() as SubsonicResult.Success).value
        assertEquals(1203, library.songs.size)
        assertEquals(1203, library.songs.map { it.id }.toSet().size)
        val songPages = server.calls.filter { it.second["IncludeItemTypes"] == "Audio" }
        assertEquals(listOf("0", "500", "1000"), songPages.map { it.second["StartIndex"] })
    }

    @Test
    fun `an album page holds just its own songs`() = runBlocking {
        val (album, songs) = (JellyfinLibraryReader("u1", FakeServer()::call).readAlbum("a1") as SubsonicResult.Success).value
        assertEquals("Field Songs", album.name)
        assertEquals(listOf("s1", "s2"), songs.map { it.id })
    }

    @Test
    fun `one song by its id, and none for what is not a song`() = runBlocking {
        val reader = JellyfinLibraryReader("u1", FakeServer()::call)
        assertEquals("Song s3", (reader.readSong("s3") as SubsonicResult.Success).value?.title)
        assertNull((reader.readSong("a1") as SubsonicResult.Success).value)
        assertNull((reader.readSong("gone") as SubsonicResult.Success).value)
    }

    @Test
    fun `an artist page names them and lists their albums`() = runBlocking {
        val (name, albums) = (JellyfinLibraryReader("u1", FakeServer()::call).readArtist("ar-ada") as SubsonicResult.Success).value
        assertEquals("Ada Whitlock", name)
        assertEquals(2, albums.size)
    }

    @Test
    fun `a playlist keeps its songs in order and leaves out what is not a song`() = runBlocking {
        val playlists = (JellyfinLibraryReader("u1", FakeServer()::call).readPlaylists() as SubsonicResult.Success).value
        assertEquals(1, playlists.size)
        assertEquals("Walks", playlists[0].name)
        assertEquals(listOf("s3", "s1"), playlists[0].songIds)
    }

    @Test
    fun `a refused sign-in stops the read`() = runBlocking {
        val refused = SubsonicResult.Failure("no", code = 40)
        val result = JellyfinLibraryReader("u1") { _, _ -> refused }.read()
        assertTrue(result is SubsonicResult.Failure && result.isAboutTheAccount)
    }

    @Test
    fun `a stream address asks for the file as it is and carries the sign-in`() {
        val url = JellyfinClient.universalStreamUrl("http://192.168.1.20:8096/", "abc123", "u1", "dev1", "tok")
        assertTrue(url.startsWith("http://192.168.1.20:8096/Audio/abc123/universal?"))
        assertTrue("UserId=u1" in url)
        assertTrue("DeviceId=dev1" in url)
        assertTrue("ApiKey=tok" in url)
        assertTrue("TranscodingContainer=mp3" in url)
        // Every kind this phone plays is named, so none of them is re-encoded.
        val containers = url.substringAfter("Container=").substringBefore('&').replace("%2C", ",").split(',')
        assertTrue(containers.containsAll(listOf("flac", "mp3", "opus", "ogg", "m4a")))
        assertTrue(JellyfinClient.isUniversalStream(url))
        assertFalse(JellyfinClient.isUniversalStream("http://h/rest/stream?id=1"))
    }

    @Test
    fun `a bitrate ceiling goes on a jellyfin address in bits a second, once`() {
        val url = JellyfinClient.universalStreamUrl("https://j.example.org", "abc", "u1", "d", "t")
        val capped = withBitrateCeiling(url, 192)
        assertTrue(capped.endsWith("&MaxStreamingBitrate=192000"))
        val again = withBitrateCeiling(capped, 128)
        assertEquals(1, Regex("MaxStreamingBitrate=").findAll(again).count())
        assertTrue(again.endsWith("MaxStreamingBitrate=128000"))
        assertEquals(url, withBitrateCeiling(url, null))
    }

    @Test
    fun `a subsonic address still takes its ceiling the old way`() {
        val capped = withBitrateCeiling("http://h/rest/stream?u=x&id=1&format=raw", 192)
        assertEquals("http://h/rest/stream?u=x&id=1&maxBitRate=192", capped)
    }

    @Test
    fun `a jellyfin login is complete with a token and no password`() {
        val typed = SubsonicConfig("jf.example.org", "ada", "secret", ServerKind.JELLYFIN)
        assertTrue(typed.isComplete)
        assertFalse(typed.isSignedIn)
        val kept = typed.copy(password = "", token = "tok", userId = "u1")
        assertTrue(kept.isComplete)
        assertTrue(kept.isSignedIn)
        assertFalse(SubsonicConfig("jf.example.org", "ada", "", ServerKind.JELLYFIN).isComplete)
        // A Subsonic login still needs its password.
        assertFalse(SubsonicConfig("nd.example.org", "ada", "", token = "tok", userId = "u1").isComplete)
    }

    @Test
    fun `a login printed shows neither the password nor the token`() {
        val text = SubsonicConfig("jf.example.org", "ada", "secret", ServerKind.JELLYFIN, "tok123", "u1").toString()
        assertFalse("secret" in text)
        assertFalse("tok123" in text)
    }

    @Test
    fun `a server kept before there was a choice is subsonic`() {
        assertEquals(ServerKind.SUBSONIC, ServerKind.fromStored(null))
        assertEquals(ServerKind.JELLYFIN, ServerKind.fromStored("JELLYFIN"))
        assertEquals(ServerKind.SUBSONIC, ServerKind.fromStored("nonsense"))
    }
}
