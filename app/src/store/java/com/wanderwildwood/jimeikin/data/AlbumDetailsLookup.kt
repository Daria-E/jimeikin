package com.wanderwildwood.jimeikin.data

import android.content.Context

// The store edition looks nothing up online for the library; see [AlbumGaps].

object AlbumDetailsLookup {
    @Suppress("UNUSED_PARAMETER")
    fun gaps(context: Context): AlbumGaps = NoAlbumGaps

    @Suppress("UNUSED_PARAMETER")
    suspend fun lookUp(context: Context, isStillOn: () -> Boolean): Int = 0
}
