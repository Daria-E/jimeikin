package com.wanderwildwood.jimeikin.ui

import com.wanderwildwood.jimeikin.data.ArtistNames

/** One row of search results: a song, an album, or an artist from the library or YouTube. */
sealed class SearchResult {
    data class Song(val song: SongUiModel) : SearchResult()
    /** [kindLabel], when given, stands in for the plain "Album" before its artist. */
    data class Album(val album: AlbumUiModel, val kindLabel: String? = null) : SearchResult()
    data class LibraryArtist(val artist: ArtistUiModel) : SearchResult()
    data class OnlineArtist(val artist: OnlineArtistUiModel) : SearchResult()
}

/** Where a search looks. The reader chooses; see [searchSourcesOn]. */
enum class SearchSource { PHONE, SERVER, ONLINE }

/**
 * Which sources something counts as found in. A song kept from the server is both on the phone
 * and the server's, so it is found with either on. A YouTube download is the phone's: with
 * YouTube on, YouTube's own listing names it too and it is shown as its file.
 */
internal fun searchSourcesOf(origin: Origin?): Set<SearchSource> = when (origin) {
    Origin.SERVER -> setOf(SearchSource.SERVER)
    Origin.SERVER_DOWNLOAD -> setOf(SearchSource.PHONE, SearchSource.SERVER)
    Origin.ONLINE_SEARCH -> setOf(SearchSource.ONLINE)
    Origin.ONLINE_SEARCH_DOWNLOAD, null -> setOf(SearchSource.PHONE)
}

/**
 * The sources in force, from the ones the reader left on ([stored]) and the ones there is a
 * toggle for ([offered]). One with no toggle is not filtered on: nobody can turn it back on.
 * If none of those offered is on - only a forgotten server was - all of them are, as a search
 * of nothing is no use to anyone.
 */
fun searchSourcesOn(stored: Set<SearchSource>, offered: List<SearchSource>): Set<SearchSource> =
    if (offered.none { it in stored }) SearchSource.entries.toSet()
    else stored + (SearchSource.entries - offered.toSet())

/** [on] with [source] turned over, unless that would leave nothing to search. */
fun toggledSearchSource(on: Set<SearchSource>, source: SearchSource, offered: List<SearchSource>): Set<SearchSource> {
    val next = if (source in on) on - source else on + source
    return if (offered.any { it in next }) next else on
}

/**
 * Search results, as one list.
 *
 * Songs, albums and artists are interwoven rather than put behind tabs, ordered first by where
 * they are - the phone, then the server, then YouTube, which is the order a copy is worth
 * having in - and then by how well they match. Each song, album or artist appears once, as
 * its best copy: a song on the phone is not listed again from the server or from YouTube,
 * and one on the server is not listed again from YouTube.
 *
 * Only [sources] are looked in. An album or an artist is kept when its own mark says so or when
 * one of the songs kept is theirs: the mark names only what streams, so an artist with songs on
 * the phone and on the server is marked as the server's and is the phone's too.
 */
fun buildSearchResults(
    query: String,
    librarySongs: List<SongUiModel>,
    libraryAlbums: List<AlbumUiModel>,
    libraryArtists: List<ArtistUiModel>,
    onlineSongs: List<SongUiModel>,
    onlineAlbums: List<AlbumUiModel>,
    onlineArtists: List<OnlineArtistUiModel>,
    sources: Set<SearchSource> = SearchSource.entries.toSet(),
): List<SearchResult> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    fun lookedIn(origin: Origin?) = searchSourcesOf(origin).any { it in sources }
    val songsLookedIn = librarySongs.filter { lookedIn(originOf(it.sourceType)) }
    val albumsWithSongs = songsLookedIn.mapNotNullTo(HashSet()) { s -> s.album?.let(ArtistNames::key) }
    val artistsWithSongs = songsLookedIn.mapTo(HashSet()) { ArtistNames.artistKey(it.artist) }
    val searchesOnline = SearchSource.ONLINE in sources

    class Candidate(
        val result: SearchResult,
        val key: String,
        val place: Int,
        val match: Int,
        /** 0 for an artist named exactly what was typed, in the library or on YouTube. */
        val lead: Int,
        val kind: Int,
        val index: Int,
    )

    val candidates = mutableListOf<Candidate>()
    fun add(result: SearchResult, key: String, place: Int, match: Int?, kind: Int, exactArtist: Boolean = false) {
        // A library item that does not match is not a result.
        val m = match ?: return
        candidates += Candidate(result, key, place, m, if (exactArtist) 0 else 1, kind, candidates.size)
    }

    songsLookedIn.forEach { s ->
        add(
            SearchResult.Song(s), songKey(s), placeOf(originOf(s.sourceType)),
            match(q, s.title, s.artist, s.album), KIND_SONG,
        )
    }
    libraryAlbums.filter { lookedIn(it.origin) || ArtistNames.key(it.title) in albumsWithSongs }.forEach { a ->
        add(SearchResult.Album(a), albumKey(a), placeOf(a.origin), match(q, a.title, a.artist), KIND_ALBUM)
    }
    libraryArtists.filter { lookedIn(it.origin) || ArtistNames.artistKey(it.name) in artistsWithSongs }.forEach { a ->
        val m = match(q, a.name)
        add(SearchResult.LibraryArtist(a), artistKey(a.name), placeOf(a.origin), m, KIND_ARTIST, exactArtist = isExactArtist(q, a.name))
    }
    // YouTube has already ranked its own results, and knows better than a name match which
    // of forty albums called Moondance is the one meant. Its order is kept, the three lists
    // taken a turn at a time - a song, an album, an artist - with an artist whose name is
    // exactly what was typed put first - and, below, ahead of everything on the phone too:
    // the artist searched for is the answer, wherever the rest of the list comes from.
    fun onlineTurn(index: Int, exact: Boolean) = if (exact) -1 else index
    onlineSongs.takeIf { searchesOnline }.orEmpty().forEachIndexed { i, s ->
        add(SearchResult.Song(s), songKey(s), PLACE_ONLINE, onlineTurn(i, false), KIND_SONG)
    }
    onlineAlbums.takeIf { searchesOnline }.orEmpty().forEachIndexed { i, a ->
        add(SearchResult.Album(a), albumKey(a), PLACE_ONLINE, onlineTurn(i, false), KIND_ALBUM_ONLINE)
    }
    // An artist is offered only when the name is what was searched for. YouTube answers
    // "moondance" with whoever has a song of that name, and a row that says only "Sam Smith,
    // Artist" is not an answer to it.
    onlineArtists.takeIf { searchesOnline }.orEmpty().forEachIndexed { i, a ->
        match(q, a.name) ?: return@forEachIndexed
        val exact = isExactArtist(q, a.name)
        add(SearchResult.OnlineArtist(a), artistKey(a.name), PLACE_ONLINE, onlineTurn(i, exact), KIND_ARTIST_ONLINE, exactArtist = exact)
    }

    return candidates
        .sortedWith(compareBy({ it.lead }, { it.place }, { it.match }, { it.kind }, { it.index }))
        // Sorted best-first, so the copy kept is the best one there is.
        .distinctBy { it.key }
        .map { it.result }
}

private const val PLACE_PHONE = 0
private const val PLACE_SERVER = 1
private const val PLACE_ONLINE = 2

// In the library a match on an artist leads, then an album, then a song. In YouTube's part
// a song leads each turn: it is what a search there is usually for.
private const val KIND_ARTIST = 0
private const val KIND_ALBUM = 1
private const val KIND_SONG = 2
private const val KIND_ALBUM_ONLINE = 3
private const val KIND_ARTIST_ONLINE = 4

private fun placeOf(origin: Origin?): Int = when (origin) {
    Origin.SERVER -> PLACE_SERVER
    Origin.ONLINE_SEARCH -> PLACE_ONLINE
    // A download is on the phone: it plays with no signal, like a file put there.
    else -> PLACE_PHONE
}

/**
 * Whether an artist is the one typed: the same name however it is spelled, and with or without
 * its "The" ("beatles" is The Beatles, "bjork" is Björk). Only a name that is not empty once
 * folded counts, so a query of punctuation leads with nobody.
 */
internal fun isExactArtist(query: String, name: String): Boolean {
    val k = ArtistNames.artistKey(query)
    return k.isNotEmpty() && (ArtistNames.artistKey(name) == k || ArtistNames.key(name) == ArtistNames.key(query))
}

/**
 * How well a name answers the query: the name itself, exactly, then starting with it, then
 * containing it; then one of the other fields - an artist or an album - containing it.
 */
private fun match(query: String, name: String, vararg others: String?): Int? {
    val n = ArtistNames.key(name)
    val k = ArtistNames.key(query)
    return when {
        k.isEmpty() -> if (name.contains(query, ignoreCase = true)) 2 else null
        n == k -> 0
        n.startsWith(k) -> 1
        n.contains(k) -> 2
        others.any { it != null && ArtistNames.key(it).contains(k) } -> 3
        else -> null
    }
}

// A song is the same song under a different album - YouTube files a track under whatever
// release it found it on - so songs meet on who and what, not where.
private fun songKey(s: SongUiModel) = "song|" + ArtistNames.artistKey(s.artist) + "|" + ArtistNames.key(withoutVersion(s.title))

private fun albumKey(a: AlbumUiModel) =
    "album|" + ArtistNames.artistKey(a.artist.orEmpty()) + "|" + ArtistNames.key(withoutVersion(a.title))

private fun artistKey(name: String) = "artist|" + ArtistNames.artistKey(name)

/**
 * "Moondance (2013 Remaster)" is Moondance. Only remasters are folded: a live take, a demo
 * or a remix is a different recording and is listed on its own.
 */
private val REMASTER_IN_BRACKETS = Regex("""\s*[(\[][^)\]]*remaster[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
private val REMASTER_AFTER_DASH = Regex("""\s+-\s+[^-]*remaster.*$""", RegexOption.IGNORE_CASE)

private fun withoutVersion(title: String): String =
    title.replace(REMASTER_IN_BRACKETS, "").replace(REMASTER_AFTER_DASH, "").trim()
