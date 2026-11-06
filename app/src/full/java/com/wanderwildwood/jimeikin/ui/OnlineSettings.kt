package com.wanderwildwood.jimeikin.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.YouTubeLinks
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.jimeikin.CalmMusic
import com.wanderwildwood.jimeikin.R
import com.wanderwildwood.jimeikin.youTubeAccount

/** Settings rows for YouTube Music, which only the full edition has; see [SettingsScreen]. */
internal fun LazyListScope.onlineSwitches() {
    item {
        val online = (LocalContext.current.applicationContext as CalmMusic).online
        val completeAlbums by online.completeAlbums.collectAsState()
        SwitchRow(
            label = stringResource(R.string.settings_fill_album_gaps),
            checked = completeAlbums,
            onCheckedChange = online::setCompleteAlbums,
        )
    }

    item {
        val online = (LocalContext.current.applicationContext as CalmMusic).online
        val keepPlayingSimilar by online.keepPlayingSimilar.collectAsState()
        SwitchRow(
            label = stringResource(R.string.settings_keep_playing_similar),
            checked = keepPlayingSimilar,
            onCheckedChange = online::setKeepPlayingSimilar,
        )
    }

    // Only Android's own page can let YouTube links from other apps open here (Android 12 and
    // later); this row says whether it has and goes there. Read again on the way back.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        item {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            var state by remember { mutableStateOf(YouTubeLinks.read(context)) }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) state = YouTubeLinks.read(context)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Column {
                ValueRow(
                    label = stringResource(R.string.settings_youtube_links),
                    value = stringResource(
                        when (state) {
                            YouTubeLinks.State.ON -> R.string.settings_youtube_links_on
                            YouTubeLinks.State.SOME -> R.string.settings_youtube_links_some
                            else -> R.string.settings_youtube_links_off
                        },
                    ),
                    onClick = { YouTubeLinks.open(context) },
                    enabled = true,
                )
                if (state != YouTubeLinks.State.ON) {
                    TextMMD(
                        text = stringResource(R.string.settings_youtube_links_hint),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** Whether a YouTube account is signed in, for [onlineAccountRows]. */
@Composable
internal fun onlineAccountConnected(): Boolean =
    (LocalContext.current.applicationContext as CalmMusic).youTubeAccount.cookie.collectAsState().value != null

internal fun LazyListScope.onlineAccountRows(connected: Boolean, onConnectClick: () -> Unit) {
    item {
        ValueRow(
            label = stringResource(R.string.settings_youtube_account),
            value = if (connected) stringResource(R.string.settings_youtube_connected) else stringResource(R.string.settings_youtube_not_connected),
            onClick = if (connected) null else onConnectClick,
            enabled = !connected,
        )
    }

    if (connected) {
        item {
            val account = (LocalContext.current.applicationContext as CalmMusic).youTubeAccount
            ArmedRow(
                label = stringResource(R.string.settings_disconnect_account),
                armedLabel = stringResource(R.string.settings_disconnect_account_armed),
                onConfirm = account::clear,
            )
        }
    }
}

/** The empty library's third way in: YouTube's settings; see [LibraryOnboardingEmptyState]. */
@Composable
internal fun OnlineSetUpButton(onClick: () -> Unit) {
    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))

    com.mudita.mmd.components.buttons.OutlinedButtonMMD(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        onClick = onClick,
    ) {
        com.mudita.mmd.components.text.TextMMD(
            text = stringResource(R.string.library_empty_set_up_youtube),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}
