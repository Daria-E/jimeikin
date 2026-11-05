package com.wanderwildwood.jimeikin.data

import android.content.res.Resources
import com.wanderwildwood.jimeikin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Talks to a Jellyfin server, by its REST API and nothing else: no SDK.
 *
 * **The password is sent once.** [signIn] posts it to `/Users/AuthenticateByName` and the
 * server answers with an access token; the token is what is kept (sealed, see [Secrets]) and
 * what every later request carries. The password is not stored anywhere. When the server stops
 * taking the token - it was signed out from the server's dashboard, say - requests are refused
 * and the person is asked to connect again, which is the one time the password goes again.
 *
 * A song's address carries the token too, as `ApiKey`, since the player and the downloader open
 * it as a plain url with no headers of their own. That address is on the song's row in the
 * library, as a Subsonic server's signed address is, and so the same rule holds: over the open
 * internet a server wants https.
 */
class JellyfinClient(
    private val config: SubsonicConfig,
    private val resources: Resources,
    private val deviceId: String,
) : MusicServerClient {

    private val base = config.normalisedBaseUrl
    private val token = config.token.orEmpty()
    private val userId = config.userId.orEmpty()

    private val reader = JellyfinLibraryReader(userId) { path, params -> get(path, params) }

    override val sendsPassword: Boolean get() = false

    /** The account can read its music: one item asked for, none needed back. */
    override suspend fun ping(): SubsonicResult<Unit> =
        get(
            "/Items",
            mapOf("userId" to userId, "IncludeItemTypes" to "Audio", "Recursive" to "true", "Limit" to "1"),
        ).map { }

    override suspend fun fetchLibrary(onProgress: suspend (SubsonicProgress) -> Unit): SubsonicResult<SubsonicLibrary> =
        reader.read(onProgress)

    override suspend fun fetchAlbum(albumId: String): SubsonicResult<Pair<SubsonicAlbum, List<SubsonicSong>>> =
        reader.readAlbum(albumId)

    override suspend fun fetchSong(songId: String): SubsonicResult<SubsonicSong?> = reader.readSong(songId)

    override suspend fun fetchArtist(artistId: String): SubsonicResult<Pair<String, List<SubsonicAlbum>>> =
        reader.readArtist(artistId)

    override suspend fun fetchPlaylists(): SubsonicResult<List<SubsonicPlaylist>> = reader.readPlaylists()

    override fun streamUrl(songId: String, suffix: String?): String =
        universalStreamUrl(base, songId, userId, deviceId, token)

    /**
     * Ends this sign-in on the server, so the token stops working there too. Asked when the
     * server is forgotten; if the server cannot be reached the token is forgotten here all the
     * same, and the server's dashboard lists the device until it is removed there.
     */
    suspend fun signOut(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/Sessions/Logout")
            .header("Authorization", authorization(deviceId, token))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching { http.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private suspend fun get(path: String, params: Map<String, String>): SubsonicResult<JSONObject> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(base + path + "?" + query(params))
                .header("Authorization", authorization(deviceId, token))
                .header("Accept", "application/json")
                .build()
            try {
                http.newCall(request).execute().use { response -> answer(response, resources, signedIn = true) }
            } catch (e: Exception) {
                // The path only: the query names the account, and nothing here is worth more.
                android.util.Log.w("JellyfinClient", "$path failed: ${e.javaClass.simpleName}")
                SubsonicResult.Failure(resources.getString(R.string.service_subsonic_unreachable))
            }
        }

    companion object {
        private const val CLIENT_NAME = "Music Box"

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Not infinite: a server that accepts the connection and then says nothing would
            // otherwise wedge the sync for good.
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Signs in with a username and password and returns the login to keep: [config] with
         * the token and the account's id in it, and the password taken out. The password is
         * in this one request and nowhere after it.
         */
        suspend fun signIn(
            config: SubsonicConfig,
            resources: Resources,
            deviceId: String,
        ): SubsonicResult<SubsonicConfig> = withContext(Dispatchers.IO) {
            val base = config.normalisedBaseUrl
            val body = JSONObject()
                .put("Username", config.user.trim())
                .put("Pw", config.password)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$base/Users/AuthenticateByName")
                .header("Authorization", authorization(deviceId, null))
                .header("Accept", "application/json")
                .post(body)
                .build()
            val answered = try {
                http.newCall(request).execute().use { response -> answer(response, resources, signedIn = false) }
            } catch (e: Exception) {
                android.util.Log.w("JellyfinClient", "sign-in failed: ${e.javaClass.simpleName}")
                SubsonicResult.Failure(resources.getString(R.string.service_subsonic_unreachable))
            }
            when (answered) {
                is SubsonicResult.Failure -> answered
                is SubsonicResult.Success -> {
                    val token = answered.value.optString("AccessToken").takeIf { it.isNotBlank() }
                    val userId = answered.value.optJSONObject("User")?.optString("Id")?.takeIf { it.isNotBlank() }
                    if (token == null || userId == null) {
                        SubsonicResult.Failure(resources.getString(R.string.service_jellyfin_not_jellyfin))
                    } else {
                        SubsonicResult.Success(config.copy(password = "", token = token, userId = userId))
                    }
                }
            }
        }

        /** An answer read as JSON, or the reason it cannot be, worded for the person. */
        private fun answer(response: Response, resources: Resources, signedIn: Boolean): SubsonicResult<JSONObject> {
            when (response.code) {
                // Code 40 and 50 are Subsonic's for the same two things, which is what lets
                // the library reader and the server page treat both kinds alike.
                401 -> return SubsonicResult.Failure(
                    resources.getString(
                        if (signedIn) R.string.service_jellyfin_signed_out else R.string.service_subsonic_login_refused,
                    ),
                    code = 40,
                )
                403 -> return SubsonicResult.Failure(resources.getString(R.string.service_subsonic_not_allowed), code = 50)
            }
            if (!response.isSuccessful) {
                return SubsonicResult.Failure(
                    resources.getString(R.string.service_subsonic_download_server_answered, response.code),
                    code = -response.code,
                )
            }
            val text = response.body?.string().orEmpty()
            return try {
                SubsonicResult.Success(JSONObject(text))
            } catch (_: JSONException) {
                SubsonicResult.Failure(resources.getString(R.string.service_jellyfin_not_jellyfin))
            }
        }

        /**
         * The header Jellyfin identifies a client by. The device id is this install's own,
         * made once, so signing in again replaces this phone's entry on the server's device
         * list rather than adding another beside it.
         */
        fun authorization(deviceId: String, token: String?): String {
            fun clean(s: String) = s.replace("\"", "").replace(",", " ")
            val device = clean(android.os.Build.MODEL ?: "Android").ifBlank { "Android" }
            val version = clean(com.wanderwildwood.jimeikin.BuildConfig.VERSION_NAME)
            return buildString {
                append("MediaBrowser Client=\"").append(CLIENT_NAME)
                append("\", Device=\"").append(device)
                append("\", DeviceId=\"").append(clean(deviceId))
                append("\", Version=\"").append(version).append('"')
                if (!token.isNullOrBlank()) append(", Token=\"").append(clean(token)).append('"')
            }
        }

        /**
         * A song's address on the server's universal audio endpoint. The server sends the file
         * untouched when it is a kind named in `Container`, which is every kind this phone
         * decodes, and otherwise makes an mp3 of it - the same split a Subsonic stream makes
         * with `format=raw`. With a ceiling set ([withMaxBitrate]) it also re-encodes what is
         * above the ceiling.
         */
        fun universalStreamUrl(base: String, songId: String, userId: String, deviceId: String, token: String): String =
            base.trimEnd('/') + "/Audio/" + encode(songId) + "/universal?" + query(
                linkedMapOf(
                    "UserId" to userId,
                    "DeviceId" to deviceId,
                    "Container" to PLAYABLE_SUFFIXES.sorted().joinToString(","),
                    "AudioCodec" to "mp3",
                    "TranscodingContainer" to "mp3",
                    "TranscodingProtocol" to "http",
                    "ApiKey" to token,
                ),
            )

        fun isUniversalStream(url: String): Boolean = "/Audio/" in url && "/universal?" in url

        /** [url] with a ceiling of [kbps] on what the server sends, replacing any before it. */
        fun withMaxBitrate(url: String, kbps: Int): String {
            val q = url.indexOf('?')
            if (q < 0) return url
            val kept = url.substring(q + 1).split('&').filterNot { it.startsWith("MaxStreamingBitrate=") }
            return url.substring(0, q) + "?" + (kept + "MaxStreamingBitrate=${kbps * 1000}").joinToString("&")
        }

        internal fun query(params: Map<String, String>): String =
            params.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }

        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
