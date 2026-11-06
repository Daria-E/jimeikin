package com.wanderwildwood.jimeikin

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether a YouTube link tapped in another app may open here. From Android 12 a web link goes
 * straight to the browser unless the reader has said, in Android's "Open by default" page, that
 * this app may open that address; the filters in the manifest alone are not enough, and an app
 * cannot say so for itself. Before Android 12 the reader is asked each time, so there is nothing
 * to set.
 */
object YouTubeLinks {
    enum class State { ON, SOME, OFF }

    /** Android's state for each address this app asks to open; null where none are asked for. */
    fun stateOf(states: Collection<Int>): State? {
        if (states.isEmpty()) return null
        val allowed = states.count {
            it == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
        return when (allowed) {
            states.size -> State.ON
            0 -> State.OFF
            else -> State.SOME
        }
    }

    /** Null below Android 12, or when Android will not say. */
    fun read(context: Context): State? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return null
            val user = manager.getDomainVerificationUserState(context.packageName) ?: return null
            if (!user.isLinkHandlingAllowed) return State.OFF
            stateOf(user.hostToStateMap.values)
        }.getOrNull()
    }

    /** Android's "Open by default" page for this app, or its app page where that is missing. */
    fun open(context: Context) {
        val uri = Uri.parse("package:${context.packageName}")
        val tries = listOf(
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
        )
        for (intent in tries) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
