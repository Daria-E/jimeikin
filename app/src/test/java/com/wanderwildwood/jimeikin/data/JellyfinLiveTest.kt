package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The reader and the stream addresses against a real Jellyfin server, when one is named.
 *
 * Skipped unless JELLYFIN_TEST_URL, JELLYFIN_TEST_USER and JELLYFIN_TEST_PASSWORD are set: point
 * them at a throwaway server seeded with a few songs of your own making, never a real library.
 * It signs in the way [JellyfinClient.signIn] does, reads the library through
 * [JellyfinLibraryReader], and fetches songs by the addresses the library would store.
 */
class JellyfinLiveTest {

    private val url = System.getenv("JELLYFIN_TEST_URL")
    private val user = System.getenv("JELLYFIN_TEST_USER")
    private val password = System.getenv("JELLYFIN_TEST_PASSWORD")
    private val http = OkHttpClient()
    private val deviceId = "live-test"
    private val auth = "MediaBrowser Client=\"Music Box\", Device=\"test\", DeviceId=\"$deviceId\", Version=\"0\""

    @Test
    fun `a real server signs in, lists its music and streams it`() = runBlocking {
        assumeTrue(url != null && user != null && password != null)
        val base = url!!.trimEnd('/')

        // Sign in.
        val signIn = Request.Builder().url("$base/Users/AuthenticateByName")
            .header("Authorization", auth)
            .post(JSONObject().put("Username", user).put("Pw", password).toString().toRequestBody("application/json".toMediaType()))
            .build()
        val session = http.newCall(signIn).execute().use { JSONObject(it.body!!.string()) }
        val token = session.getString("AccessToken")
        val userId = session.getJSONObject("User").getString("Id")

        // A wrong password is refused with 401, which the client words as a refused login.
        val wrong = Request.Builder().url("$base/Users/AuthenticateByName")
            .header("Authorization", auth)
            .post(JSONObject().put("Username", user).put("Pw", "not-it").toString().toRequestBody("application/json".toMediaType()))
            .build()
        assertEquals(401, http.newCall(wrong).execute().use { it.code })

        val reader = JellyfinLibraryReader(userId) { path, params ->
            val request = Request.Builder()
                .url(base + path + "?" + JellyfinClient.query(params))
                .header("Authorization", "$auth, Token=\"$token\"")
                .build()
            http.newCall(request).execute().use { r ->
                if (r.code == 401) SubsonicResult.Failure("signed out", code = 40)
                else SubsonicResult.Success(JSONObject(r.body!!.string()))
            }
        }

        val library = (reader.read() as SubsonicResult.Success).value
        println("live: ${library.albums.size} albums, ${library.songs.size} songs")
        library.albums.forEach { println("live album: ${it.name} / ${it.artist} / ${it.artistId} / ${it.year} / ${it.songCount}") }
        library.songs.forEach {
            println("live song: ${it.title} / ${it.artist} / ${it.album} / #${it.trackNumber} d${it.discNumber} / ${it.durationMillis} ms / ${it.suffix} / ${it.sizeBytes} B")
        }
        assertTrue(library.albums.isNotEmpty())
        assertTrue(library.songs.isNotEmpty())
        assertTrue(library.songs.all { it.durationMillis != null && it.albumId != null })

        val firstAlbum = library.albums.first()
        val (album, albumSongs) = (reader.readAlbum(firstAlbum.id) as SubsonicResult.Success).value
        assertEquals(firstAlbum.name, album.name)
        assertTrue(albumSongs.isNotEmpty())
        assertTrue(albumSongs.all { it.albumId == firstAlbum.id })

        val song = library.songs.first()
        assertEquals(song.title, (reader.readSong(song.id) as SubsonicResult.Success).value?.title)
        firstAlbum.artistId?.let { artistId ->
            val (name, albums) = (reader.readArtist(artistId) as SubsonicResult.Success).value
            println("live artist: $name, ${albums.size} albums")
            assertTrue(albums.any { it.id == firstAlbum.id })
        }

        val playlists = (reader.readPlaylists() as SubsonicResult.Success).value
        playlists.forEach { println("live playlist: ${it.name}: ${it.songIds.size} songs") }

        // Every song plays from the address the library would store, as the player opens it:
        // a plain GET with nothing but the address. Kinds the phone plays come back as they
        // are; the rest come back as mp3.
        for (s in library.songs) {
            val stream = JellyfinClient.universalStreamUrl(base, s.id, userId, deviceId, token)
            http.newCall(Request.Builder().url(stream).build()).execute().use { r ->
                val bytes = r.body!!.bytes()
                println("live stream ${s.suffix}: ${r.code} ${r.header("Content-Type")} ${bytes.size} B")
                assertEquals(200, r.code)
                assertTrue(bytes.size > 1000)
                if (s.suffix != null && s.suffix.lowercase() in PLAYABLE_SUFFIXES) {
                    s.sizeBytes?.let { assertEquals(it, bytes.size.toLong()) }
                } else {
                    assertTrue(r.header("Content-Type").orEmpty().contains("mpeg") || r.header("Content-Type").orEmpty().contains("mp3"))
                }
            }
        }

        // A ceiling re-encodes what is above it.
        val big = library.songs.filter { it.suffix == "flac" }.maxByOrNull { it.sizeBytes ?: 0 }
        if (big != null) {
            val capped = withBitrateCeiling(JellyfinClient.universalStreamUrl(base, big.id, userId, deviceId, token), 96)
            http.newCall(Request.Builder().url(capped).build()).execute().use { r ->
                val bytes = r.body!!.bytes()
                println("live capped flac: ${r.code} ${r.header("Content-Type")} ${bytes.size} B (was ${big.sizeBytes})")
                assertEquals(200, r.code)
                assertTrue(bytes.size < (big.sizeBytes ?: Long.MAX_VALUE))
            }
        }

        // An address without the sign-in is refused.
        val unsigned = JellyfinClient.universalStreamUrl(base, song.id, userId, deviceId, "wrong-token")
        val refused = http.newCall(Request.Builder().url(unsigned).build()).execute().use { it.code }
        println("live unsigned stream: $refused")
        assertTrue(refused == 401 || refused == 403)

        // Signing out ends the token.
        http.newCall(
            Request.Builder().url("$base/Sessions/Logout").header("Authorization", "$auth, Token=\"$token\"")
                .post(ByteArray(0).toRequestBody(null)).build(),
        ).execute().use { assertTrue(it.isSuccessful) }
        val after = reader.read()
        assertTrue(after is SubsonicResult.Failure && after.isAboutTheAccount)
    }
}
