package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBrowserTest {

    // A recorded-shaped response with made-up stations.
    private val json = """
    [
      {"stationuuid":"aaaa-1","name":"\tHarbour Jazz ","url":"http://example.org/a.m3u","url_resolved":"https://stream.example.org/harbour","state":"Oregon","country":"Nowhereland","lastcheckok":1,"hls":0},
      {"stationuuid":"aaaa-2","name":"Dead Air","url_resolved":"https://stream.example.org/dead","country":"Nowhereland","lastcheckok":0},
      {"stationuuid":"aaaa-3","name":"No Stream","url_resolved":"","country":"Nowhereland","lastcheckok":1},
      {"stationuuid":"aaaa-4","name":"Odd Scheme","url_resolved":"rtsp://example.org/x","lastcheckok":1},
      {"stationuuid":"aaaa-1","name":"Harbour Jazz again","url_resolved":"https://stream.example.org/harbour2","lastcheckok":1},
      {"stationuuid":"aaaa-5","name":"Hill Radio","url_resolved":"http://stream.example.org/hill","state":"","country":"Elsewhere","lastcheckok":1}
    ]
    """

    @Test
    fun mapsUsableStationsOnly() {
        val out = RadioBrowser.parseStations(json)
        assertEquals(listOf("RB:aaaa-1", "RB:aaaa-5"), out.map { it.id })
        assertEquals("Harbour Jazz", out[0].title)
        assertEquals("https://stream.example.org/harbour", out[0].streamUrl)
        assertEquals("Oregon", out[0].place)
        assertEquals("Elsewhere", out[1].country)
    }

    @Test
    fun garbageIsEmpty() {
        assertTrue(RadioBrowser.parseStations("<html>").isEmpty())
        assertTrue(RadioBrowser.parseStations("{}").isEmpty())
    }

    private val none = RadioSearchResults(emptyList(), emptyList())
    private val garden = RadioSearchResults(emptyList(), listOf(RadioChannel("g1", "Garden", "", "")))
    private val browser = RadioSearchResults(emptyList(), listOf(RadioChannel("RB:1", "Browser", "", "", "https://x/y")))

    @Test
    fun gardenAnswersFirstAndBrowserIsNotAsked() = runBlocking {
        var asked = false
        val r = RadioBrowser.searchWithFallback("jazz", { garden }, { asked = true; browser })
        assertEquals("g1", r.channels[0].id)
        assertTrue(!asked)
    }

    @Test
    fun emptyOrErrorFallsBack() = runBlocking {
        assertEquals("RB:1", RadioBrowser.searchWithFallback("jazz", { none }, { browser }).channels[0].id)
        assertEquals("RB:1", RadioBrowser.searchWithFallback("jazz", { throw RuntimeException("x") }, { browser }).channels[0].id)
    }

    @Test
    fun bothFailingIsEmptyNotACrash() = runBlocking {
        val r = RadioBrowser.searchWithFallback("jazz", { none }, { throw RuntimeException("x") })
        assertTrue(r.channels.isEmpty())
    }

    @Test
    fun aRadioGardenChannelHasNoPresetStream() {
        assertNull(garden.channels[0].streamUrl)
    }
}
