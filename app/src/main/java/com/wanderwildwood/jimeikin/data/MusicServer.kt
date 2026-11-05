package com.wanderwildwood.jimeikin.data

/** The kinds of music server the app can read a library from. */
enum class ServerKind {
    /** Navidrome, Airsonic, Gonic, Nextcloud Music: anything that speaks the Subsonic API. */
    SUBSONIC,
    JELLYFIN,
    ;

    companion object {
        /** A stored name back to a kind. A server set up before there was a choice is Subsonic. */
        fun fromStored(name: String?): ServerKind = entries.firstOrNull { it.name == name } ?: SUBSONIC
    }
}

/**
 * What the library needs from a music server, whichever kind it is.
 *
 * The types it hands back carry Subsonic's name because that server came first; they say
 * nothing about Subsonic beyond that. [SubsonicSync] files whatever comes back here into the
 * library the same way, so a Jellyfin song is a row like any other server song: listed beside
 * the card's music, played from the address on its row, and kept to Music/Music Box by fetching
 * that same address.
 */
interface MusicServerClient {
    /** Whether the server is there and the login is right. */
    suspend fun ping(): SubsonicResult<Unit>

    /** Every album and every song. */
    suspend fun fetchLibrary(onProgress: suspend (SubsonicProgress) -> Unit = {}): SubsonicResult<SubsonicLibrary>

    /** One album and its songs, straight from the server. */
    suspend fun fetchAlbum(albumId: String): SubsonicResult<Pair<SubsonicAlbum, List<SubsonicSong>>>

    /** One song, or null when the server no longer has it. */
    suspend fun fetchSong(songId: String): SubsonicResult<SubsonicSong?>

    /** An artist's name and albums. */
    suspend fun fetchArtist(artistId: String): SubsonicResult<Pair<String, List<SubsonicAlbum>>>

    /** The playlists the server keeps, with the songs on each. */
    suspend fun fetchPlaylists(): SubsonicResult<List<SubsonicPlaylist>>

    /** Where a song's audio is, signed so the player can open it with nothing else. */
    fun streamUrl(songId: String, suffix: String?): String

    /** True when the password itself goes with each request, which the server page says. */
    val sendsPassword: Boolean
}
