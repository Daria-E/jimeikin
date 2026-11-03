package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Backup and restore against a file the person picked: the Android half of [LibraryBackup]. */
object LibraryBackupFiles {

    sealed interface Result {
        data class Done(val playlists: Int, val stations: Int) : Result
        data class Refused(val kind: LibraryBackup.BackupException.Kind) : Result
        data object Unreadable : Result
    }

    /** Writes the backup to [uri]; false, with the file removed if it was left empty, when that fails. */
    suspend fun save(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val db = CalmMusicDatabase.getDatabase(context)
            val playlistDao = db.playlistDao()
            val songDao = db.songDao()
            val playlists = playlistDao.getAllPlaylists()
                .filter { LibraryBackup.isOwn(it.id) }
                .map { p ->
                    LibraryBackup.Playlist(
                        p,
                        playlistDao.getTracksForPlaylist(p.id).map { t ->
                            LibraryBackup.Track(t.songId, t.position, t.addedAt, songDao.getSongById(t.songId))
                        },
                    )
                }
            val text = LibraryBackup.write(LibraryBackup.Contents(playlists, db.radioStationDao().getAll()))
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("no stream")
        }.isSuccess
        if (!ok) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
        ok
    }

    /**
     * Reads the whole file first; only a file that passes replaces anything, and the database
     * replaces in one transaction. A file over 20 MB is refused unread: thousands of playlists
     * are far smaller.
     */
    suspend fun restore(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { readCapped(it) }
        }.getOrNull() ?: return@withContext Result.Unreadable
        if (bytes.size > MAX_BYTES) return@withContext Result.Refused(LibraryBackup.BackupException.Kind.NOT_A_BACKUP)
        val contents = try {
            LibraryBackup.read(String(bytes, Charsets.UTF_8))
        } catch (e: LibraryBackup.BackupException) {
            return@withContext Result.Refused(e.kind)
        }
        val db = CalmMusicDatabase.getDatabase(context)
        runCatching {
            db.withTransaction {
                val playlistDao = db.playlistDao()
                val stationDao = db.radioStationDao()
                playlistDao.deleteOwnPlaylistTracks()
                playlistDao.deleteOwnPlaylists()
                db.songDao().insertIfAbsent(LibraryBackup.songsToAdd(contents))
                playlistDao.insertPlaylists(contents.playlists.map { it.playlist })
                playlistDao.insertTracks(
                    contents.playlists.flatMap { p ->
                        p.tracks.map { PlaylistTrackEntity(p.playlist.id, it.songId, it.position, it.addedAt) }
                    },
                )
                stationDao.forgetAll()
                stationDao.keepAll(contents.stations)
            }
        }.onFailure { return@withContext Result.Unreadable }
        Result.Done(contents.playlists.size, contents.stations.size)
    }

    /** At most MAX_BYTES + 1 bytes, so a file that is too big can be told from one that just fits. */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= MAX_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private const val MAX_BYTES = 20 * 1024 * 1024
}
