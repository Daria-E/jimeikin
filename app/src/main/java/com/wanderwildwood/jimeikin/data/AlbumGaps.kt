package com.wanderwildwood.jimeikin.data

/**
 * Details an online lookup found for albums whose tags leave gaps, laid over the library as a
 * scan takes it in: who an untagged album is by, and each song's disc, track and year where its
 * tags have none. The full edition fills it from MusicBrainz ([AlbumDetailsLookup]); the store
 * edition has no lookup, and so nothing to fill.
 */
interface AlbumGaps {
    fun fill(song: SongEntity): SongEntity
    fun albumArtist(albumId: String): String?
}

object NoAlbumGaps : AlbumGaps {
    override fun fill(song: SongEntity): SongEntity = song
    override fun albumArtist(albumId: String): String? = null
}
