package com.wanderwildwood.jimeikin

import java.net.URI
import java.net.URLDecoder

/**
 * What a YouTube or YouTube Music web address names, for a link another app hands over
 * (a tap on the link, or Share). Only the two things Music Box can use are read out: a video
 * id and a playlist id. Nothing else in the address is kept: the ids alone go on, to the player
 * and to YouTube for the playlist's songs, so a link cannot point the app anywhere but YouTube.
 */
data class YouTubeLink(val videoId: String?, val playlistId: String?) {

    companion object {
        private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
        private const val SHORT_HOST = "youtu.be"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val PLAYLIST_ID = Regex("[A-Za-z0-9_-]{2,64}")
        private val ADDRESS = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

        /** The first web address in shared text, without the punctuation a sentence puts after it. */
        fun addressIn(text: String?): String? =
            text?.let { ADDRESS.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', '"', '\'') }

        /** What [address] names, or null when it is not a YouTube address naming a video or a playlist. */
        fun parse(address: String?): YouTubeLink? {
            val uri = runCatching { URI(address?.trim().orEmpty()) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            val query = queryOf(uri.rawQuery)
            val list = query["list"]?.takeIf { PLAYLIST_ID.matches(it) }

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
