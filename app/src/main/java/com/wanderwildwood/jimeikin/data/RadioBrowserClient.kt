package com.wanderwildwood.jimeikin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Radio Browser (radio-browser.info), a community directory of stations, as a second source
 * behind radio.garden.
 *
 * It is asked only when radio.garden errors or finds nothing. A station found here is kept the
 * same way as any other: the `url_resolved` address is stored as the stream, so a kept station
 * depends on neither directory afterwards.
 *
 * Servers are named in a fixed list and tried in turn. The documented way of finding them is a
 * DNS lookup, which does not work on every network, so it is not used. The "count a click" call
 * is not made: it would tell a third party what was played.
 */
object RadioBrowser {

    /** Checked live on 2026-11-01 (de1 and de2 answered; fi1, nl1 and at1 did not). */
    val MIRRORS = listOf(
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "all.api.radio-browser.info",
    )

    private const val LIMIT = 40

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** The user agent names the app and its version, as their guidance asks. */
    @Volatile
    var userAgent: String = "MusicBox/" + com.wanderwildwood.jimeikin.BuildConfig.VERSION_NAME

    /** First mirror that answers with a list wins; null if none does. */
    private fun get(path: String): String? {
        for (host in MIRRORS) {
            try {
                val request = Request.Builder()
                    .url("https://$host/json/$path")
                    .header("User-Agent", userAgent)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string()?.let { return it }
                }
            } catch (e: Exception) {
                android.util.Log.w("RadioBrowser", "$host failed", e)
            }
        }
        return null
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")

    private fun list(query: String): List<RadioChannel> {
        val body = get("stations/search?$query&hidebroken=true&order=votes&reverse=true&limit=$LIMIT")
            ?: return emptyList()
        return parseStations(body)
    }

    suspend fun searchByName(name: String): List<RadioChannel> = withContext(Dispatchers.IO) {
        if (name.trim().length < 2) emptyList() else list("name=${enc(name)}")
    }

    suspend fun searchByTag(tag: String): List<RadioChannel> = withContext(Dispatchers.IO) {
        if (tag.trim().length < 2) emptyList() else list("tag=${enc(tag)}")
    }

    /** A country's full name ("Germany") or its two-letter code ("DE"). */
    suspend fun searchByCountry(country: String): List<RadioChannel> = withContext(Dispatchers.IO) {
        val c = country.trim()
        when {
            c.length == 2 -> list("countrycode=${enc(c)}")
            c.length > 2 -> list("country=${enc(c)}&countryExact=true")
            else -> emptyList()
        }
    }

    /** What was typed, tried as a name, then a tag, then a country. */
    suspend fun search(query: String): RadioSearchResults {
        val channels = searchByName(query)
            .ifEmpty { searchByTag(query) }
            .ifEmpty { searchByCountry(query) }
        return RadioSearchResults(emptyList(), channels)
    }

    /** Maps Radio Browser's JSON array to the station model; unusable rows are left out. */
    fun parseStations(json: String): List<RadioChannel> {
        val array = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val seen = HashSet<String>()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val uuid = o.optString("stationuuid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val stream = o.optString("url_resolved").trim()
            if (!(stream.startsWith("http://") || stream.startsWith("https://"))) return@mapNotNull null
            if (o.has("lastcheckok") && o.optInt("lastcheckok", 1) == 0) return@mapNotNull null
            val title = o.optString("name").trim().ifBlank { return@mapNotNull null }
            if (!seen.add(uuid)) return@mapNotNull null
            RadioChannel(
                id = "RB:$uuid",
                title = title,
                place = o.optString("state").trim(),
                country = o.optString("country").trim(),
                streamUrl = stream,
            )
        }
    }

    /**
     * radio.garden first; Radio Browser only when it comes back empty or throws. Kept apart from
     * the two clients so the order can be tested without a network.
     */
    suspend fun searchWithFallback(
        query: String,
        primary: suspend (String) -> RadioSearchResults,
        fallback: suspend (String) -> RadioSearchResults,
    ): RadioSearchResults {
        val first = try { primary(query) } catch (e: Exception) { null }
        if (first != null && (first.channels.isNotEmpty() || first.places.isNotEmpty())) return first
        return try { fallback(query) } catch (e: Exception) { first ?: RadioSearchResults(emptyList(), emptyList()) }
    }
}
