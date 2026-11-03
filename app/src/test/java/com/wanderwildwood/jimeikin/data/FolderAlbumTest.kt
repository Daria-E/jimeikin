package com.wanderwildwood.jimeikin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderAlbumTest {

    @Test
    fun theFolderAFileSitsInNamesItsAlbum() {
        assertEquals(FolderAlbum("Glass Orchard", null), folderAlbumOf("Mara Quill/Glass Orchard/03 Lantern.flac"))
        assertEquals(FolderAlbum("Glass Orchard", null), folderAlbumOf("Glass Orchard/03 Lantern.flac"))
    }

    @Test
    fun aFileAtTheTopOfTheChosenFolderHasNone() {
        assertNull(folderAlbumOf("Lantern.mp3"))
    }

    @Test
    fun aDiscFolderIsTheAlbumAboveItWithItsNumber() {
        assertEquals(FolderAlbum("Glass Orchard", 2), folderAlbumOf("Mara Quill/Glass Orchard/CD 2/01 Tide.mp3"))
        assertEquals(FolderAlbum("Glass Orchard", 1), folderAlbumOf("Glass Orchard/Disc1/01 Tide.mp3"))
        assertEquals(FolderAlbum("Glass Orchard", 3), folderAlbumOf("Glass Orchard/disk-3/01 Tide.mp3"))
        // A disc folder at the top names nothing.
        assertNull(folderAlbumOf("CD 1/01 Tide.mp3"))
    }

    @Test
    fun aFolderThatOnlySaysMusicNamesNoAlbum() {
        assertNull(folderAlbumOf("Music/Lantern.mp3"))
        assertNull(folderAlbumOf("Download/Lantern.mp3"))
        assertNull(folderAlbumOf("Music/Music Box/Lantern.m4a"))
        assertNull(folderAlbumOf("Unknown Album/Lantern.m4a"))
    }

    @Test
    fun placeholderTagsAreNoTags() {
        assertTrue(isPlaceholderTag("Unknown Album"))
        assertTrue(isPlaceholderTag(" <unknown> "))
        assertTrue(isPlaceholderTag("UNKNOWN ARTIST"))
        assertFalse(isPlaceholderTag("Unknown Pleasures"))
        assertFalse(isPlaceholderTag("Untitled"))
    }
}
