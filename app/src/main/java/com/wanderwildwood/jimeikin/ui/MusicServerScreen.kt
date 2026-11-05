package com.wanderwildwood.jimeikin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.jimeikin.data.ServerKind
import com.wanderwildwood.jimeikin.data.SubsonicConfig
import com.wanderwildwood.jimeikin.R

/**
 * Where a music server is entered: Navidrome or any other Subsonic server, or Jellyfin.
 *
 * Which kind, three fields and one button. Connecting reads the whole library in one go rather than
 * asking as you browse, so what the server holds is on the Songs, Albums and Artists screens
 * beside the music on the card, and stays listed when the phone is away from the network;
 * playing one then needs the network, which the dotted rule under its row says.
 */
@Composable
fun MusicServerScreen(
    config: SubsonicConfig?,
    sendsPassword: Boolean,
    isBusy: Boolean,
    statusMessage: String?,
    onConnectClick: (SubsonicConfig) -> Unit,
    onForgetClick: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(config?.kind ?: ServerKind.SUBSONIC) }
    var address by rememberSaveable { mutableStateOf(config?.baseUrl ?: "") }
    var user by rememberSaveable { mutableStateOf(config?.user ?: "") }
    var password by rememberSaveable { mutableStateOf(config?.password ?: "") }

    // The fields are seeded from the stored login once and then belong to whoever is typing,
    // which is right everywhere except here: forgetting a server used to leave the address and
    // a boxful of password dots sitting under the word "Forgotten", which says the opposite of
    // what has just happened. They were only on screen - nothing was kept - but a screen that
    // looks like it kept your password is not much better than one that did.
    LaunchedEffect(config) {
        if (config == null) {
            kind = ServerKind.SUBSONIC
            address = ""
            user = ""
            password = ""
        }
    }

    // Forget sits at the foot of the screen when everything fits, and the page scrolls
    // when it does not - a long status line once pushed it off the bottom entirely.
    // The minimum height is what lets the spacer above it still push it down.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // A tap steps to the other kind, the value shown under the name as on Settings.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !isBusy) {
                        kind = if (kind == ServerKind.SUBSONIC) ServerKind.JELLYFIN else ServerKind.SUBSONIC
                        // A password typed or kept for one kind is not sent to the other.
                        password = ""
                    }
                    .padding(vertical = 8.dp),
            ) {
                TextMMD(text = stringResource(R.string.library_server_kind), style = MaterialTheme.typography.titleSmall)
                TextMMD(
                    text = stringResource(
                        if (kind == ServerKind.JELLYFIN) R.string.library_server_kind_jellyfin
                        else R.string.library_server_kind_subsonic,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextFieldMMD(
                modifier = Modifier.fillMaxWidth(),
                value = address,
                onValueChange = { address = it },
                label = { TextMMD(text = stringResource(R.string.library_server_address)) },
                placeholder = {
                    TextMMD(text = if (kind == ServerKind.JELLYFIN) "jellyfin.example.org" else "music.example.org")
                },
                singleLine = true,
                readOnly = isBusy,
            )

            Spacer(modifier = Modifier.height(8.dp))
            TextMMD(
                text = stringResource(
                    if (com.wanderwildwood.jimeikin.data.PlainHttp.onOpenNetwork(address)) R.string.library_server_plain_http
                    else R.string.library_server_address_note,
                ),
                style = MaterialTheme.typography.labelSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextFieldMMD(
                modifier = Modifier.fillMaxWidth(),
                value = user,
                onValueChange = { user = it },
                label = { TextMMD(text = stringResource(R.string.library_server_username)) },
                singleLine = true,
                readOnly = isBusy,
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextFieldMMD(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = { password = it },
                label = { TextMMD(text = stringResource(R.string.library_server_password)) },
                singleLine = true,
                readOnly = isBusy,
                visualTransformation = PasswordVisualTransformation(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // A Jellyfin login kept from before is used again when the password is left empty
            // and the address and name are the ones it was made for: the password is not kept,
            // so the field is empty every time this screen opens.
            val kept = config?.takeIf {
                kind == ServerKind.JELLYFIN && password.isEmpty() && it.kind == ServerKind.JELLYFIN &&
                    it.baseUrl.trim() == address.trim() && it.user.trim() == user.trim()
            }
            val entered = kept ?: SubsonicConfig(address, user, password, kind)
            ButtonMMD(
                onClick = { if (entered.isComplete && !isBusy) onConnectClick(entered) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(
                    text = if (config == null) stringResource(R.string.library_server_connect) else stringResource(R.string.library_server_connect_again),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextMMD(text = statusMessage, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(24.dp))

            TextMMD(
                text = stringResource(
                    when {
                        kind == ServerKind.JELLYFIN -> R.string.library_server_password_note_jellyfin
                        sendsPassword -> R.string.library_server_password_note_sent
                        else -> R.string.library_server_password_note
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
            )

            if (config != null) {
                Spacer(modifier = Modifier.weight(1f))
                ForgetServerRow(onForget = onForgetClick)
            }
        }
    }
}

/** The row asks. Nothing on the server is touched; its songs leave this phone's library. */
@Composable
private fun ForgetServerRow(onForget: () -> Unit) {
    var armed by rememberArmedFlag()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (armed) { armed = false; onForget() } else armed = true }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        TextMMD(
            text = if (armed) {
                stringResource(R.string.library_server_forget_armed)
            } else {
                stringResource(R.string.library_server_forget)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
