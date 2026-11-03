package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/**
 * Where something came from, when that was not a file put on the phone.
 *
 * A file in a chosen folder carries no mark - it is most of any library and needs nothing
 * said about it. Anything that streams, from the music server or online, carries a plain
 * cloud: it is not on this phone and plays only with a signal. A download carries the
 * available-offline tick instead, and no cloud, as it plays anywhere; it is still worth
 * telling apart from a file the reader put there themselves, because it is the one that can
 * be removed and fetched again. Which place it is from is said in words, to a screen reader.
 */
enum class Origin(val streams: Boolean) {
    SERVER(streams = true),
    ONLINE_SEARCH(streams = true),
    SERVER_DOWNLOAD(streams = false),
    ONLINE_SEARCH_DOWNLOAD(streams = false),
}

fun originOf(sourceType: String?): Origin? = when (sourceType) {
    "SUBSONIC" -> Origin.SERVER
    "ONLINE_SEARCH" -> Origin.ONLINE_SEARCH
    "SUBSONIC_DOWNLOAD" -> Origin.SERVER_DOWNLOAD
    "ONLINE_SEARCH_DOWNLOAD" -> Origin.ONLINE_SEARCH_DOWNLOAD
    else -> null
}

/**
 * For an album or an artist. Anything that streams outranks anything downloaded - the mark
 * that matters is whether it will play without a signal - and the server outranks YouTube.
 */
fun originOf(sourceTypes: Collection<String>): Origin? {
    val origins = sourceTypes.mapNotNull { originOf(it) }.toSet()
    return listOf(Origin.SERVER, Origin.ONLINE_SEARCH, Origin.SERVER_DOWNLOAD, Origin.ONLINE_SEARCH_DOWNLOAD)
        .firstOrNull { it in origins }
}

@Composable
fun OriginMark(origin: Origin) {
    val description = stringResource(
        when (origin) {
            Origin.SERVER -> R.string.origin_server
            Origin.ONLINE_SEARCH -> R.string.origin_online
            Origin.SERVER_DOWNLOAD -> R.string.origin_server_download
            Origin.ONLINE_SEARCH_DOWNLOAD -> R.string.origin_online_download
        },
    )
    Icon(
        imageVector = if (origin.streams) Icons.Cloud else Icons.OfflinePin,
        contentDescription = description,
        modifier = Modifier.size(16.dp),
    )
}

/** A row's second line, with the marks before it when there are any: a heart, then where it is from. */
@Composable
fun SubtitleLine(text: String, origin: Origin?, hearted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (hearted) {
            Icon(
                imageVector = Icons.Heart,
                contentDescription = stringResource(R.string.hearted_mark),
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(if (origin != null) 4.dp else 8.dp))
        }
        if (origin != null) {
            OriginMark(origin)
            Spacer(modifier = Modifier.width(8.dp))
        }
        TextMMD(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
