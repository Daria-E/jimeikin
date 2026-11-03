package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R
import com.wanderwildwood.jimeikin.data.MusicBrainz

/** Settings' MusicBrainz row; the store edition has no lookup and no row. */
internal fun LazyListScope.albumDetailsSwitch(onRescan: () -> Unit) {
    item {
        // Album details from MusicBrainz, for albums on the phone whose tags leave gaps.
        // Off to begin with; the note says what is sent and where.
        val context = LocalContext.current
        val settings = (context.applicationContext as com.wanderwildwood.jimeikin.CalmMusic).settingsManager
        var lookUp by remember { mutableStateOf(settings.getAlbumDetailsLookupSync()) }
        val status by MusicBrainz.status.collectAsState()
        Column {
            SwitchRow(
                label = stringResource(R.string.settings_musicbrainz),
                checked = lookUp,
                onCheckedChange = { on ->
                    lookUp = on
                    settings.setAlbumDetailsLookup(on)
                    if (!on) {
                        // What it found goes, and the next scan reads every file's own
                        // tags again, so nothing it filled in stays behind.
                        MusicBrainz.forget(context)
                        settings.setLocalAlbumKeyVersion(0)
                    }
                    onRescan()
                },
                note = stringResource(R.string.settings_musicbrainz_note),
            )
            val line = when {
                !lookUp -> null
                status.running -> stringResource(R.string.settings_musicbrainz_running, status.done, status.total)
                status.matched > 0 -> pluralStringResource(R.plurals.settings_musicbrainz_matched, status.matched, status.matched)
                else -> null
            }
            if (line != null) {
                TextMMD(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
