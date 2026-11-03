package com.wanderwildwood.jimeikin.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * The backup: the playlists made in the app, with their songs in order, and the kept radio
 * stations, as one plain JSON file written and read through the system's file picker. It goes
 * where the person puts it and nowhere else. Field names are data, not interface, and stay in
 * English.
 *
 * Each song in a playlist carries its details as well as its id, so a song streamed from
 * YouTube or a music server comes back on another phone even before it is played there. A
 * song from a music folder is named by the file it was on this phone; it comes back where the
 * same folder is read again, and is passed over quietly where it is not.
 *
 * Not in the file: playlists read from .m3u files or from a music server (the scan and the
 * server's sync make them again), the songs themselves, hearts and settings. A restore leaves
 * those playlists as they are.
 *
 * [read] checks the whole file and throws a [BackupException] for anything it cannot use; only
 * then does the caller touch the database, in one transaction.
 */
object LibraryBackup {

    const val FORMAT = "jimeikin-backup"
    const val VERSION = 1

    /** Playlists the app reads from elsewhere have ids starting so: an .m3u file ([M3uImporter]), a music server ([SubsonicSync]). */
    val READ_FROM_ELSEWHERE = listOf("M3U:", "SUBSONIC:PLAYLIST:")

    /** Sources whose songs are put back into the library from the file when they are missing. */
    val SONGS_KEPT_IN_FILE = setOf("ONLINE_SEARCH", "SUBSONIC")

    data class Track(val songId: String, val position: Int, val addedAt: Long, val song: SongEntity?)
    data class Playlist(val playlist: PlaylistEntity, val tracks: List<Track>)
    data class Contents(val playlists: List<Playlist>, val stations: List<RadioStationEntity>)

    /** Why a file was refused, as a kind the screen turns into a plain sentence. */
    class BackupException(val kind: Kind, message: String = kind.name) : Exception(message) {
        enum class Kind { NOT_A_BACKUP, NEWER, DAMAGED }
    }

    /** A playlist made in the app, which the backup carries; not one read from a file or a server. */
    fun isOwn(playlistId: String) = READ_FROM_ELSEWHERE.none { playlistId.startsWith(it) }

    fun write(contents: Contents, written: Instant = Instant.now()): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("written", written.toString())
        root.put("playlists", JSONArray().apply {
            for (p in contents.playlists) {
                if (!isOwn(p.playlist.id)) continue
                put(JSONObject().apply {
                    put("id", p.playlist.id)
                    put("name", p.playlist.name)
                    put("description", p.playlist.description ?: JSONObject.NULL)
                    put("created", p.playlist.createdAt)
                    put("songs", JSONArray().apply {
                        for (t in p.tracks.sortedBy { it.position }) put(JSONObject().apply {
                            put("id", t.songId)
                            put("position", t.position)
                            put("added", t.addedAt)
                            put("song", t.song?.let { songJson(it) } ?: JSONObject.NULL)
                        })
                    })
                })
            }
        })
        root.put("stations", JSONArray().apply {
            for (s in contents.stations) put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("place", s.place)
                put("country", s.country)
                put("stream", s.streamUrl)
                put("kept", s.keptAtMillis)
            })
        })
        return root.toString(2)
    }

    /** Reads a backup whole, or throws a [BackupException]; nothing is half-read. */
    fun read(text: String): Contents {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupException(BackupException.Kind.NOT_A_BACKUP)
        }
        if (root.optString("format") != FORMAT) throw BackupException(BackupException.Kind.NOT_A_BACKUP)
        val version = root.optInt("version", -1)
        if (version > VERSION) throw BackupException(BackupException.Kind.NEWER)
        if (version < 1) throw BackupException(BackupException.Kind.DAMAGED)
        try {
            val pa = root.getJSONArray("playlists")
            val playlists = (0 until pa.length()).map { i ->
                val o = pa.getJSONObject(i)
                val id = o.getString("id")
                require(id.isNotBlank() && isOwn(id))
                val name = o.getString("name")
                require(name.isNotBlank())
                val sa = o.getJSONArray("songs")
                val tracks = (0 until sa.length()).map { k ->
                    val t = sa.getJSONObject(k)
                    val songId = t.getString("id")
                    require(songId.isNotBlank())
                    val song = if (t.isNull("song")) null else readSong(t.getJSONObject("song"))
                    require(song == null || song.id == songId)
                    Track(songId, t.getInt("position"), t.getLong("added"), song)
                }
                // One row per song in a playlist: the database would refuse a second halfway
                // through the restore, so the file is refused here instead.
                require(tracks.map { it.songId }.toSet().size == tracks.size)
                Playlist(
                    PlaylistEntity(
                        id = id,
                        name = name,
                        description = if (o.isNull("description")) null else o.getString("description"),
                        createdAt = o.getLong("created"),
                    ),
                    tracks,
                )
            }
            require(playlists.map { it.playlist.id }.toSet().size == playlists.size)
            val ra = root.getJSONArray("stations")
            val stations = (0 until ra.length()).map { i ->
                val o = ra.getJSONObject(i)
                val id = o.getString("id")
                require(id.isNotBlank())
                val stream = o.getString("stream")
                require(stream.startsWith("http://", ignoreCase = true) || stream.startsWith("https://", ignoreCase = true))
                RadioStationEntity(
                    id = id,
                    title = o.getString("title").also { require(it.isNotBlank()) },
                    place = o.getString("place"),
                    country = o.getString("country"),
                    streamUrl = stream,
                    keptAtMillis = o.getLong("kept"),
                )
            }
            require(stations.map { it.id }.toSet().size == stations.size)
            return Contents(playlists, stations)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupException.Kind.DAMAGED)
        }
    }

    /** The songs from [contents] to put back into the library where they are missing. */
    fun songsToAdd(contents: Contents): List<SongEntity> =
        contents.playlists.flatMap { p -> p.tracks.mapNotNull { it.song } }
            .filter { it.sourceType in SONGS_KEPT_IN_FILE }
            .distinctBy { it.id }

    private fun songJson(s: SongEntity) = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("artist", s.artist)
        put("album", s.album ?: JSONObject.NULL)
        put("album_id", s.albumId ?: JSONObject.NULL)
        put("disc", s.discNumber ?: JSONObject.NULL)
        put("track", s.trackNumber ?: JSONObject.NULL)
        put("duration_ms", s.durationMillis ?: JSONObject.NULL)
        put("source", s.sourceType)
        put("audio", s.audioUri)
        put("artist_id", s.artistId ?: JSONObject.NULL)
        put("year", s.releaseYear ?: JSONObject.NULL)
    }

    private fun readSong(o: JSONObject): SongEntity = SongEntity(
        id = o.getString("id").also { require(it.isNotBlank()) },
        title = o.getString("title"),
        artist = o.getString("artist"),
        album = o.stringOrNull("album"),
        albumId = o.stringOrNull("album_id"),
        discNumber = o.intOrNull("disc"),
        trackNumber = o.intOrNull("track"),
        durationMillis = o.longOrNull("duration_ms"),
        sourceType = o.getString("source").also { require(it.isNotBlank()) },
        audioUri = o.getString("audio"),
        artistId = o.stringOrNull("artist_id"),
        releaseYear = o.intOrNull("year"),
    )

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
}
