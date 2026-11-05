package com.wanderwildwood.jimeikin.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a Jellyfin server's music, by its REST API.
 *
 * Kept apart from [JellyfinClient], as [SubsonicLibraryReader] is from its client, so it takes
 * nothing but a way to make a call and can be tested against made-up answers.
 *
 * Jellyfin keeps one item list for everything, filtered by kind: the albums are the
 * `MusicAlbum` items, the songs the `Audio` ones, each read in pages of 500 with the count of
 * the whole given back on the first. Two kinds of read and a few calls each, so a library of
 * thousands of songs is read in seconds, as with a Subsonic server's quick route.
 *
 * Only the music libraries are asked: a film's soundtrack in a video library is a video's, not
 * a song, and `Audio` is what tells them apart.
 */
class JellyfinLibraryReader(
    private val userId: String,
    private val call: suspend (path: String, params: Map<String, String>) -> SubsonicResult<JSONObject>,
) {

    suspend fun read(onProgress: suspend (SubsonicProgress) -> Unit = {}): SubsonicResult<SubsonicLibrary> {
        val albums = when (val r = paged(albumQuery(), onPage = { _, _ -> })) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value.mapNotNull { it.toAlbum() }
        }
        val songs = when (
            val r = paged(songQuery(), onPage = { done, total -> onProgress(SubsonicProgress.Songs(done, total)) })
        ) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value.mapNotNull { it.toSong() }
        }
        return SubsonicResult.Success(SubsonicLibrary(albums, songs))
    }

    /** One album and its songs, in disc and track order. */
    suspend fun readAlbum(albumId: String): SubsonicResult<Pair<SubsonicAlbum, List<SubsonicSong>>> {
        val album = when (val r = byId(albumId)) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value?.toAlbum()
                ?: SubsonicAlbum(albumId, UNKNOWN_ALBUM, UNKNOWN_ARTIST, null, null)
        }
        val songs = when (val r = paged(songQuery() + mapOf("ParentId" to albumId), onPage = { _, _ -> })) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value.mapNotNull { it.toSong() }
        }
        return SubsonicResult.Success(album to songs)
    }

    /** One song, or null when the server no longer has it. */
    suspend fun readSong(songId: String): SubsonicResult<SubsonicSong?> =
        byId(songId).map { it?.takeIf { o -> o.optString("Type") == "Audio" }?.toSong() }

    /** An artist's name and the albums filed under them as album artist. */
    suspend fun readArtist(artistId: String): SubsonicResult<Pair<String, List<SubsonicAlbum>>> {
        val name = when (val r = byId(artistId)) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value?.optString("Name")?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
        }
        val albums = when (val r = paged(albumQuery() + mapOf("AlbumArtistIds" to artistId), onPage = { _, _ -> })) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value.mapNotNull { it.toAlbum() }
        }
        return SubsonicResult.Success(name to albums)
    }

    /**
     * The account's playlists and the song ids on each. Two calls per playlist, as with
     * Subsonic: one for the list, one for what is on it. A playlist with nothing playable on
     * it is left out, the same as an empty one.
     */
    suspend fun readPlaylists(): SubsonicResult<List<SubsonicPlaylist>> {
        val lists = when (
            val r = paged(
                mapOf(
                    "userId" to userId,
                    "IncludeItemTypes" to "Playlist",
                    "Recursive" to "true",
                    "SortBy" to "SortName",
                ),
                onPage = { _, _ -> },
            )
        ) {
            is SubsonicResult.Failure -> return r
            is SubsonicResult.Success -> r.value
        }
        val result = mutableListOf<SubsonicPlaylist>()
        for (o in lists) {
            val id = o.optString("Id").takeIf { it.isNotBlank() } ?: continue
            val name = o.optString("Name").ifBlank { "Playlist" }
            val entries = when (val r = call("/Playlists/$id/Items", mapOf("userId" to userId))) {
                is SubsonicResult.Failure -> return r
                is SubsonicResult.Success -> items(r.value)
            }
            val songIds = entries
                .filter { it.optString("Type") == "Audio" }
                .mapNotNull { it.optString("Id").takeIf { s -> s.isNotBlank() } }
            if (songIds.isNotEmpty()) result += SubsonicPlaylist(id, name, songIds)
        }
        return SubsonicResult.Success(result)
    }

    private fun albumQuery() = mapOf(
        "userId" to userId,
        "IncludeItemTypes" to "MusicAlbum",
        "Recursive" to "true",
        "SortBy" to "SortName",
        "Fields" to "ChildCount",
    )

    private fun songQuery() = mapOf(
        "userId" to userId,
        "IncludeItemTypes" to "Audio",
        "Recursive" to "true",
        "SortBy" to "ParentIndexNumber,IndexNumber,SortName",
        // The file's size and its kind are on its media source, not on the item.
        "Fields" to "MediaSources",
    )

    /** One item by its id, or null when there is none. */
    private suspend fun byId(id: String): SubsonicResult<JSONObject?> =
        call("/Items", mapOf("userId" to userId, "Ids" to id)).map { items(it).firstOrNull() }

    /**
     * Every item a query matches, a page at a time. The total comes back with each page, and
     * a short or empty page ends it too, so a count the server gets wrong cannot loop.
     */
    private suspend fun paged(
        query: Map<String, String>,
        onPage: suspend (done: Int, total: Int) -> Unit,
    ): SubsonicResult<List<JSONObject>> {
        val all = mutableListOf<JSONObject>()
        while (all.size < MAX_ITEMS) {
            val body = when (
                val r = call("/Items", query + mapOf("StartIndex" to all.size.toString(), "Limit" to PAGE_SIZE.toString()))
            ) {
                is SubsonicResult.Failure -> return r
                is SubsonicResult.Success -> r.value
            }
            val page = items(body)
            all += page
            val total = body.optInt("TotalRecordCount", all.size)
            onPage(all.size, maxOf(total, all.size))
            if (page.size < PAGE_SIZE || all.size >= total) break
        }
        return SubsonicResult.Success(all)
    }

    private fun items(body: JSONObject): List<JSONObject> {
        val array: JSONArray = body.optJSONArray("Items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun JSONObject.toAlbum(): SubsonicAlbum? {
        val id = optString("Id").takeIf { it.isNotBlank() } ?: return null
        val albumArtist = optJSONArray("AlbumArtists")?.optJSONObject(0)
        return SubsonicAlbum(
            id = id,
            name = optString("Name").ifBlank { UNKNOWN_ALBUM },
            artist = optString("AlbumArtist").ifBlank { albumArtist?.optString("Name").orEmpty() }.ifBlank { UNKNOWN_ARTIST },
            artistId = albumArtist?.optString("Id")?.takeIf { it.isNotBlank() },
            year = optInt("ProductionYear").takeIf { it > 0 },
            songCount = optInt("ChildCount", -1).takeIf { it >= 0 },
        )
    }

    private fun JSONObject.toSong(): SubsonicSong? {
        val id = optString("Id").takeIf { it.isNotBlank() } ?: return null
        val artists = optJSONArray("Artists")
            ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
            .orEmpty()
        val source = optJSONArray("MediaSources")?.optJSONObject(0)
        return SubsonicSong(
            id = id,
            title = optString("Name").ifBlank { "[Untitled]" },
            artist = artists.joinToString(", ").ifBlank { optString("AlbumArtist") }.ifBlank { UNKNOWN_ARTIST },
            artistId = optJSONArray("ArtistItems")?.optJSONObject(0)?.optString("Id")?.takeIf { it.isNotBlank() },
            album = optString("Album").takeIf { it.isNotBlank() },
            albumId = optString("AlbumId").takeIf { it.isNotBlank() },
            trackNumber = optInt("IndexNumber").takeIf { it > 0 },
            discNumber = optInt("ParentIndexNumber").takeIf { it > 0 },
            year = optInt("ProductionYear").takeIf { it > 0 },
            // Ticks are a ten-millionth of a second.
            durationMillis = optLong("RunTimeTicks").takeIf { it > 0 }?.let { it / 10_000L },
            sizeBytes = source?.optLong("Size")?.takeIf { it > 0 },
            suffix = (optString("Container").ifBlank { source?.optString("Container").orEmpty() })
                .substringBefore(',')
                .takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val PAGE_SIZE = 500

        /** A limit only a broken server, one that never ends its list, would reach. */
        const val MAX_ITEMS = 500_000

        const val UNKNOWN_ALBUM = "[Unknown album]"
        const val UNKNOWN_ARTIST = "[Unknown artist]"
    }
}
