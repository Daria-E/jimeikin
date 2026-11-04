package com.wanderwildwood.jimeikin.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.jimeikin.R

/** Whether the one-line tip about holding a song is on show: until a song has been held once. */
fun menuHintVisible(heldBefore: Boolean, hasSongs: Boolean): Boolean = hasSongs && !heldBefore

/** Remembers, in preferences, that a song has been held; the tip never comes back after that. */
object MenuHint {
    private const val PREFS = "menu_hint"
    private const val KEY_HELD = "held_a_song"
    private var loaded = false
    private var held by mutableStateOf(false)

    private fun load(context: Context) {
        if (loaded) return
        held = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HELD, false)
        loaded = true
    }

    fun heldBefore(context: Context): Boolean {
        load(context)
        return held
    }

    fun songHeld(context: Context) {
        load(context)
        if (held) return
        held = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HELD, true).apply()
    }
}

/** The tip itself, for the top of a list of songs. Draws nothing once a song has been held. */
@Composable
fun MenuHintLine(hasSongs: Boolean = true) {
    val context = LocalContext.current
    if (!menuHintVisible(MenuHint.heldBefore(context), hasSongs)) return
    TextMMD(
        text = stringResource(R.string.hint_hold_song),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
