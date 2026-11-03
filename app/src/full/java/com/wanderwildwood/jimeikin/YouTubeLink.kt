package com.wanderwildwood.jimeikin

import java.net.URI
import java.net.URLDecoder

/**
 * What a YouTube or YouTube Music web address names, for a link another app hands over
 * (a tap on the link, or Share, or pasted into search). Only the things Music Box can use are
 * read out: a video id, a playlist id, and a YouTube Music album's id (its browse/MPREb_ page).
 * Nothing else in the address is kept: the ids alone go on, to the player and to YouTube for the
 * playlist's or album's songs, so a link cannot point the app anywhere but YouTube.
 */
data class YouTubeLink(val videoId: String?, val playlistId: String?, val albumId: String? = null) {

    /**
     * An album rather than a playlist: a YouTube Music album page, or the playlist YouTube keeps
     * for every album (its id starts "OLAK5uy_").
     */
    val isAlbum: Boolean get() = albumId != null || playlistId?.startsWith(ALBUM_LIST_PREFIX) == true

    companion object {
        private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
        private const val SHORT_HOST = "youtu.be"
        private const val MUSIC_HOST = "music.youtube.com"
        private const val ALBUM_LIST_PREFIX = "OLAK5uy_"
        private val ALBUM_ID = Regex("MPREb_[A-Za-z0-9_-]{1,64}")
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,64}")
        private val ADDRESS = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

        /** The first web address in shared text, without the punctuation a sentence puts after it. */
        fun addressIn(text: String?): String? =
            text?.let { ADDRESS.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', '"', '\'') }

        /** What [address] names, or null when it is not a YouTube address naming a video, a playlist or an album. */
        fun parse(address: String?): YouTubeLink? {
            val uri = runCatching { URI(address?.trim().orEmpty()) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            val query = queryOf(uri.rawQuery)
            val list = query["list"]?.takeIf { PLAYLIST_ID.matches(it) }

            // An album's own page on YouTube Music: music.youtube.com/browse/MPREb_...
            if (host == MUSIC_HOST && segments.firstOrNull() == "browse") {
                val album = segments.getOrNull(1)?.takeIf { ALBUM_ID.matches(it) } ?: return null
                return YouTubeLink(null, null, album)
            }

            val video: String? = when {
                host == SHORT_HOST -> segments.firstOrNull()
                host in HOSTS && segments.firstOrNull() == "watch" -> query["v"]
                host in HOSTS && segments.firstOrNull() == "playlist" -> null
                else -> return null
            }?.takeIf { VIDEO_ID.matches(it) }

            if (host in HOSTS && segments.firstOrNull() == "playlist" && list == null) return null
            if (video == null && list == null) return null
            return YouTubeLink(video, list)
        }

        private fun queryOf(raw: String?): Map<String, String> {
            val found = LinkedHashMap<String, String>()
            raw?.split('&')?.forEach { pair ->
                val at = pair.indexOf('=')
                if (at <= 0) return@forEach
                val key = decode(pair.substring(0, at))
                if (key !in found) found[key] = decode(pair.substring(at + 1))
            }
            return found
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
