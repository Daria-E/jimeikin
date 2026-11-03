package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wanderwildwood.jimeikin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Album details from MusicBrainz (musicbrainz.org, open data), for albums on the phone whose
 * tags leave something out: who the album is by, its year, or its track order.
 *
 * Off until the reader turns it on in Settings. Then, after a library scan, each album that
 * lacks one of those is looked up once: its title and artist are searched for, and a release
 * is taken only when its title is the album's and most of the album's song titles are on it
 * (with lengths that agree, where both are known). What it gives fills gaps only: a tag the
 * file has always wins, and the files themselves are never written to. An album with no match
 * stays exactly as tagged. Results are kept in the app's own storage, so an album is asked
 * about once, and again only when its songs change.
 *
 * MusicBrainz asks for no more than one request a second from one address, and a user agent
 * that names the app and a way to reach its author:
 * https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting. Requests here are a little over a
 * second apart, one at a time, and a pass stops at the first refusal or failure.
 */
object MusicBrainz {

    private const val BASE = "https://musicbrainz.org/ws/2"
    private const val SPACING_MS = 1_100L
    private const val FILE = "musicbrainz.json"

    private val userAgent: String =
        (if (BuildConfig.FLAVOR == "store") "MusicBoxK/" else "MusicBox/") +
            BuildConfig.VERSION_NAME + " ( https://github.com/wanderwildwood/jimeikin ; email@wanderthe.dev )"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val requestLock = Mutex()
    private var lastRequestAt = 0L

    /** Where a pass is, for the Settings row. */
    data class Status(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val matched: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val passLock = Mutex()

    /** Thrown when MusicBrainz refuses or cannot be reached: the pass stops there. */
    private class Unavailable(message: String) : Exception(message)

    private suspend fun get(url: String): String = requestLock.withLock {
        val wait = lastRequestAt + SPACING_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Unavailable("HTTP ${response.code}")
                    response.body?.string() ?: throw Unavailable("empty answer")
                }
            }
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            throw Unavailable(e.toString())
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** What is kept, read for the library scan; empty when the lookup is off. */
    fun matches(context: Context): MusicBrainzMatches = MusicBrainzMatches.read(File(context.filesDir, FILE))

    /** Turning the lookup off forgets everything it found. */
    fun forget(context: Context) {
        File(context.filesDir, FILE).delete()
        _status.value = Status()
    }

    /**
     * Looks up every album on the phone that needs it and has not been asked about. Returns how
     * many albums were newly filled in; the caller rescans the library when that is not zero.
     */
    suspend fun lookUp(context: Context, isStillOn: () -> Boolean): Int {
        if (!passLock.tryLock()) return 0
        try {
            if (!isOnline(context)) return 0
            val file = File(context.filesDir, FILE)
            val db = CalmMusicDatabase.getDatabase(context)
            val songs = db.songDao().getSongsBySourceType("LOCAL_FILE")
            val albumRows = db.albumDao().getAllAlbums().filter { it.sourceType == "LOCAL_FILE" }.associateBy { it.id }
            val albums = MusicBrainzMatcher.albumsToLookUp(songs, albumRows)
            var kept = withContext(Dispatchers.IO) { MusicBrainzMatches.read(file) }
            val todo = albums.filter { kept.entry(it.albumId)?.signature != it.signature }
            var matched = 0
            _status.value = Status(running = true, done = 0, total = todo.size, matched = kept.matchedCount())
            for ((i, album) in todo.withIndex()) {
                if (!isStillOn()) break
                val match = try {
                    find(album)
                } catch (e: Unavailable) {
                    android.util.Log.w("MusicBrainz", "lookup stopped: ${e.message}")
                    break
                }
                kept = kept.with(album.albumId, MusicBrainzMatches.Entry(album.signature, match))
                if (match != null) matched++
                withContext(Dispatchers.IO) { kept.write(file) }
                _status.value = Status(running = true, done = i + 1, total = todo.size, matched = kept.matchedCount())
            }
            _status.value = _status.value.copy(running = false)
            return matched
        } finally {
            passLock.unlock()
        }
    }

    private suspend fun find(album: MusicBrainzMatcher.LocalAlbum): MusicBrainzMatcher.AlbumMatch? {
        val search = get("$BASE/release/?fmt=json&limit=10&query=" + enc(MusicBrainzMatcher.query(album)))
        val candidates = MusicBrainzMatcher.pick(album, MusicBrainzParse.candidates(search))
        for (candidate in candidates) {
            val body = get("$BASE/release/${enc(candidate.id)}?fmt=json&inc=recordings+artist-credits")
            val release = MusicBrainzParse.release(body) ?: continue
            MusicBrainzMatcher.fit(album, release)?.let { return it }
        }
        return null
    }
}

/** MusicBrainz's JSON, read into what the matcher needs. */
object MusicBrainzParse {

    data class Candidate(val id: String, val title: String, val artist: String, val score: Int, val trackCount: Int)
    data class Track(val disc: Int, val position: Int, val title: String, val lengthMillis: Long?)
    data class Release(val id: String, val title: String, val artist: String, val year: Int?, val tracks: List<Track>)

    private fun credit(o: JSONObject): String {
        val credits = o.optJSONArray("artist-credit") ?: return ""
        return buildString {
            for (i in 0 until credits.length()) {
                val c = credits.optJSONObject(i) ?: continue
                append(c.optString("name").ifEmpty { c.optJSONObject("artist")?.optString("name").orEmpty() })
                append(c.optString("joinphrase"))
            }
        }.trim()
    }

    fun candidates(json: String): List<Candidate> {
        val releases = try { JSONObject(json).optJSONArray("releases") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until releases.length()).mapNotNull { i ->
            val r = releases.optJSONObject(i) ?: return@mapNotNull null
            val id = r.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Candidate(id, r.optString("title"), credit(r), r.optInt("score", 0), r.optInt("track-count", 0))
        }
    }

    fun release(json: String): Release? {
        val r = try { JSONObject(json) } catch (_: Exception) { return null }
        val id = r.optString("id").takeIf { it.isNotBlank() } ?: return null
        val media: JSONArray = r.optJSONArray("media") ?: JSONArray()
        val tracks = mutableListOf<Track>()
        for (m in 0 until media.length()) {
            val medium = media.optJSONObject(m) ?: continue
            val disc = medium.optInt("position", m + 1)
            val list = medium.optJSONArray("tracks") ?: continue
            for (t in 0 until list.length()) {
                val track = list.optJSONObject(t) ?: continue
                val title = track.optString("title").ifEmpty { track.optJSONObject("recording")?.optString("title").orEmpty() }
                if (title.isBlank()) continue
                val length = if (track.isNull("length")) null else track.optLong("length", 0L).takeIf { it > 0 }
                tracks += Track(disc, track.optInt("position", t + 1), title, length)
            }
        }
        val year = r.optString("date").take(4).toIntOrNull()
        return Release(id, r.optString("title"), credit(r), year, tracks)
    }
}

/** Which albums to ask about, what to ask, and whether an answer is the album. Pure, for tests. */
object MusicBrainzMatcher {

    data class LocalTrack(val songId: String, val title: String, val durationMillis: Long?)

    data class LocalAlbum(
        val albumId: String,
        val title: String,
        /** Who most of it is by, when one artist is; null for a mix such as a compilation. */
        val artist: String?,
        val tracks: List<LocalTrack>,
    ) {
        /** Changes when the album's songs do, which is when it is worth asking again. */
        val signature: String
            get() = (ArtistNames.key(title) + "|" + tracks.map { it.songId }.sorted().joinToString("|"))
                .hashCode().toUInt().toString(16)
    }

    /** What a match gives: who the album is by, its year, and each song's disc and track. */
    data class AlbumMatch(
        val releaseId: String,
        val artist: String,
        val year: Int?,
        val tracks: Map<String, Pair<Int, Int>>,
    )

    /**
     * Albums on the phone that are missing who they are by (no Album Artist tag), a year, or a
     * track number on any song. A fully tagged album is never asked about.
     */
    fun albumsToLookUp(songs: List<SongEntity>, albumRows: Map<String, AlbumEntity>): List<LocalAlbum> =
        songs.filter { it.sourceType == "LOCAL_FILE" && !it.albumId.isNullOrBlank() && !it.album.isNullOrBlank() }
            .groupBy { it.albumId!! }
            .mapNotNull { (albumId, own) ->
                val untagged = albumId.startsWith(UNTAGGED_ALBUM_PREFIX)
                val needs = untagged || own.any { it.trackNumber == null } || own.all { it.releaseYear == null }
                if (!needs) return@mapNotNull null
                val title = ArtistNames.preferred(own.mapNotNull { it.album })
                val leads = own.map { ArtistNames.lead(it.artist) }.filter { it.isNotBlank() }
                val byKey = leads.groupBy { ArtistNames.artistKey(it) }
                val top = byKey.values.maxByOrNull { it.size }
                val artist = albumRows[albumId]?.artist?.takeIf { !untagged && it.isNotBlank() }
                    ?: top?.takeIf { it.size * 2 > own.size }?.let { ArtistNames.preferred(it) }
                if (artist == null && own.size < 3) return@mapNotNull null
                LocalAlbum(albumId, title, artist, own.map { LocalTrack(it.id, it.title, it.durationMillis) })
            }

    private fun phrase(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The search: the title, and the artist when there is one; for a mix, the number of tracks. */
    fun query(album: LocalAlbum): String =
        "release:" + phrase(album.title) +
            (album.artist?.let { " AND artist:" + phrase(it) } ?: " AND tracks:${album.tracks.size}")

    /**
     * The search results worth opening, best first, at most two: the title must be the album's
     * and, when the album is one artist's, so must the artist. A release with as many tracks as
     * the album comes first.
     */
    fun pick(album: LocalAlbum, candidates: List<MusicBrainzParse.Candidate>): List<MusicBrainzParse.Candidate> {
        val titleKey = ArtistNames.key(plainTitle(album.title))
        val artistKey = album.artist?.let(ArtistNames::artistKey)
        return candidates
            .filter { it.score >= 80 }
            .filter { ArtistNames.key(plainTitle(it.title)) == titleKey }
            .filter { c -> artistKey == null || ArtistNames.collaborators(c.artist).any { ArtistNames.artistKey(it) == artistKey } || ArtistNames.artistKey(c.artist) == artistKey }
            .filter { it.trackCount == 0 || it.trackCount >= (album.tracks.size + 1) / 2 }
            .sortedWith(compareBy({ if (it.trackCount == album.tracks.size) 0 else 1 }, { -it.score }))
            .take(2)
    }

    /** How far apart two lengths of the same recording may be. */
    private const val LENGTH_SLACK_MS = 10_000L

    /**
     * The album's songs placed on a release, or null when it is not this album: at least three
     * in five of the songs must be on it by title, and where both lengths are known they must
     * agree.
     */
    fun fit(album: LocalAlbum, release: MusicBrainzParse.Release): AlbumMatch? {
        if (album.tracks.isEmpty() || release.tracks.isEmpty()) return null
        val free = release.tracks.toMutableList()
        val placed = mutableMapOf<String, Pair<Int, Int>>()
        for (song in album.tracks) {
            val key = ArtistNames.key(plainTitle(song.title))
            if (key.isEmpty()) continue
            val same = free.filter { ArtistNames.key(plainTitle(it.title)) == key }
            val best = same
                .filter { t -> song.durationMillis == null || t.lengthMillis == null || kotlin.math.abs(song.durationMillis - t.lengthMillis) <= LENGTH_SLACK_MS }
                .minByOrNull { t -> if (song.durationMillis == null || t.lengthMillis == null) Long.MAX_VALUE else kotlin.math.abs(song.durationMillis - t.lengthMillis) }
                ?: continue
            free.remove(best)
            placed[song.songId] = best.disc to best.position
        }
        if (placed.size * 5 < album.tracks.size * 3) return null
        return AlbumMatch(release.id, release.artist, release.year, placed)
    }

    private val VERSION_IN_BRACKETS = Regex("""\s*[(\[][^)\]]*(?:remaster|deluxe|edition|bonus)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val VERSION_AFTER_DASH = Regex("""\s+-\s+[^-]*remaster.*$""", RegexOption.IGNORE_CASE)

    /** "Moondance (2013 Remaster)" and "Glass Orchard (Deluxe Edition)" are the plain title. */
    fun plainTitle(title: String): String =
        title.replace(VERSION_IN_BRACKETS, "").replace(VERSION_AFTER_DASH, "").trim().ifEmpty { title }
}

/** What the lookups found, kept in the app's own files folder. */
class MusicBrainzMatches private constructor(private val entries: Map<String, Entry>) : AlbumGaps {

    /** One album asked about: its [signature] then, and the match, or null for none. */
    data class Entry(val signature: String, val match: MusicBrainzMatcher.AlbumMatch?)

    fun entry(albumId: String): Entry? = entries[albumId]

    fun with(albumId: String, entry: Entry) = MusicBrainzMatches(entries + (albumId to entry))

    fun matchedCount(): Int = entries.values.count { it.match != null }

    fun isEmpty(): Boolean = entries.values.none { it.match != null }

    /** Who an album no tag names is by, as MusicBrainz has it. A tagged album keeps its tag. */
    override fun albumArtist(albumId: String): String? =
        if (albumId.startsWith(UNTAGGED_ALBUM_PREFIX)) entries[albumId]?.match?.artist?.takeIf { it.isNotBlank() } else null

    /** A song with its gaps filled: disc, track and year, each only where its tags had none. */
    override fun fill(song: SongEntity): SongEntity {
        val match = song.albumId?.let { entries[it]?.match } ?: return song
        val place = match.tracks[song.id]
        return song.copy(
            trackNumber = song.trackNumber ?: place?.second,
            discNumber = song.discNumber ?: place?.first.takeIf { song.trackNumber == null },
            releaseYear = song.releaseYear ?: match.year,
        )
    }

    fun toJson(): String {
        val albums = JSONObject()
        entries.forEach { (id, e) ->
            val o = JSONObject().put("sig", e.signature)
            e.match?.let { m ->
                o.put("release", m.releaseId).put("artist", m.artist)
                m.year?.let { o.put("year", it) }
                val tracks = JSONObject()
                m.tracks.forEach { (songId, p) -> tracks.put(songId, JSONArray().put(p.first).put(p.second)) }
                o.put("tracks", tracks)
            }
            albums.put(id, o)
        }
        return JSONObject().put("v", 1).put("albums", albums).toString()
    }

    fun write(file: File) {
        val tmp = File(file.parentFile, file.name + ".new")
        tmp.writeText(toJson())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        val EMPTY = MusicBrainzMatches(emptyMap())

        fun read(file: File): MusicBrainzMatches =
            if (!file.exists()) EMPTY else try { parse(file.readText()) } catch (_: Exception) { EMPTY }

        fun parse(json: String): MusicBrainzMatches {
            val albums = JSONObject(json).optJSONObject("albums") ?: return EMPTY
            val out = mutableMapOf<String, Entry>()
            for (id in albums.keys()) {
                val o = albums.optJSONObject(id) ?: continue
                val release = o.optString("release").takeIf { it.isNotBlank() }
                val match = release?.let {
                    val tracks = mutableMapOf<String, Pair<Int, Int>>()
                    o.optJSONObject("tracks")?.let { t ->
                        for (songId in t.keys()) {
                            val a = t.optJSONArray(songId) ?: continue
                            tracks[songId] = a.optInt(0, 1) to a.optInt(1, 0)
                        }
                    }
                    MusicBrainzMatcher.AlbumMatch(it, o.optString("artist"), if (o.has("year")) o.optInt("year") else null, tracks)
                }
                out[id] = Entry(o.optString("sig"), match)
            }
            return MusicBrainzMatches(out)
        }
    }
}
