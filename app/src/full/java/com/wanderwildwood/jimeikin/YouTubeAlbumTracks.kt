package com.wanderwildwood.jimeikin

import org.json.JSONObject

/** One track of an album as YouTube Music's own album page lists it. */
data class InnertubeAlbumTrack(
    val videoId: String,
    val title: String,
    /** Null where the row leaves it out, which it does when it is the album's artist. */
    val artist: String?,
    val durationMillis: Long?,
    val trackNumber: Int?,
)

/** An album page: its name, artist and year as the page's header gives them, and its tracks. */
data class InnertubeAlbumPage(
    val title: String,
    val artist: String?,
    val year: Int?,
    val tracks: List<InnertubeAlbumTrack>,
) {
    companion object {
        val EMPTY = InnertubeAlbumPage(title = "", artist = null, year = null, tracks = emptyList())
    }
}

/** An album page's header and tracks; see [parseAlbumTracks] and [parseAlbumHeader]. */
internal fun parseAlbumPage(root: JSONObject): InnertubeAlbumPage {
    val (title, artist, year) = parseAlbumHeader(root)
    return InnertubeAlbumPage(title = title, artist = artist, year = year, tracks = parseAlbumTracks(root))
}

/**
 * The album's name, artist and year from its page's header (the first section of its first
 * tab), or from the page's microformat when the header is not there: an empty name and nulls
 * for whatever neither gives.
 */
internal fun parseAlbumHeader(root: JSONObject): Triple<String, String?, Int?> {
    fun runsText(o: JSONObject?): String? = o?.optJSONArray("runs")?.let { runs ->
        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }?.trim()?.takeIf { it.isNotEmpty() }

    val header = root.optJSONObject("contents")
        ?.optJSONObject("twoColumnBrowseResultsRenderer")
        ?.optJSONArray("tabs")
        ?.optJSONObject(0)
        ?.optJSONObject("tabRenderer")
        ?.optJSONObject("content")
        ?.optJSONObject("sectionListRenderer")
        ?.optJSONArray("contents")
        ?.optJSONObject(0)
        ?.optJSONObject("musicResponsiveHeaderRenderer")
    val micro = root.optJSONObject("microformat")?.optJSONObject("microformatDataRenderer")

    val title = runsText(header?.optJSONObject("title"))
        ?: micro?.optString("title")?.trim().orEmpty()
    // The page's own description is "Album • Artist" where the header is missing.
    val artist = runsText(header?.optJSONObject("straplineTextOne"))
        ?: micro?.optString("description")?.substringAfter(" • ", "")?.trim()?.takeIf { it.isNotEmpty() }
    // The subtitle is "Album • 1959" (or "EP • 2021"): the year is its four-digit run.
    val year = header?.optJSONObject("subtitle")?.optJSONArray("runs")?.let { runs ->
        (0 until runs.length()).firstNotNullOfOrNull { i ->
            runs.optJSONObject(i)?.optString("text")?.trim()?.takeIf { it.length == 4 }?.toIntOrNull()
        }
    }
    return Triple(title, artist, year)
}

/**
 * The tracks of an album page (a browse of its MPRE id), in the album's order.
 *
 * The page used to be put together from a search for the album's title instead, which found
 * some of its tracks in whatever order the search ranked them - an order that could change
 * from one search to the next, so the page re-sorted itself each time it was drawn again. A
 * track YouTube cannot play has no video id and is left out.
 */
internal fun parseAlbumTracks(root: JSONObject): List<InnertubeAlbumTrack> {
    val sections = root.optJSONObject("contents")
        ?.optJSONObject("twoColumnBrowseResultsRenderer")
        ?.optJSONObject("secondaryContents")
        ?.optJSONObject("sectionListRenderer")
        ?.optJSONArray("contents")
        ?: return emptyList()

    val tracks = mutableListOf<InnertubeAlbumTrack>()
    for (i in 0 until sections.length()) {
        val items = sections.optJSONObject(i)
            ?.optJSONObject("musicShelfRenderer")
            ?.optJSONArray("contents")
            ?: continue
        for (j in 0 until items.length()) {
            val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
            parseAlbumTrack(item)?.let { tracks += it }
        }
    }
    return tracks
}

private fun parseAlbumTrack(item: JSONObject): InnertubeAlbumTrack? {
    val flexColumns = item.optJSONArray("flexColumns") ?: return null

    fun columnRuns(index: Int) = flexColumns.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        ?.optJSONObject("text")
        ?.optJSONArray("runs")

    val titleRun = columnRuns(0)?.optJSONObject(0) ?: return null
    val title = titleRun.optString("text").trim()
    if (title.isEmpty()) return null

    val videoId = titleRun.optJSONObject("navigationEndpoint")
        ?.optJSONObject("watchEndpoint")
        ?.optString("videoId")
        .takeUnless { it.isNullOrBlank() }
        ?: item.optJSONObject("playlistItemData")
            ?.optString("videoId")
            .takeUnless { it.isNullOrBlank() }
        ?: return null

    // The second column is the track's artists when they differ from the album's: "A & B",
    // as runs with the joins between them. Empty when it is the album artist's own.
    val artist = columnRuns(1)?.let { runs ->
        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }?.trim()?.takeIf { it.isNotEmpty() }

    val durationText = item.optJSONArray("fixedColumns")
        ?.optJSONObject(0)
        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
        ?.optJSONObject("text")
        ?.optJSONArray("runs")
        ?.optJSONObject(0)
        ?.optString("text")

    val trackNumber = item.optJSONObject("index")
        ?.optJSONArray("runs")
        ?.optJSONObject(0)
        ?.optString("text")
        ?.trim()
        ?.toIntOrNull()

    return InnertubeAlbumTrack(
        videoId = videoId,
        title = title,
        artist = artist,
        durationMillis = durationText?.let(::durationTextToMillis),
        trackNumber = trackNumber,
    )
}

/** "4:38" or "1:02:03" to milliseconds; null for anything else. */
internal fun durationTextToMillis(text: String): Long? {
    val parts = text.trim().split(":")
    if (parts.size < 2) return null
    val numbers = parts.mapNotNull { it.toIntOrNull() }
    if (numbers.size != parts.size) return null

    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        else -> return null
    }
    return (seconds * 1000L).coerceAtLeast(0L)
}
