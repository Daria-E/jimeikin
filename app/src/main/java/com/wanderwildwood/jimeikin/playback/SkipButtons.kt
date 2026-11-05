package com.wanderwildwood.jimeikin.playback

import android.view.KeyEvent

/**
 * What the skip buttons on a headset or a Bluetooth speaker do, chosen in Settings.
 *
 * [SONGS] is what every other player does and is the default. [SEEK] is for long recordings,
 * an audiobook or a concert, where the next track is not what anybody wants.
 */
enum class SkipButtonMode {
    SONGS,
    SEEK,
    ;

    companion object {
        /** A stored name back to a mode; anything unknown is the default. */
        fun fromStored(name: String?): SkipButtonMode = entries.firstOrNull { it.name == name } ?: SONGS
    }
}

/** What one press of a media button comes to. */
sealed interface ButtonAction {
    /** The next song in the app's queue. */
    data object NextSong : ButtonAction

    /** The start of this song, or the song before it inside the first few seconds: see [SkipButtons.back]. */
    data object PreviousOrRestart : ButtonAction

    /** A jump inside the song, ahead when positive. */
    data class SeekBy(val deltaMs: Long) : ButtonAction

    /** Taken and nothing done: the key coming back up, or a key held down repeating. */
    data object Swallow : ButtonAction
}

/**
 * The media buttons, worked out apart from the player so the rules can be tested.
 *
 * Only next and previous are decided here. Fast-forward and rewind, which is what most
 * headsets send for a long press, are left to Media3, which seeks with them as it always has.
 */
object SkipButtons {

    const val SEEK_AHEAD_MS = 30_000L
    const val SEEK_BACK_MS = 10_000L

    /**
     * How far into a song "previous" stops meaning the song before it and starts meaning this
     * one from the top. Three seconds is what other players use.
     */
    const val RESTART_RATHER_THAN_PREVIOUS_MS = 3_000L

    /**
     * What a key does, or null when it is not one of the skip keys and Media3 should handle it.
     *
     * A press is acted on when it goes down. A held key arrives as the same key repeating, and
     * those repeats are swallowed: acting on each would race through the queue a song every
     * fiftieth of a second. A headset that can seek while held sends fast-forward and rewind
     * instead, and those go to Media3 untouched.
     *
     * A key coming up is swallowed when its going down was seen ([downSeen]). When it was not,
     * the up is the only sign of the press there is, and it is acted on: a wired headset's keys
     * go to the app in front first, and only what that app leaves reaches the player. The inkOS
     * launcher takes the down of a skip key and leaves the up, so with the home screen showing,
     * the button did nothing at all.
     */
    fun forKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        mode: SkipButtonMode,
        downSeen: Boolean = false,
        canceled: Boolean = false,
    ): ButtonAction? {
        val ahead = keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
        val back = keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
        if (!ahead && !back) return null
        val press = when (action) {
            KeyEvent.ACTION_DOWN -> repeatCount == 0
            KeyEvent.ACTION_UP -> !downSeen && !canceled
            else -> false
        }
        if (!press) return ButtonAction.Swallow
        return when (mode) {
            SkipButtonMode.SONGS -> if (ahead) ButtonAction.NextSong else ButtonAction.PreviousOrRestart
            SkipButtonMode.SEEK -> ButtonAction.SeekBy(if (ahead) SEEK_AHEAD_MS else -SEEK_BACK_MS)
        }
    }

    /** Where a seek by [deltaMs] lands: never before the start, and short of the end when the length is known. */
    fun seekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }

    /** What "back" means just now. */
    enum class Back { RESTART, PREVIOUS, NOTHING }

    /**
     * Past the first few seconds, back starts the song over; inside them it goes to the song
     * before. With nothing before it, it starts this one over all the same - a button that does
     * nothing reads as a broken one. A live station has no start to return to and no song
     * before it.
     */
    fun back(positionMs: Long, hasPrevious: Boolean, isLive: Boolean): Back = when {
        isLive -> if (hasPrevious) Back.PREVIOUS else Back.NOTHING
        positionMs > RESTART_RATHER_THAN_PREVIOUS_MS -> Back.RESTART
        hasPrevious -> Back.PREVIOUS
        else -> Back.RESTART
    }
}

/**
 * The app's queue, as the playback service sees it.
 *
 * The player only ever holds part of the queue - the run of songs from the one playing to the
 * next that needs resolving, and never the songs before it - so its own idea of next and
 * previous is not the queue's. Asked for the next song with nothing after the current one in
 * that run it did nothing, and asked for the previous it could only start the song again. That
 * is what a headset's skip buttons were reaching. Whatever holds the queue attaches here, and
 * the service hands skips to it; with nothing attached the player does what it can.
 */
object QueueSkips {

    interface Handler {
        fun next()
        fun previousOrRestart()
    }

    @Volatile
    private var handler: Handler? = null

    val isAttached: Boolean get() = handler != null

    fun attach(h: Handler) {
        handler = h
    }

    fun detach(h: Handler) {
        if (handler === h) handler = null
    }

    /** True when the queue took it. */
    fun next(): Boolean = handler?.let { it.next(); true } ?: false

    fun previousOrRestart(): Boolean = handler?.let { it.previousOrRestart(); true } ?: false
}
