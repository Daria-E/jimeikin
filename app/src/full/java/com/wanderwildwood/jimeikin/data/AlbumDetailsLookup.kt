package com.wanderwildwood.jimeikin.data

import android.content.Context

/** The full edition's album lookup: MusicBrainz, when the reader has switched it on. */
object AlbumDetailsLookup {
    fun gaps(context: Context): AlbumGaps = MusicBrainz.matches(context)

    suspend fun lookUp(context: Context, isStillOn: () -> Boolean): Int = MusicBrainz.lookUp(context, isStillOn)
}
