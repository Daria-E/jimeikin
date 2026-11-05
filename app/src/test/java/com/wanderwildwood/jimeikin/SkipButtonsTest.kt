package com.wanderwildwood.jimeikin

import android.view.KeyEvent
import com.wanderwildwood.jimeikin.playback.ButtonAction
import com.wanderwildwood.jimeikin.playback.QueueSkips
import com.wanderwildwood.jimeikin.playback.SkipButtonMode
import com.wanderwildwood.jimeikin.playback.SkipButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipButtonsTest {

    private fun press(keyCode: Int, mode: SkipButtonMode = SkipButtonMode.SONGS, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        SkipButtons.forKey(keyCode, action, repeat, mode)

    @Test
    fun `next and previous move through the songs by default`() {
        assertEquals(ButtonAction.NextSong, press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(ButtonAction.PreviousOrRestart, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(ButtonAction.NextSong, press(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD))
        assertEquals(ButtonAction.PreviousOrRestart, press(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD))
    }

    @Test
    fun `skip ahead and back seeks thirty seconds on and ten back`() {
        assertEquals(ButtonAction.SeekBy(30_000L), press(KeyEvent.KEYCODE_MEDIA_NEXT, SkipButtonMode.SEEK))
        assertEquals(ButtonAction.SeekBy(-10_000L), press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, SkipButtonMode.SEEK))
    }

    @Test
    fun `fast forward rewind and play are left to media3`() {
        assertNull(press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertNull(press(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertNull(press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertNull(press(KeyEvent.KEYCODE_HEADSETHOOK))
        assertNull(press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, SkipButtonMode.SEEK))
    }

    @Test
    fun `the key coming up after its own press and a held key repeating do nothing`() {
        fun up(keyCode: Int) = SkipButtons.forKey(keyCode, KeyEvent.ACTION_UP, 0, SkipButtonMode.SONGS, downSeen = true)
        assertEquals(ButtonAction.Swallow, up(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(ButtonAction.Swallow, up(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(ButtonAction.Swallow, press(KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 1))
        assertEquals(ButtonAction.Swallow, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, SkipButtonMode.SEEK, repeat = 7))
    }

    @Test
    fun `a key coming up whose press another app took is the press`() {
        assertEquals(ButtonAction.NextSong, press(KeyEvent.KEYCODE_MEDIA_NEXT, action = KeyEvent.ACTION_UP))
        assertEquals(ButtonAction.PreviousOrRestart, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, action = KeyEvent.ACTION_UP))
        assertEquals(ButtonAction.SeekBy(30_000L), press(KeyEvent.KEYCODE_MEDIA_NEXT, SkipButtonMode.SEEK, action = KeyEvent.ACTION_UP))
        // Not one that was called off.
        assertEquals(
            ButtonAction.Swallow,
            SkipButtons.forKey(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP, 0, SkipButtonMode.SONGS, canceled = true),
        )
    }

    @Test
    fun `a stored mode comes back and anything else is the default`() {
        assertEquals(SkipButtonMode.SEEK, SkipButtonMode.fromStored("SEEK"))
        assertEquals(SkipButtonMode.SONGS, SkipButtonMode.fromStored("SONGS"))
        assertEquals(SkipButtonMode.SONGS, SkipButtonMode.fromStored(null))
        assertEquals(SkipButtonMode.SONGS, SkipButtonMode.fromStored("garbage"))
    }

    @Test
    fun `back restarts after three seconds and goes back inside them`() {
        assertEquals(SkipButtons.Back.RESTART, SkipButtons.back(positionMs = 45_000, hasPrevious = true, isLive = false))
        assertEquals(SkipButtons.Back.RESTART, SkipButtons.back(positionMs = 3_001, hasPrevious = true, isLive = false))
        assertEquals(SkipButtons.Back.PREVIOUS, SkipButtons.back(positionMs = 3_000, hasPrevious = true, isLive = false))
        assertEquals(SkipButtons.Back.PREVIOUS, SkipButtons.back(positionMs = 0, hasPrevious = true, isLive = false))
    }

    @Test
    fun `back on the first song starts it over`() {
        assertEquals(SkipButtons.Back.RESTART, SkipButtons.back(positionMs = 1_000, hasPrevious = false, isLive = false))
    }

    @Test
    fun `back on a station never restarts it`() {
        assertEquals(SkipButtons.Back.PREVIOUS, SkipButtons.back(positionMs = 60_000, hasPrevious = true, isLive = true))
        assertEquals(SkipButtons.Back.NOTHING, SkipButtons.back(positionMs = 60_000, hasPrevious = false, isLive = true))
    }

    @Test
    fun `a seek stays inside the song`() {
        assertEquals(40_000L, SkipButtons.seekTarget(10_000, 200_000, 30_000))
        assertEquals(0L, SkipButtons.seekTarget(4_000, 200_000, -10_000))
        assertEquals(200_000L, SkipButtons.seekTarget(190_000, 200_000, 30_000))
        // A length not yet known (C.TIME_UNSET is negative) does not cap it.
        assertEquals(220_000L, SkipButtons.seekTarget(190_000, Long.MIN_VALUE + 1, 30_000))
    }

    @Test
    fun `skips go to the queue while one is attached and to the player otherwise`() {
        var nexts = 0
        var backs = 0
        val handler = object : QueueSkips.Handler {
            override fun next() { nexts++ }
            override fun previousOrRestart() { backs++ }
        }
        assertFalse(QueueSkips.next())
        QueueSkips.attach(handler)
        try {
            assertTrue(QueueSkips.isAttached)
            assertTrue(QueueSkips.next())
            assertTrue(QueueSkips.previousOrRestart())
            assertEquals(1, nexts)
            assertEquals(1, backs)
            // A handler that has been replaced cannot take the newer one away.
            QueueSkips.detach(object : QueueSkips.Handler {
                override fun next() = Unit
                override fun previousOrRestart() = Unit
            })
            assertTrue(QueueSkips.isAttached)
        } finally {
            QueueSkips.detach(handler)
        }
        assertFalse(QueueSkips.isAttached)
        assertFalse(QueueSkips.previousOrRestart())
    }
}
