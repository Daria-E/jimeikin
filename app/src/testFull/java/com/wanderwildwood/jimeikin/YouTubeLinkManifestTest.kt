package com.wanderwildwood.jimeikin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The full edition's link filters: which addresses another app can open in Music Box. Each
 * kind of link the filters take is one [YouTubeLink] reads, album pages and album playlists
 * among them; the store edition's manifest takes none.
 */
class YouTubeLinkManifestTest {

    private val src = listOf(File("src"), File("app/src")).first { it.isDirectory }
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun viewFilters(manifest: File): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val filters = doc.getElementsByTagName("intent-filter")
        return (0 until filters.length).map { filters.item(it) as Element }.filter { f ->
            val actions = f.getElementsByTagName("action")
            (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(ns, "name") == "android.intent.action.VIEW" }
        }
    }

    private fun data(f: Element, attr: String): List<String> {
        val d = f.getElementsByTagName("data")
        return (0 until d.length).map { (d.item(it) as Element).getAttributeNS(ns, attr) }.filter { it.isNotEmpty() }
    }

    @Test fun albumPagesOpenFromOtherApps() {
        val filters = viewFilters(File(src, "full/AndroidManifest.xml"))
        val album = filters.single { "/browse/MPREb_" in data(it, "pathPrefix") }
        // Only YouTube Music's own album pages: no other host, no other browse page.
        assertEquals(listOf("music.youtube.com"), data(album, "host"))
        assertEquals(listOf("/browse/MPREb_"), data(album, "pathPrefix"))
        assertTrue(YouTubeLink.parse("https://music.youtube.com/browse/MPREb_madeUpAlbum01")!!.isAlbum)
    }

    @Test fun albumPlaylistsOpenThroughThePlaylistFilter() {
        val filters = viewFilters(File(src, "full/AndroidManifest.xml"))
        val playlist = filters.single { "/playlist" in data(it, "path") }
        assertTrue("music.youtube.com" in data(playlist, "host"))
        assertTrue("www.youtube.com" in data(playlist, "host"))
        assertTrue(YouTubeLink.parse("https://music.youtube.com/playlist?list=OLAK5uy_madeUpAlbumList01")!!.isAlbum)
    }
}
