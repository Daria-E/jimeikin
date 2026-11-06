package com.wanderwildwood.jimeikin.ui

import com.wanderwildwood.jimeikin.lockscreen.LockScreenControls
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import com.wanderwildwood.jimeikin.FilesCreateDocument
import com.wanderwildwood.jimeikin.FilesOpenDocument
import androidx.compose.runtime.rememberCoroutineScope
import com.wanderwildwood.jimeikin.data.LibraryBackup
import com.wanderwildwood.jimeikin.data.LibraryBackupFiles
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/**
 * One flat screen. It used to be three tabs holding one, three and five controls between
 * them, each control wrapped in a paragraph explaining itself; a screen with this little on
 * it does not need doors, and a label that needs a paragraph is the wrong label.
 *
 * Rows that only adjust come first, and the two that take something away come last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    localFolders: List<String>,
    onOpenOnlineAccountClick: () -> Unit,
    hasBatteryOptimizationExemption: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onAddFolderClick: () -> Unit,
    onRemoveFolderClick: (String) -> Unit,
    onRescanLocalMusicClick: () -> Unit,
    onNavigateToDownloadsClick: () -> Unit,
    musicServerSummary: String,
    onNavigateToMusicServerClick: () -> Unit,
    isMusicServerConnected: Boolean,
    serverMaxBitrateKbps: Int?,
    onServerMaxBitrateChange: (Int?) -> Unit,
    isRescanningLocal: Boolean,
    isIngestingLocal: Boolean,
    localScanProgress: Float,
    localIngestProgress: Float,
    localScanTotalDiscovered: Int?,
    localScanSkippedUnchanged: Int?,
    localScanIndexedNewOrUpdated: Int?,
    localScanDeletedMissing: Int?,
    localScanUnreadableFolders: Int?,
    onLibraryRestored: () -> Unit,
) {
    val context = LocalContext.current
    val onlineConnected = onlineAccountConnected()
    val scope = rememberCoroutineScope()

    // Backup and restore through the phone's own file picker, as in Medicine: the file goes
    // where the reader puts it, and a restore reads all of it before anything changes.
    val saveBackup = rememberLauncherForActivityResult(FilesCreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = LibraryBackupFiles.save(context, uri)
            Toast.makeText(
                context,
                context.getString(if (ok) R.string.backup_saved else R.string.backup_not_saved),
                if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(FilesOpenDocument(writable = false)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = when (val r = LibraryBackupFiles.restore(context, uri)) {
                is LibraryBackupFiles.Result.Done -> {
                    onLibraryRestored()
                    context.getString(
                        R.string.restore_done,
                        context.resources.getQuantityString(R.plurals.restore_done_playlists, r.playlists, r.playlists),
                        context.resources.getQuantityString(R.plurals.restore_done_stations, r.stations, r.stations),
                    )
                }
                is LibraryBackupFiles.Result.Refused -> context.getString(
                    when (r.kind) {
                        LibraryBackup.BackupException.Kind.NOT_A_BACKUP -> R.string.restore_not_ours
                        LibraryBackup.BackupException.Kind.NEWER -> R.string.restore_newer
                        LibraryBackup.BackupException.Kind.DAMAGED -> R.string.restore_damaged
                    },
                )
                LibraryBackupFiles.Result.Unreadable -> context.getString(R.string.restore_unreadable)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    PagedColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        run {
            if (localFolders.isEmpty()) {
                item {
                    TextMMD(
                        text = stringResource(R.string.settings_no_folders),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(localFolders) { folder ->
                    FolderRow(
                        path = formatDirectoryPath(
                            folder,
                            stringResource(R.string.settings_folder_phone),
                            stringResource(R.string.settings_folder_card),
                        ),
                        onRemove = { onRemoveFolderClick(folder) },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ButtonMMD(
                        onClick = onAddFolderClick,
                        modifier = Modifier.weight(1f),
                    ) { TextMMD(text = stringResource(R.string.settings_add_folder), style = MaterialTheme.typography.titleSmall) }

                    if (localFolders.isNotEmpty()) {
                        // Never faded: while a scan runs the line below says so, and a press waits.
                        OutlinedButtonMMD(
                            onClick = { if (!isRescanningLocal && !isIngestingLocal) onRescanLocalMusicClick() },
                            modifier = Modifier.weight(1f),
                        ) { TextMMD(text = stringResource(R.string.settings_rescan_folders), style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }

            // What the scan is doing, said in words. A bar that moves is a smear on this
            // panel, and the percentage was drawn twice - once as a slider nobody could
            // move, once as a number underneath it.
            val scanLine = when {
                isIngestingLocal ->
                    context.getString(R.string.settings_scan_adding, (localIngestProgress * 100f).toInt().coerceIn(0, 100))
                // Until the folders have been walked the app does not know how many songs
                // there are, so it does not know what fraction of them it has read. It used
                // to say 0% for the whole of that, which on a large card reads as stuck.
                isRescanningLocal && localScanProgress <= 0f ->
                    context.getString(R.string.settings_scan_looking)
                isRescanningLocal ->
                    context.getString(R.string.settings_scan_reading, (localScanProgress * 100f).toInt().coerceIn(0, 100))
                else -> null
            }
            if (scanLine != null) {
                item {
                    TextMMD(
                        text = scanLine,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            if (scanLine == null && localScanTotalDiscovered != null) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        TextMMD(
                            text = localScanIndexedNewOrUpdated
                                ?.takeIf { it > 0 }
                                ?.let {
                                    pluralStringResource(
                                        R.plurals.settings_scan_found_new_or_changed,
                                        localScanTotalDiscovered,
                                        localScanTotalDiscovered,
                                        it,
                                    )
                                }
                                ?: pluralStringResource(
                                    R.plurals.settings_scan_found,
                                    localScanTotalDiscovered,
                                    localScanTotalDiscovered,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (localScanDeletedMissing != null && localScanDeletedMissing > 0) {
                            TextMMD(
                                text = pluralStringResource(
                                    R.plurals.settings_scan_removed_missing,
                                    localScanDeletedMissing,
                                    localScanDeletedMissing,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (localScanUnreadableFolders != null && localScanUnreadableFolders > 0) {
                            TextMMD(
                                text = if (localScanUnreadableFolders == 1) {
                                    stringResource(R.string.settings_scan_one_folder_unreadable)
                                } else {
                                    pluralStringResource(
                                        R.plurals.settings_scan_folders_unreadable,
                                        localScanUnreadableFolders,
                                        localScanUnreadableFolders,
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        // Room, not a rule, between the folders and the switches.
        item { Spacer(Modifier.height(16.dp)) }

        // Album details from MusicBrainz, in the edition that has the lookup (src/full).
        albumDetailsSwitch(onRescan = onRescanLocalMusicClick)

        // The catalogue's own switches, in the edition that has one (src/full).
        onlineSwitches()

        item {
            // Read and written here rather than passed down: nothing else on this screen, or in
            // the app, follows it. Glance asks for it each time it draws.
            val settings = (context.applicationContext as com.wanderwildwood.jimeikin.CalmMusic).settingsManager
            var playingOnLockScreen by remember { mutableStateOf(settings.getPlayingOnLockScreenSync()) }
            SwitchRow(
                label = stringResource(R.string.settings_playing_on_lock_screen),
                checked = playingOnLockScreen,
                onCheckedChange = { on ->
                    playingOnLockScreen = on
                    settings.setPlayingOnLockScreen(on)
                    com.wanderwildwood.jimeikin.glance.GlanceProvider.changed(context)
                },
            )
        }

        item {
            // Read and written here, as the Glance switch is: only the playback service follows
            // it, and it asks at each press. A tap steps to the other choice.
            val settings = (context.applicationContext as com.wanderwildwood.jimeikin.CalmMusic).settingsManager
            var skipMode by remember { mutableStateOf(settings.getSkipButtonModeSync()) }
            ValueRow(
                label = stringResource(R.string.settings_skip_buttons),
                value = stringResource(
                    when (skipMode) {
                        com.wanderwildwood.jimeikin.playback.SkipButtonMode.SONGS -> R.string.settings_skip_buttons_songs
                        com.wanderwildwood.jimeikin.playback.SkipButtonMode.SEEK -> R.string.settings_skip_buttons_seek
                    },
                ),
                onClick = {
                    val next = when (skipMode) {
                        com.wanderwildwood.jimeikin.playback.SkipButtonMode.SONGS -> com.wanderwildwood.jimeikin.playback.SkipButtonMode.SEEK
                        com.wanderwildwood.jimeikin.playback.SkipButtonMode.SEEK -> com.wanderwildwood.jimeikin.playback.SkipButtonMode.SONGS
                    }
                    skipMode = next
                    settings.setSkipButtonMode(next)
                },
                enabled = true,
            )
        }

        item {
            ValueRow(
                label = stringResource(R.string.settings_background_playback),
                value = if (hasBatteryOptimizationExemption) {
                    stringResource(R.string.settings_background_playback_allowed)
                } else {
                    stringResource(R.string.settings_background_playback_not_allowed)
                },
                onClick = onRequestBatteryOptimizationExemption,
                enabled = !hasBatteryOptimizationExemption,
            )
        }

        item {
            // Off until the reader turns the service on in Android's own settings, which is
            // the only place it can be turned on; this row says which it is and goes there.
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            var lockScreenControlsOn by remember { mutableStateOf(LockScreenControls.isEnabled(context)) }
            // Asked for and still off on the way back: on Android 13+ that is restricted settings.
            var asked by rememberSaveable { mutableStateOf(false) }
            var restricted by remember { mutableStateOf(false) }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        lockScreenControlsOn = LockScreenControls.isEnabled(context)
                        restricted = RestrictedSettings.blocked(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Column {
                ValueRow(
                    label = stringResource(R.string.settings_lockscreen_controls),
                    value = if (lockScreenControlsOn) {
                        stringResource(R.string.settings_lockscreen_controls_on)
                    } else {
                        stringResource(R.string.settings_lockscreen_controls_off)
                    },
                    onClick = {
                        asked = true
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    enabled = true,
                )
                if (asked && !lockScreenControlsOn && restricted) RestrictedHint(Modifier.padding(horizontal = 16.dp))
            }
        }

        item {
            // Downloads is transient status about streaming rather than a place of its own;
            // it used to cost a row on a screen that existed only to hold three doors. The
            // label carries itself, so it gets no second line.
            LinkRow(label = stringResource(R.string.settings_downloads), onClick = onNavigateToDownloadsClick)
        }

        item {
            ValueRow(
                label = stringResource(R.string.settings_music_server),
                value = musicServerSummary,
                onClick = onNavigateToMusicServerClick,
                enabled = true,
            )
        }

        // Each tap steps to the next ceiling, the value shown under the name like every row
        // here: one repaint per change and no dialog. Only there while a server is.
        if (isMusicServerConnected) {
            item {
                val index = SERVER_QUALITY_STEPS.indexOf(serverMaxBitrateKbps).coerceAtLeast(0)
                ValueRow(
                    label = stringResource(R.string.library_server_quality),
                    value = serverMaxBitrateKbps
                        ?.let { stringResource(R.string.library_server_quality_capped, it) }
                        ?: stringResource(R.string.library_server_quality_original),
                    onClick = { onServerMaxBitrateChange(SERVER_QUALITY_STEPS[(index + 1) % SERVER_QUALITY_STEPS.size]) },
                    enabled = true,
                )
            }
        }

        item {
            ValueRow(
                label = stringResource(R.string.settings_save_backup),
                value = stringResource(R.string.settings_save_backup_note),
                onClick = { runCatching { saveBackup.launch("music-box-backup-${java.time.LocalDate.now()}.json") } },
                enabled = true,
            )
        }

        item {
            ArmedRow(
                label = stringResource(R.string.settings_restore),
                armedLabel = stringResource(R.string.settings_restore_armed),
                onConfirm = {
                    runCatching { restoreBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                },
            )
        }

        onlineAccountRows(onlineConnected, onOpenOnlineAccountClick)
    }
}

/** From no ceiling down to what a slow mobile connection carries without a pause. */
private val SERVER_QUALITY_STEPS: List<Int?> = listOf(null, 320, 192, 128, 96)

/** A row that only opens somewhere. It has no value to show, so it shows none. */
@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TextMMD(text = label, style = MaterialTheme.typography.titleSmall)
            if (note != null) TextMMD(text = note, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(16.dp))
        SwitchMMD(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A row shows its value, and otherwise says nothing. */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.titleSmall)
        TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The row asks, rather than a dialog: one repaint instead of two, and the question is put in
 * the place the answer belongs. It stays armed until the next other touch, Back or leaving
 * the screen, so a stray tap leaves nothing live for whoever picks the phone up next.
 */
@Composable
internal fun ArmedRow(
    label: String,
    armedLabel: String,
    onConfirm: () -> Unit,
) {
    var armed by rememberArmedFlag()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (armed) {
                    armed = false
                    onConfirm()
                } else {
                    armed = true
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextMMD(
            text = if (armed) armedLabel else label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * A folder row is its own remove button. The trash glyph beside it removed the folder on one
 * tap, unasked, and cost a column of width to do it.
 */
@Composable
private fun FolderRow(path: String, onRemove: () -> Unit) {
    var armed by rememberArmedFlag()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable {
                if (armed) {
                    armed = false
                    onRemove()
                } else {
                    armed = true
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (armed) {
            TextMMD(
                text = stringResource(R.string.settings_remove_folder_armed),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        TextMMD(text = path, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatDirectoryPath(uriString: String, phone: String, card: String): String {
    try {
        val decoded = Uri.decode(uriString)
        // A card is "<uuid>:Music", the built-in storage is "primary:Music". Both used to
        // fall through to the raw content:// uri, which told the reader nothing.
        val treeMarker = decoded.substringAfterLast("/tree/", "")
        val body = if (treeMarker.isNotEmpty()) treeMarker else decoded
        val volume = body.substringBefore(':', "")
        val path = body.substringAfter(':', "").replace("/", " > ")
        val volumeLabel = when {
            volume.equals("primary", ignoreCase = true) -> phone
            volume.isNotEmpty() -> card
            else -> return uriString
        }
        return if (path.isEmpty()) volumeLabel else "$volumeLabel > $path"
    } catch (e: Exception) {
        return uriString
    }
}
