package com.wanderwildwood.jimeikin

import android.content.Context
import androidx.core.content.edit
import com.wanderwildwood.jimeikin.data.DownloadUpgrade
import com.wanderwildwood.jimeikin.data.Secrets
import com.wanderwildwood.jimeikin.data.SongEntity
import com.wanderwildwood.jimeikin.data.YouTubeAudioFile
import com.wanderwildwood.jimeikin.ui.AlbumUiModel
import com.wanderwildwood.jimeikin.ui.OnlineArtistUiModel
import com.wanderwildwood.jimeikin.ui.SongUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The full edition's catalogue: YouTube Music, through InnerTube with NewPipe behind it. */
fun createOnlineMusic(app: CalmMusic): OnlineMusic = YouTubeMusic(app)

/** The YouTube parts, for the code in this edition that reaches them directly. */
private val CalmMusic.youTube: YouTubeMusic get() = online as YouTubeMusic
val CalmMusic.youTubeInnertubeClient: YouTubeMusicInnertubeClient get() = youTube.innertube
val CalmMusic.youTubeStreamResolver: YouTubeStreamResolver get() = youTube.streamResolver
val CalmMusic.youTubeCopies: YouTubeCopies get() = youTube.copies
val CalmMusic.youTubeAccount: YouTubeMusic.Account get() = youTube.account

class YouTubeMusic(private val app: CalmMusic) : OnlineMusic {

    // The same settings file, and the same keys, as before the editions were split.
    private val prefs = app.getSharedPreferences("calmmusic_settings", Context.MODE_PRIVATE)

    /** The YouTube account signed in on the settings page, if any; its cookie is sealed like a password. */
    inner class Account {
        private val _cookie = MutableStateFlow(load())
        val cookie: StateFlow<String?> = _cookie.asStateFlow()

        private fun load(): String? {
            val stored = prefs.getString(KEY_YOUTUBE_ACCOUNT_COOKIE, null) ?: return null
            val cookie = Secrets.open(stored) ?: return null
            if (!Secrets.isSealed(stored)) {
                Secrets.seal(cookie)?.let { sealed -> prefs.edit { putString(KEY_YOUTUBE_ACCOUNT_COOKIE, sealed) } }
            }
            return cookie
        }

        fun set(cookie: String) {
            prefs.edit { putString(KEY_YOUTUBE_ACCOUNT_COOKIE, Secrets.seal(cookie)) }
            _cookie.value = cookie
        }

        fun clear() {
            prefs.edit { remove(KEY_YOUTUBE_ACCOUNT_COOKIE) }
            _cookie.value = null
        }
    }

    val account = Account()

    val innertube: YouTubeMusicInnertubeClient by lazy {
        YouTubeMusicInnertubeClientImpl(OkHttpClient.Builder().build()) { account.cookie.value }
    }

    val streamResolver: YouTubeStreamResolver by lazy { YouTubeStreamResolver() }

    private val precache: YouTubePrecacheManager by lazy { YouTubePrecacheManager(app) }

    /** Which YouTube songs are already downloaded, and as which file. */
    val copies: YouTubeCopies by lazy { YouTubeCopies(File(app.filesDir, "youtube-downloads.txt")) }

    override val available: Boolean = true

    override val recorded: StateFlow<Map<String, String>> get() = copies.recorded
    override fun recordCopy(onlineId: String, songId: String) = copies.record(onlineId, songId)
    override fun renameCopy(oldSongId: String, newSongId: String) = copies.rename(oldSongId, newSongId)
    override fun forgetCopy(songId: String) = copies.forget(songId)
    override fun copyOf(song: SongUiModel, downloads: List<SongUiModel>): SongUiModel? = copies.copyOf(song, downloads)
    override fun withCopies(songs: List<SongUiModel>, downloads: List<SongUiModel>): List<SongUiModel> =
        copies.withCopies(songs, downloads)
    override fun leftToDownload(songs: List<SongUiModel>, downloads: List<SongUiModel>, isUnderway: (String) -> Boolean) =
        copies.leftToDownload(songs, downloads, isUnderway)
    override fun copyIn(song: SongUiModel, recorded: Map<String, String>, downloads: List<SongUiModel>): SongUiModel? =
        YouTubeCopyMatch.copyOf(song, recorded, downloads)
    override fun withCopiesIn(songs: List<SongUiModel>, recorded: Map<String, String>, downloads: List<SongUiModel>): List<SongUiModel> =
        YouTubeCopyMatch.withCopies(songs, recorded, downloads)
    override fun inAlbumOrder(songs: List<SongUiModel>): List<SongUiModel> = YouTubeCopyMatch.inAlbumOrder(songs)
    override fun idInComment(comment: String?): String? = YouTubeAudioFile.videoIdOf(comment)

    override suspend fun search(query: String): OnlineSearch {
        val songResults = innertube.searchSongs(query = query, limit = 25)
        val albumResults = innertube.searchAlbums(query = query, limit = 25)
        val artistResults = innertube.searchArtists(query = query, limit = 25)
        return OnlineSearch(
            songs = songResults.map { it.toSong(trackNumber = null) },
            albums = albumResults.map { it.toAlbum(fallbackArtist = null) },
            artists = artistResults.map { OnlineArtistUiModel(browseId = it.browseId, name = it.name) },
        )
    }

    // Each album's listing, as first fetched. A page is drawn again whenever the library
    // changes - each time a download lands - and fetching the listing afresh each time could
    // bring it back in another order, or with a track more or less.
    private val albumListings = ConcurrentHashMap<String, List<SongUiModel>>()

    override suspend fun albumSongs(album: AlbumUiModel): List<SongUiModel> {
        val cacheKey = listingKey(album)
        albumListings[cacheKey]?.let { return it }
        // A playlist or album pasted into search as a link is read as the playlist it is, and
        // never looked for by its name.
        val songs = YouTubePlaylist.listIdOf(album.id)?.let { linkListSongs(it, album) }
            ?: albumTracks(album).ifEmpty { searchAlbumSongs(album) }
        if (songs.isNotEmpty()) albumListings[cacheKey] = songs
        return songs
    }

    private fun listingKey(album: AlbumUiModel): String =
        listOf(album.id, YouTubeCopyMatch.key(album.title), YouTubeCopyMatch.key(album.artist.orEmpty()))
            .joinToString("|")

    /** The album's own page on YouTube Music, when it came from there and so has its id. */
    private suspend fun albumTracks(album: AlbumUiModel): List<SongUiModel> {
        if (!album.id.startsWith("MPRE")) return emptyList()
        val tracks = withContext(Dispatchers.IO) {
            runCatching { innertube.getAlbumTracks(album.id) }.getOrDefault(emptyList())
        }
        return songsOf(tracks, album)
    }

    /**
     * A playlist read whole (up to [YouTubePlaylist.MAX_SONGS]), numbered in its own order so an
     * album page keeps it. Its songs carry the album's name only when it is an album's playlist.
     */
    private suspend fun linkListSongs(list: String, album: AlbumUiModel): List<SongUiModel> {
        val link = YouTubeLink(null, list)
        val address = YouTubePlaylist.address(link) ?: return emptyList()
        val first = runCatching { firstPlaylistPage(address) }.getOrNull() ?: return emptyList()
        val (page, next) = first
        val all = if (next == null) page
        else page + morePlaylistPages(address, next, page.map { it.id }, YouTubePlaylist.MAX_SONGS - page.size)
        return all.mapIndexed { index, song ->
            song.copy(
                trackNumber = index + 1,
                discNumber = 1,
                album = if (link.isAlbum) album.title else song.album,
            )
        }
    }

    private fun songsOf(tracks: List<InnertubeAlbumTrack>, album: AlbumUiModel): List<SongUiModel> =
        tracks.mapIndexed { index, track ->
            SongUiModel(
                id = track.videoId,
                title = track.title,
                artist = track.artist ?: album.artist.orEmpty(),
                durationText = formatDurationMillis(track.durationMillis),
                durationMillis = track.durationMillis,
                trackNumber = track.trackNumber ?: (index + 1),
                discNumber = 1,
                sourceType = "ONLINE_SEARCH",
                audioUri = track.videoId,
                album = album.title,
            )
        }

    private suspend fun searchAlbumSongs(album: AlbumUiModel): List<SongUiModel> {
        return withContext(Dispatchers.IO) {
            val termBuilder = StringBuilder().apply {
                append(album.title)
                val artist = album.artist
                if (!artist.isNullOrBlank()) {
                    append(' ')
                    append(artist)
                }
            }

            val results = innertube.searchSongs(
                query = termBuilder.toString(),
                limit = 50,
            )

            val targetAlbumName = album.title.trim()

            val filtered = results.filter { result ->
                val resultAlbum = result.album?.trim().orEmpty()
                if (resultAlbum.isEmpty()) return@filter false

                resultAlbum.equals(targetAlbumName, ignoreCase = true) ||
                        resultAlbum.contains(targetAlbumName, ignoreCase = true) ||
                        targetAlbumName.contains(resultAlbum, ignoreCase = true)
            }

            val songsForAlbum = if (filtered.isNotEmpty()) filtered else results

            songsForAlbum.mapIndexed { index, item ->
                SongUiModel(
                    id = item.videoId,
                    title = item.title,
                    artist = item.artist,
                    durationText = formatDurationMillis(item.durationMillis),
                    durationMillis = item.durationMillis,
                    trackNumber = index + 1,
                    discNumber = 1,
                    sourceType = "ONLINE_SEARCH",
                    audioUri = item.videoId,
                    album = album.title,
                )
            }
        }
    }

    override suspend fun findArtist(name: String): OnlineArtistUiModel? {
        val key = com.wanderwildwood.jimeikin.data.ArtistNames.key(name)
        return runCatching { innertube.searchArtists(name, limit = 5) }
            .getOrDefault(emptyList())
            .firstOrNull { com.wanderwildwood.jimeikin.data.ArtistNames.key(it.name) == key }
            ?.let { OnlineArtistUiModel(browseId = it.browseId, name = it.name) }
    }

    override suspend fun artistPage(browseId: String, downloads: List<SongUiModel>): OnlineArtistPage {
        return withContext(Dispatchers.IO) {
            val page = innertube.getArtistPage(browseId)
            val songs = page.songs.mapIndexed { index, item -> item.toSong(trackNumber = index + 1, discNumber = 1) }
            OnlineArtistPage(
                name = page.name,
                // The ones already downloaded as their files, so their menus offer to remove
                // the download rather than to fetch it again.
                songs = copies.withCopies(songs, downloads),
                albums = page.albums.map { it.toAlbum(fallbackArtist = page.name) },
                singles = page.singles.map { it.toAlbum(fallbackArtist = page.name) },
            )
        }
    }

    override fun similarSeed(seed: SongUiModel): String? = SimilarSongs.seedVideoId(seed, copies.recorded.value)

    override suspend fun similar(seedId: String, queue: () -> List<SongUiModel>, count: Int): List<SongUiModel> {
        val found = innertube.getUpNext(seedId)
        return SimilarSongs.pick(found, queue(), copies.recorded.value, seedId, count).map { it.toSong(trackNumber = null) }
    }

    private fun linkIn(text: String): YouTubeLink? = YouTubeLink.parse(YouTubeLink.addressIn(text) ?: text.trim())

    override fun isLink(text: String): Boolean = linkIn(text) != null

    override suspend fun searchLink(text: String): LinkSearch {
        val link = linkIn(text) ?: return LinkSearch.Cannot(R.string.link_unreadable)
        return try {
            searchFor(link)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            LinkSearch.Cannot(if (link.isAlbum) R.string.link_album_unreadable else R.string.link_unreadable)
        }
    }

    private suspend fun searchFor(link: YouTubeLink): LinkSearch {
        link.albumId?.let { id ->
            val album = albumPage(id) ?: return LinkSearch.Cannot(R.string.link_album_unreadable)
            return LinkSearch.Album(album.first, album.second.size, isPlaylist = false)
        }
        // A song, also when the link names the playlist it was played from: the song is what
        // the link was copied for.
        link.videoId?.let { return LinkSearch.Song(linkSong(it)) }

        val list = link.playlistId ?: return LinkSearch.Cannot(R.string.link_unreadable)
        val unreadable = if (link.isAlbum) R.string.link_album_unreadable else R.string.link_playlist_unreadable
        val address = YouTubePlaylist.address(YouTubeLink(null, list)) ?: return LinkSearch.Cannot(unreadable)
        val info = runCatching {
            withContext(Dispatchers.IO) {
                YouTubeStreamResolver.ensureInitialized(OkHttpClient.Builder().build())
                PlaylistInfo.getInfo(ServiceList.YouTube, address)
            }
        }.getOrNull() ?: return LinkSearch.Cannot(unreadable)
        val firstPage = info.relatedItems.count { YouTubePlaylist.videoIdOf(it.url) != null }
        val count = info.streamCount.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: firstPage
        if (count <= 0) return LinkSearch.Cannot(unreadable)
        val album = AlbumUiModel(
            id = YouTubePlaylist.linkListId(list),
            title = YouTubePlaylist.title(info.name, link.isAlbum)
                ?: app.getString(if (link.isAlbum) R.string.link_untitled_album else R.string.link_untitled_playlist),
            artist = YouTubePlaylist.artist(info.uploaderName).takeIf { it.isNotEmpty() },
            sourceType = "ONLINE_SEARCH",
        )
        return LinkSearch.Album(album, count, isPlaylist = !link.isAlbum)
    }

    /** An album's page as an album, with its songs, which are kept as its listing. Null when it cannot be read. */
    private suspend fun albumPage(id: String): Pair<AlbumUiModel, List<SongUiModel>>? {
        val page = withContext(Dispatchers.IO) { runCatching { innertube.getAlbumPage(id) }.getOrNull() }
        if (page == null || page.tracks.isEmpty()) return null
        val album = AlbumUiModel(
            id = id,
            title = page.title.ifBlank { app.getString(R.string.link_untitled_album) },
            artist = page.artist,
            sourceType = "ONLINE_SEARCH",
            releaseYear = page.year,
        )
        val songs = songsOf(page.tracks, album)
        albumListings[listingKey(album)] = songs
        return album to songs
    }

    /**
     * The song a link names, titled as YouTube Music lists it. If the lookup fails the song
     * still plays, under a plain title.
     */
    private suspend fun linkSong(videoId: String): SongUiModel {
        // The song is the first thing YouTube lists after itself; that gives its title.
        val found = runCatching { innertube.getUpNext(videoId).firstOrNull { it.videoId == videoId } }.getOrNull()
        return found?.toSong(trackNumber = null) ?: SongUiModel(
            id = videoId,
            title = app.getString(R.string.link_untitled),
            artist = "",
            sourceType = "ONLINE_SEARCH",
            audioUri = videoId,
        )
    }

    override suspend fun openLink(text: String): LinkOutcome? {
        val link = linkIn(text) ?: return null
        // An album's page plays the album, in its own order.
        link.albumId?.let { id ->
            val songs = albumPage(id)?.second ?: return LinkOutcome.Cannot(R.string.link_album_unreadable)
            return LinkOutcome.Play(songs)
        }
        val videoId = link.videoId
        val address = YouTubePlaylist.address(link)

        if (videoId == null) {
            // A playlist alone: its first page plays at once, the rest of it follows.
            val list = address ?: return null
            val first = runCatching { firstPlaylistPage(list) }.getOrNull()
                ?: return LinkOutcome.Cannot(R.string.link_playlist_unreadable)
            val songs = YouTubePlaylist.rest(null, first.first) { it.id }
            if (songs.isEmpty()) return LinkOutcome.Cannot(R.string.link_playlist_unreadable)
            val next = first.second
            val more: (suspend () -> LinkMore)? = next?.let { page ->
                {
                    val rest = morePlaylistPages(list, page, songs.map { it.id }, YouTubePlaylist.MAX_SONGS - songs.size)
                    LinkMore(rest, queuedMessage(rest.size))
                }
            }
            return LinkOutcome.Play(songs, more = more)
        }

        val song = linkSong(videoId)
        if (address == null) return LinkOutcome.Play(listOf(song))
        // A song in a playlist: the song plays now, and the playlist is read behind it, from
        // the song onwards and then round to the start.
        return LinkOutcome.Play(listOf(song), more = {
            val first = runCatching { firstPlaylistPage(address) }.getOrNull()
            if (first == null) {
                LinkMore(emptyList(), app.getString(R.string.link_playlist_one_song))
            } else {
                val (page, next) = first
                val all = if (next == null) page
                else page + morePlaylistPages(address, next, page.map { it.id }, YouTubePlaylist.MAX_SONGS - page.size)
                val rest = YouTubePlaylist.rest(videoId, all) { it.id }
                LinkMore(rest, queuedMessage(rest.size))
            }
        })
    }

    private fun queuedMessage(count: Int): String? =
        if (count <= 0) null else app.resources.getQuantityString(R.plurals.link_playlist_queued, count, count)

    /** A playlist's first page of songs, and where the next page is (null when there is none). */
    private suspend fun firstPlaylistPage(address: String): Pair<List<SongUiModel>, Page?> =
        withContext(Dispatchers.IO) {
            YouTubeStreamResolver.ensureInitialized(OkHttpClient.Builder().build())
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, address)
            val songs = info.relatedItems.mapNotNull { it.toLinkSong() }
                .take(YouTubePlaylist.MAX_SONGS)
            songs to info.nextPage?.takeIf { info.hasNextPage() && songs.size < YouTubePlaylist.MAX_SONGS }
        }

    /**
     * The playlist's pages from [page] on, up to [room] more songs, none already in [have].
     * A page that fails ends the reading there, keeping what came before it.
     */
    private suspend fun morePlaylistPages(address: String, page: Page, have: List<String>, room: Int): List<SongUiModel> =
        withContext(Dispatchers.IO) {
            val found = ArrayList<SongUiModel>()
            val ids = have.toMutableSet()
            var next: Page? = page
            while (next != null && found.size < room) {
                val items = runCatching { PlaylistInfo.getMoreItems(ServiceList.YouTube, address, next) }.getOrNull() ?: break
                val fresh = YouTubePlaylist.newOnes(ids, items.items.mapNotNull { it.toLinkSong() }, room - found.size) { it.id }
                found += fresh
                ids += fresh.map { it.id }
                next = items.nextPage?.takeIf { items.hasNextPage() }
            }
            found
        }

    private fun StreamInfoItem.toLinkSong(): SongUiModel? {
        val id = YouTubePlaylist.videoIdOf(url) ?: return null
        val millis = duration.takeIf { it > 0 }?.let { it * 1000L }
        return SongUiModel(
            id = id,
            title = name?.takeIf { it.isNotBlank() } ?: app.getString(R.string.link_untitled),
            artist = YouTubePlaylist.artist(uploaderName),
            durationText = formatDurationMillis(millis),
            durationMillis = millis,
            sourceType = "ONLINE_SEARCH",
            audioUri = id,
        )
    }

    override fun precacheSearch(ids: List<String>) = precache.precacheSearchResults(ids)
    override fun clearSearchWindow() = precache.clearSearchWindow()
    override fun updateQueueWindow(ids: List<String>) = precache.updateQueueWindow(ids)

    override val streamHeaders: Map<String, String> = mapOf(
        "Cookie" to BYPASS_COOKIES,
        "Referer" to "https://www.youtube.com/",
    )

    override fun isCatalogueHost(host: String): Boolean =
        host.endsWith("youtube.com") || host.endsWith("youtu.be") || host.endsWith("googlevideo.com")

    override fun playAddress(id: String): String = "https://www.youtube.com/watch?v=$id"

    override fun cacheKey(id: String): String = id

    override fun unplayableMessage(errorClassName: String, message: String): Int? {
        val isNewPipeContentNotAvailable =
            errorClassName == "org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException" ||
                message.contains("page needs to be reloaded", ignoreCase = true) ||
                message.contains("ContentNotAvailable", ignoreCase = true)
        return if (isNewPipeContentNotAvailable) R.string.main_youtube_cannot_play else null
    }

    override fun resolveStream(id: String): Pair<String, String?> {
        val now = System.currentTimeMillis()
        precache.getCachedWithLabel(id, now)?.let { return it }
        val (resolvedUrl, resolverLabel) = runBlocking(Dispatchers.IO) {
            try {
                innertube.getBestAudioUrl(id) to "Innertube"
            } catch (_: Exception) {
                streamResolver.getBestAudioUrl(id) to "NewPipe"
            }
        }
        precache.putUrl(id, resolvedUrl, resolverLabel, now)
        return resolvedUrl to resolverLabel
    }

    override suspend fun download(item: DownloadItem, targetDir: File, client: OkHttpClient, onProgress: (Float) -> Unit): Boolean {
        val song = SongUiModel(
            id = item.songId,
            title = item.title,
            artist = item.artist,
            durationMillis = item.durationMillis,
            discNumber = item.discNumber,
            trackNumber = item.trackNumber,
            sourceType = "ONLINE_SEARCH",
            audioUri = item.audioUri,
            album = item.album,
        )
        return performYouTubeDownloadInternal(
            app = app,
            song = song,
            albumArtist = item.albumArtist,
            targetDir = targetDir,
            context = app,
            client = client,
            onProgress = onProgress,
        )
    }

    override suspend fun upgradeCandidates(inUse: () -> Set<String>): List<SongEntity> = DownloadUpgrade.candidates(app, inUse)

    override suspend fun upgrade(
        songId: String,
        client: OkHttpClient,
        inUse: () -> Set<String>,
        onProgress: (Float) -> Unit,
    ): Pair<DownloadState, String?> =
        when (val result = DownloadUpgrade.upgrade(app, songId, client, inUse, onProgress)) {
            DownloadUpgrade.Result.Done -> DownloadState.DOWNLOADED to null
            is DownloadUpgrade.Result.Failed -> DownloadState.FAILED to result.reason
            DownloadUpgrade.Result.Later -> DownloadState.NOT_FINISHED to null
        }

    private val _completeAlbums = MutableStateFlow(prefs.getBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, false))
    override val completeAlbums: StateFlow<Boolean> = _completeAlbums.asStateFlow()
    override fun setCompleteAlbums(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, enabled) }
        _completeAlbums.value = enabled
    }

    /** Off unless chosen: when a YouTube song is the last in the queue, add what YouTube plays after it. */
    private val _keepPlayingSimilar = MutableStateFlow(prefs.getBoolean(KEY_KEEP_PLAYING_SIMILAR, false))
    override val keepPlayingSimilar: StateFlow<Boolean> = _keepPlayingSimilar.asStateFlow()
    override fun setKeepPlayingSimilar(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_KEEP_PLAYING_SIMILAR, enabled) }
        _keepPlayingSimilar.value = enabled
    }

    private fun InnertubeSongResult.toSong(trackNumber: Int?, discNumber: Int? = null) = SongUiModel(
        id = videoId,
        title = title,
        artist = artist,
        durationText = formatDurationMillis(durationMillis),
        durationMillis = durationMillis,
        trackNumber = trackNumber,
        discNumber = discNumber,
        sourceType = "ONLINE_SEARCH",
        audioUri = videoId,
        album = album,
    )

    private fun InnertubeAlbumResult.toAlbum(fallbackArtist: String?) = AlbumUiModel(
        id = albumId,
        title = title,
        artist = artist ?: fallbackArtist,
        sourceType = "ONLINE_SEARCH",
        releaseYear = year,
    )

    private companion object {
        const val KEY_COMPLETE_ALBUMS_WITH_YOUTUBE = "complete_albums_with_youtube"
        const val KEY_KEEP_PLAYING_SIMILAR = "keep_playing_similar"
        const val KEY_YOUTUBE_ACCOUNT_COOKIE = "youtube_account_cookie"
        const val BYPASS_COOKIES = "SOCS=CAI; VISITOR_INFO1_LIVE=i7Sm6Qgj0lE; CONSENT=YES+cb.20210328-17-p0.en+FX+475"
    }
}
