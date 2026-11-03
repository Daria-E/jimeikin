package com.wanderwildwood.jimeikin.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class LibraryBackupTest {

    private val online = SongEntity("aB3_dEf-9Xy", "Harbour Lights", "Ada Whitlock", "Tide Tables", "MPRE1", 1, 3, 201_000, "ONLINE_SEARCH", "aB3_dEf-9Xy", "UC1", 2024)
    private val server = SongEntity("SUBSONIC:77", "Low Field", "Tomas Reyes", null, null, null, null, null, "SUBSONIC", "77")
    private val local = SongEntity("content://music/1", "Kitchen Radio", "Ada Whitlock", null, null, null, null, null, "LOCAL_FILE", "content://music/1")

    private val contents = LibraryBackup.Contents(
        playlists = listOf(
            LibraryBackup.Playlist(
                PlaylistEntity("p1", "Morning", "for the walk", 1_700_000_000_000),
                listOf(
                    LibraryBackup.Track(local.id, 2, 10, local),
                    LibraryBackup.Track(online.id, 0, 11, online),
                    LibraryBackup.Track(server.id, 1, 12, server),
                    LibraryBackup.Track("gone-song", 3, 13, null),
                ),
            ),
            LibraryBackup.Playlist(PlaylistEntity("p2", "Empty", null, 5), emptyList()),
        ),
        stations = listOf(
            RadioStationEntity("rg1", "Valley FM", "Springfield", "Nowhere", "https://stream.example.org/valley", 99),
        ),
    )

    private fun roundTrip(c: LibraryBackup.Contents = contents) =
        LibraryBackup.read(LibraryBackup.write(c, Instant.EPOCH))

    private fun refused(text: String): LibraryBackup.BackupException.Kind {
        try {
            LibraryBackup.read(text)
        } catch (e: LibraryBackup.BackupException) {
            return e.kind
        }
        fail("read a file it should have refused")
        error("unreachable")
    }

    @Test fun everythingComesBack() {
        val back = roundTrip()
        assertEquals(2, back.playlists.size)
        assertEquals(contents.playlists[0].playlist, back.playlists[0].playlist)
        assertEquals(contents.playlists[1].playlist, back.playlists[1].playlist)
        assertEquals(contents.stations, back.stations)
        // Written in playlist order, every detail of each song kept.
        assertEquals(listOf(online.id, server.id, local.id, "gone-song"), back.playlists[0].tracks.map { it.songId })
        assertEquals(listOf(0, 1, 2, 3), back.playlists[0].tracks.map { it.position })
        assertEquals(online, back.playlists[0].tracks[0].song)
        assertEquals(null, back.playlists[0].tracks[3].song)
    }

    @Test fun onlyStreamedSongsAreAddedBack() {
        val added = LibraryBackup.songsToAdd(roundTrip())
        assertEquals(setOf(online.id, server.id), added.map { it.id }.toSet())
    }

    @Test fun playlistsReadFromElsewhereAreLeftOut() {
        val withOthers = contents.copy(
            playlists = contents.playlists + listOf(
                LibraryBackup.Playlist(PlaylistEntity("M3U:Music/road.m3u", "road", null, 1), emptyList()),
                LibraryBackup.Playlist(PlaylistEntity("SUBSONIC:PLAYLIST:9", "Server list", null, 1), emptyList()),
            ),
        )
        assertEquals(listOf("p1", "p2"), roundTrip(withOthers).playlists.map { it.playlist.id })
        assertFalse(LibraryBackup.isOwn("M3U:x"))
        assertFalse(LibraryBackup.isOwn("SUBSONIC:PLAYLIST:x"))
        assertTrue(LibraryBackup.isOwn("0b5c-uuid"))
    }

    @Test fun anotherFileIsNotABackup() {
        assertEquals(LibraryBackup.BackupException.Kind.NOT_A_BACKUP, refused("not json at all"))
        assertEquals(LibraryBackup.BackupException.Kind.NOT_A_BACKUP, refused("""{"format":"fukuyaku-backup","version":1}"""))
    }

    @Test fun aNewerBackupIsRefused() {
        val json = JSONObject(LibraryBackup.write(contents)).put("version", LibraryBackup.VERSION + 1)
        assertEquals(LibraryBackup.BackupException.Kind.NEWER, refused(json.toString()))
    }

    @Test fun aDamagedBackupIsRefusedWhole() {
        fun damaged(change: (JSONObject) -> Unit): LibraryBackup.BackupException.Kind {
            val json = JSONObject(LibraryBackup.write(contents))
            change(json)
            return refused(json.toString())
        }
        val kind = LibraryBackup.BackupException.Kind.DAMAGED
        assertEquals(kind, damaged { it.remove("stations") })
        assertEquals(kind, damaged { it.getJSONArray("playlists").getJSONObject(0).put("name", " ") })
        assertEquals(kind, damaged { it.getJSONArray("playlists").getJSONObject(1).put("id", "p1") })
        assertEquals(kind, damaged { it.getJSONArray("playlists").getJSONObject(1).put("id", "M3U:x") })
        assertEquals(kind, damaged {
            val songs = it.getJSONArray("playlists").getJSONObject(0).getJSONArray("songs")
            songs.put(JSONObject(songs.getJSONObject(0).toString()))
        })
        assertEquals(kind, damaged {
            it.getJSONArray("playlists").getJSONObject(0).getJSONArray("songs").getJSONObject(0)
                .getJSONObject("song").put("id", "another")
        })
        assertEquals(kind, damaged { it.getJSONArray("stations").getJSONObject(0).put("stream", "file:///sdcard/x") })
        assertEquals(kind, damaged { it.put("version", 0) })
    }

    @Test fun nothingKeptIsStillABackup() {
        val back = roundTrip(LibraryBackup.Contents(emptyList(), emptyList()))
        assertTrue(back.playlists.isEmpty())
        assertTrue(back.stations.isEmpty())
    }
}
