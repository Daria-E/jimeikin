package com.wanderwildwood.jimeikin

/**
 * The parts of opening a YouTube playlist link that need no network: where the playlist is
 * read from, the order its songs join the queue in, and how its names are tidied. The
 * reading itself is NewPipe's playlist extractor, in [YouTubeMusic.openLink].
 */
object YouTubePlaylist {

    /** The most songs one link puts in the queue; a longer playlist stops here. */
    const val MAX_SONGS = 500

    /**
     * The address NewPipe reads the playlist from: always www.youtube.com (a YouTube Music
     * playlist has the same id there). A link that also names a song keeps it, because a mix
     * (a list id starting "RD") is made from the song and cannot be read without it.
     */
    fun address(link: YouTubeLink): String? {
        val list = link.playlistId ?: return null
        val video = link.videoId
        return if (video != null) "https://www.youtube.com/watch?v=$video&list=$list"
        else "https://www.youtube.com/playlist?list=$list"
    }

    /**
     * What joins the queue behind [startId], the song the link named, which is already
     * playing: the playlist's songs after it, then the ones before it, so the whole playlist
     * plays once round from there. The song itself is never added again, and a song the
     * playlist holds twice is added once. With no song named, or one the playlist does not
     * hold, it is the whole playlist in its own order.
     */
    fun <T> rest(startId: String?, playlist: List<T>, id: (T) -> String): List<T> {
        val at = if (startId == null) -1 else playlist.indexOfFirst { id(it) == startId }
        val ordered = if (at < 0) playlist else playlist.subList(at + 1, playlist.size) + playlist.subList(0, at)
        val seen = HashSet<String>()
        if (startId != null) seen += startId
        return ordered.filter { seen.add(id(it)) }
    }

    /** The first [max] songs of [more], leaving out any already in [have] or met earlier. */
    fun <T> newOnes(have: Collection<String>, more: List<T>, max: Int, id: (T) -> String): List<T> {
        val seen = HashSet(have)
        return more.filter { seen.add(id(it)) }.take(max.coerceAtLeast(0))
    }

    /** A YouTube Music song's channel is "Artist - Topic"; the artist is the part before. */
    fun artist(uploader: String?): String =
        uploader.orEmpty().trim().removeSuffix(" - Topic").trim()

    /**
     * The id an album row carries for a playlist or album pasted into search as a link: the
     * list id behind a mark no YouTube Music album or library album id starts with, so the
     * album page reads it as the playlist it is (see [listIdOf]).
     */
    fun linkListId(list: String): String = LINK_LIST_PREFIX + list

    /** The list id inside an id made by [linkListId], or null for any other album id. */
    fun listIdOf(albumId: String): String? =
        albumId.takeIf { it.startsWith(LINK_LIST_PREFIX) }?.removePrefix(LINK_LIST_PREFIX)?.takeIf { it.isNotEmpty() }

    private const val LINK_LIST_PREFIX = "link-list:"

    /**
     * A playlist's name as a row shows it, or null when it has none. YouTube names an album's
     * playlist "Album - Its Title"; the word in front is dropped.
     */
    fun title(name: String?, isAlbum: Boolean): String? {
        val trimmed = name.orEmpty().trim()
        val shown = if (isAlbum) trimmed.removePrefix("Album - ").trim() else trimmed
        return shown.takeIf { it.isNotEmpty() }
    }

    /** The 11-character video id at the end of a watch address NewPipe lists, or null. */
    fun videoIdOf(url: String?): String? = YouTubeLink.parse(url)?.videoId
}
