package com.wanderwildwood.jimeikin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CalmMusicSettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _includeLocalMusic = MutableStateFlow(getIncludeLocalMusicSync())
    val includeLocalMusic: StateFlow<Boolean> = _includeLocalMusic.asStateFlow()

    private val _localMusicFolders = MutableStateFlow(getLocalMusicFoldersSync())
    val localMusicFolders: StateFlow<Set<String>> = _localMusicFolders.asStateFlow()


    private val _subsonicConfig = MutableStateFlow(getSubsonicConfigSync())
    val subsonicConfig: StateFlow<SubsonicConfig?> = _subsonicConfig.asStateFlow()

    private val _subsonicMaxBitrate = MutableStateFlow(getSubsonicMaxBitrateSync())

    /**
     * The most a server song may stream at, in kbps, or null to stream every song as it is
     * stored. Kept apart from the login, so forgetting a server and connecting again does not
     * lose it.
     */
    val subsonicMaxBitrate: StateFlow<Int?> = _subsonicMaxBitrate.asStateFlow()

    fun setSubsonicMaxBitrate(kbps: Int?) {
        prefs.edit { putInt(KEY_SUBSONIC_MAX_BITRATE, kbps ?: 0) }
        _subsonicMaxBitrate.value = kbps?.takeIf { it > 0 }
    }

    fun getSubsonicMaxBitrateSync(): Int? =
        prefs.getInt(KEY_SUBSONIC_MAX_BITRATE, 0).takeIf { it > 0 }

    /**
     * The music server's address and login.
     *
     * For a Subsonic server the password has to be kept, not just a token: Subsonic signs
     * each request with md5(password + a fresh salt), so the password itself is needed every
     * time. The About says so. For Jellyfin only the token its sign-in handed back is kept,
     * and the password is never written: a Jellyfin login that has not been signed in yet is
     * not stored at all.
     */
    fun setSubsonicConfig(config: SubsonicConfig?) {
        val keep = config?.takeIf { it.isComplete && (it.kind != ServerKind.JELLYFIN || it.isSignedIn) }
        prefs.edit {
            // What one server wanted says nothing about another, or about another account.
            if (config == null || prefs.getString(KEY_SUBSONIC_AUTH_FOR, null) != authKey(config)) {
                remove(KEY_SUBSONIC_AUTH_FOR)
                remove(KEY_SUBSONIC_AUTH_VERSION)
                remove(KEY_SUBSONIC_AUTH_PASSWORD_FORM)
            }
            remove(KEY_SUBSONIC_PASSWORD)
            remove(KEY_JELLYFIN_TOKEN)
            remove(KEY_JELLYFIN_USER_ID)
            if (keep == null) {
                remove(KEY_SUBSONIC_URL)
                remove(KEY_SUBSONIC_USER)
                remove(KEY_SERVER_KIND)
            } else {
                putString(KEY_SUBSONIC_URL, keep.baseUrl)
                putString(KEY_SUBSONIC_USER, keep.user)
                putString(KEY_SERVER_KIND, keep.kind.name)
                // Sealed with a key held in the phone's keystore - see [Secrets]. If sealing
                // fails the secret is not written at all rather than written in the clear.
                when (keep.kind) {
                    ServerKind.SUBSONIC -> putString(KEY_SUBSONIC_PASSWORD, Secrets.seal(keep.password))
                    ServerKind.JELLYFIN -> {
                        putString(KEY_JELLYFIN_TOKEN, Secrets.seal(keep.token))
                        putString(KEY_JELLYFIN_USER_ID, keep.userId)
                    }
                }
            }
        }
        // What is held in memory is what was written: never a Jellyfin password.
        _subsonicConfig.value = keep?.let { if (it.kind == ServerKind.JELLYFIN) it.copy(password = "") else it }
        _subsonicSendsPassword.value = _subsonicConfig.value?.takeIf { it.kind == ServerKind.SUBSONIC }?.let { getSubsonicAuth(it).sendsPassword } ?: false
    }

    /**
     * How this server and account have turned out to want requests signed (see
     * [SubsonicClient]), or the usual way when nothing has been learned about them.
     */
    fun getSubsonicAuth(config: SubsonicConfig): SubsonicAuth {
        if (prefs.getString(KEY_SUBSONIC_AUTH_FOR, null) != authKey(config)) return SubsonicAuth()
        return SubsonicAuth(
            apiVersion = prefs.getString(KEY_SUBSONIC_AUTH_VERSION, null),
            passwordForm = prefs.getBoolean(KEY_SUBSONIC_AUTH_PASSWORD_FORM, false),
        )
    }

    fun setSubsonicAuth(config: SubsonicConfig, auth: SubsonicAuth) {
        prefs.edit {
            putString(KEY_SUBSONIC_AUTH_FOR, authKey(config))
            if (auth.apiVersion == null) remove(KEY_SUBSONIC_AUTH_VERSION)
            else putString(KEY_SUBSONIC_AUTH_VERSION, auth.apiVersion)
            putBoolean(KEY_SUBSONIC_AUTH_PASSWORD_FORM, auth.passwordForm)
        }
        if (_subsonicConfig.value?.let(::authKey) == authKey(config)) {
            _subsonicSendsPassword.value = auth.sendsPassword
        }
    }

    private val _subsonicSendsPassword = MutableStateFlow(
        _subsonicConfig.value?.takeIf { it.kind == ServerKind.SUBSONIC }?.let { getSubsonicAuth(it).sendsPassword } ?: false,
    )

    /** Whether the stored server is one that is sent the password itself, for the server screen's note. */
    val subsonicSendsPassword: StateFlow<Boolean> = _subsonicSendsPassword.asStateFlow()

    private fun authKey(config: SubsonicConfig) = config.normalisedBaseUrl + "\n" + config.user.trim()

    private fun getSubsonicConfigSync(): SubsonicConfig? {
        var url = prefs.getString(KEY_SUBSONIC_URL, null) ?: return null
        // Before 2.18.14 an address typed without a scheme was used as http://. Keep the server
        // a reader already connected to where it is: write that scheme in, once.
        if (!PlainHttp.hasScheme(url) && url.isNotBlank()) {
            url = "http://" + url.trim()
            prefs.edit { putString(KEY_SUBSONIC_URL, url) }
        }
        val user = prefs.getString(KEY_SUBSONIC_USER, null) ?: return null
        if (ServerKind.fromStored(prefs.getString(KEY_SERVER_KIND, null)) == ServerKind.JELLYFIN) {
            val token = Secrets.open(prefs.getString(KEY_JELLYFIN_TOKEN, null)) ?: return null
            val userId = prefs.getString(KEY_JELLYFIN_USER_ID, null) ?: return null
            return SubsonicConfig(url, user, "", ServerKind.JELLYFIN, token, userId).takeIf { it.isComplete }
        }
        val stored = prefs.getString(KEY_SUBSONIC_PASSWORD, null) ?: return null
        val password = Secrets.open(stored) ?: return null
        // A password written by an older version is in the clear. Seal it where it lies, the
        // first time it is read, so upgrading is enough and nobody has to type it again.
        if (!Secrets.isSealed(stored)) {
            Secrets.seal(password)?.let { sealed ->
                prefs.edit { putString(KEY_SUBSONIC_PASSWORD, sealed) }
            }
        }
        return SubsonicConfig(url, user, password).takeIf { it.isComplete }
    }

    /**
     * This install's name for itself on a Jellyfin server, made once. Jellyfin lists a sign-in
     * by device, so signing in again replaces this phone's entry instead of adding another.
     * Random, and nothing to do with the phone's own identifiers.
     */
    fun jellyfinDeviceId(): String {
        prefs.getString(KEY_JELLYFIN_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val made = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit { putString(KEY_JELLYFIN_DEVICE_ID, made) }
        return made
    }

    fun getLastLocalLibraryScanMillis(): Long {
        return prefs.getLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, 0L)
    }

    fun updateLastLocalLibraryScanMillis(value: Long) {
        prefs.edit { putLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, value) }
    }

    fun getLocalAlbumKeyVersion(): Int = prefs.getInt(KEY_LOCAL_ALBUM_KEY_VERSION, 1)

    fun setLocalAlbumKeyVersion(value: Int) {
        prefs.edit { putInt(KEY_LOCAL_ALBUM_KEY_VERSION, value) }
    }

    /** The sources search looks in, by name; null until one has been turned off, which is all of them. */
    fun getSearchSources(): Set<String>? = prefs.getStringSet(KEY_SEARCH_SOURCES, null)

    fun setSearchSources(sources: Set<String>) {
        prefs.edit { putStringSet(KEY_SEARCH_SOURCES, sources) }
    }

    fun setIncludeLocalMusic(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INCLUDE_LOCAL_MUSIC, enabled) }
        _includeLocalMusic.value = enabled
    }

    private fun getIncludeLocalMusicSync(): Boolean {
        return prefs.getBoolean(KEY_INCLUDE_LOCAL_MUSIC, false)
    }

    fun addLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.add(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun removeLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.remove(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun getLocalMusicFoldersSync(): Set<String> {
        return prefs.getStringSet(KEY_LOCAL_MUSIC_FOLDERS, emptySet()) ?: emptySet()
    }

    /** Off unless turned on: what is playing, handed to Glance for its lock-screen panel. The app draws its own strip on the lock screen, so the two would otherwise say it twice. */
    fun getPlayingOnLockScreenSync(): Boolean {
        return prefs.getBoolean(KEY_PLAYING_ON_LOCK_SCREEN, false)
    }

    fun setPlayingOnLockScreen(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PLAYING_ON_LOCK_SCREEN, enabled) }
    }

    /**
     * What a headset's or a Bluetooth speaker's skip buttons do. Read by the playback service
     * at each press, so a change holds from the next one.
     */
    fun getSkipButtonModeSync(): com.wanderwildwood.jimeikin.playback.SkipButtonMode =
        com.wanderwildwood.jimeikin.playback.SkipButtonMode.fromStored(prefs.getString(KEY_SKIP_BUTTONS, null))

    fun setSkipButtonMode(mode: com.wanderwildwood.jimeikin.playback.SkipButtonMode) {
        prefs.edit { putString(KEY_SKIP_BUTTONS, mode.name) }
    }

    /** Album details looked up online for albums whose tags leave gaps (full edition); off to begin with. */
    fun getAlbumDetailsLookupSync(): Boolean = prefs.getBoolean(KEY_ALBUM_DETAILS_LOOKUP, false)

    fun setAlbumDetailsLookup(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ALBUM_DETAILS_LOOKUP, enabled) }
    }

    /**
     * Which of the five places along the bottom was last open. The app always started on
     * Songs, so somebody who lives in Albums or Playlists paid a tap for it every time they
     * came back - and on a phone that is picked up and put down all day, that is most of
     * the taps the app ever gets.
     *
     * Only the routes on the bottom bar are kept, and only those are given back: a stored
     * route that a later version no longer has is a screen nobody can navigate away from,
     * so anything unrecognised falls back to Songs.
     */
    fun getLastLibraryTab(known: List<String>, fallback: String): String {
        val stored = prefs.getString(KEY_LAST_LIBRARY_TAB, null) ?: return fallback
        return if (stored in known) stored else fallback
    }

    fun setLastLibraryTab(route: String) {
        prefs.edit { putString(KEY_LAST_LIBRARY_TAB, route) }
    }

    companion object {
        private const val PREFS_NAME = "calmmusic_settings"
        private const val KEY_SUBSONIC_URL = "subsonic_url"
        private const val KEY_SUBSONIC_USER = "subsonic_user"
        private const val KEY_SUBSONIC_PASSWORD = "subsonic_password"
        private const val KEY_SUBSONIC_AUTH_FOR = "subsonic_auth_for"
        private const val KEY_SUBSONIC_AUTH_VERSION = "subsonic_auth_version"
        private const val KEY_SUBSONIC_AUTH_PASSWORD_FORM = "subsonic_auth_password_form"
        private const val KEY_SUBSONIC_MAX_BITRATE = "subsonic_max_bitrate"
        private const val KEY_SERVER_KIND = "server_kind"
        private const val KEY_JELLYFIN_TOKEN = "jellyfin_token"
        private const val KEY_JELLYFIN_USER_ID = "jellyfin_user_id"
        private const val KEY_JELLYFIN_DEVICE_ID = "jellyfin_device_id"
        private const val KEY_INCLUDE_LOCAL_MUSIC = "include_local_music"
        private const val KEY_SEARCH_SOURCES = "search_sources"
        private const val KEY_LOCAL_MUSIC_FOLDERS = "local_music_folders"
        private const val KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS = "last_local_library_scan_millis"
        private const val KEY_LOCAL_ALBUM_KEY_VERSION = "local_album_key_version"
        private const val KEY_LAST_LIBRARY_TAB = "last_library_tab"
        private const val KEY_PLAYING_ON_LOCK_SCREEN = "playing_on_lock_screen"
        private const val KEY_ALBUM_DETAILS_LOOKUP = "album_details_lookup"
        private const val KEY_SKIP_BUTTONS = "skip_buttons"
    }
}