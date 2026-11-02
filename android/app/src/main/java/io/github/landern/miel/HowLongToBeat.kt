package io.github.landern.miel

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Completion times from HowLongToBeat's site search, cached per game. */
object HowLongToBeat {
    /** Completion times in seconds; 0 when HowLongToBeat has none. */
    data class Times(val main: Int, val plus: Int, val all: Int)

    /**
     * One search hit; `platforms` holds HowLongToBeat's platform names, `steam` its Steam app ID or
     * 0, and `completions` how many players logged a time.
     */
    internal data class Result(
        val name: String,
        val alias: String,
        val platforms: List<String>,
        val times: Times,
        val steam: Long = 0,
        val completions: Int = 0,
    )

    private const val SITE = "https://howlongtobeat.com"
    private const val FALLBACK_PATH = "/api/s"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val SCRIPT =
        Regex("""<script[^>]+src\s*=\s*["']([^"']*/_next/static/chunks/[^"']*)["']""")
    private val POST_FETCH =
        Regex(
            """fetch\s*\(\s*["']/api/([a-zA-Z0-9_/]+)[^{]{0,200}\{[^}]*method\s*:\s*["']POST["']""",
        )
    private val PLATFORMS =
        mapOf(
            "Nintendo 3DS" to "Nintendo 3DS",
            "Nintendo DS" to "Nintendo DS",
            "Game Boy" to "Game Boy",
            "Game Boy Color" to "Game Boy Color",
            "Game Boy Advance" to "Game Boy Advance",
            "NES" to "NES",
            "SNES" to "Super Nintendo",
            "Nintendo 64" to "Nintendo 64",
            "GameCube" to "Nintendo GameCube",
            "Wii" to "Nintendo Wii",
            "PSP" to "PlayStation Portable",
            "PlayStation" to "PlayStation",
            DroidDeck.PLATFORM to "PC",
        )

    /** The search endpoint found in the site's scripts, kept for the process. */
    @Volatile private var searchPath: String? = null

    /**
     * Cached times for `game`: Times, null when not looked up yet; a miss is cached as
     * Times(0,0,0).
     */
    fun cached(context: Context, game: Game): Times? {
        val json = prefs(context).getString(game.id, null) ?: return null
        val times = JSONObject(json)
        return Times(times.optInt("main"), times.optInt("plus"), times.optInt("all"))
    }

    /**
     * Searches HowLongToBeat for `game` and caches the result. Blocking; call off the main thread.
     * Throws IOException when the site cannot be searched (nothing cached then).
     */
    fun lookup(context: Context, game: Game): Times {
        val terms = normalize(game.title).split(' ').filter { it.isNotEmpty() }
        val results =
            search(terms)
                ?: run {
                    searchPath = null
                    search(terms)
                }
                ?: throw IOException("HowLongToBeat search failed")
        val index = best(game.title, game.platform, game.steamId, results)
        val times = if (index < 0) Times(0, 0, 0) else results[index].times
        val json =
            JSONObject().put("main", times.main).put("plus", times.plus).put("all", times.all)
        prefs(context).edit().putString(game.id, json.toString()).apply()
        return times
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * e.g. "Main 12½h · Extras 20h · 100% 35h"; empty for a miss. Rounds to the nearest half hour,
     * skips zero values; under 1h shows minutes like "45m".
     */
    fun describe(times: Times): String =
        listOf("Main" to times.main, "Extras" to times.plus, "100%" to times.all)
            .filter { it.second > 0 }
            .joinToString(" · ") { (label, seconds) -> "$label ${duration(seconds)}" }

    private fun duration(seconds: Int): String {
        if (seconds < 3600) return "${(seconds + 30) / 60}m"
        val halves = (seconds + 900) / 1800
        return "${halves / 2}${if (halves % 2 == 1) "½" else ""}h"
    }

    /**
     * Index of the result with Steam app ID `steamId`, else the best name match for `title` on
     * `platform`, or -1.
     */
    internal fun best(
        title: String,
        platform: String,
        steamId: String,
        results: List<Result>,
    ): Int {
        val steam = steamId.toLongOrNull()
        if (steam != null) results.indexOfFirst { it.steam == steam }.let { if (it >= 0) return it }
        val query = normalize(title)
        val wanted = PLATFORMS[platform]
        return results
            .mapIndexed { index, result ->
                val score =
                    listOf(result.name, result.alias)
                        .map(::normalize)
                        .filter { it.isNotEmpty() }
                        .maxOfOrNull { score(query, it) } ?: 0.0
                Triple(index, wanted != null && wanted in result.platforms, score)
            }
            .filter { it.third >= 0.6 }
            .maxWithOrNull(
                compareBy({ it.second }, { it.third }, { results[it.first].completions }),
            )
            ?.first ?: -1
    }

    private fun score(query: String, candidate: String): Double {
        val similarity =
            1.0 - distance(query, candidate).toDouble() / maxOf(query.length, candidate.length)
        val missingNumber = query.any(Char::isDigit) && candidate.none(Char::isDigit)
        return if (missingNumber) similarity - 0.1 else similarity
    }

    /** Levenshtein distance. */
    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** Lowercase, without accents or punctuation, `&` as "and", single spaces. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Search results, or null when the site rejects the request or answers without `data`. */
    private fun search(terms: List<String>): List<Result>? {
        val path = searchPath ?: discover().also { searchPath = it }
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$SITE/")
        val init =
            request("$SITE$path/init?t=${System.currentTimeMillis()}", headers, null, 64 * 1024)
                ?: return null
        try {
            val auth = JSONObject(init)
            val names = auth.keys().asSequence().toList()
            val token = auth.optString("token")
            val key = names.find { it.contains("key", ignoreCase = true) }?.let(auth::optString)
            val value = names.find { it.contains("val", ignoreCase = true) }?.let(auth::optString)
            if (token.isEmpty()) return null
            val pair = if (key.isNullOrEmpty() || value == null) emptyMap() else mapOf(key to value)
            val response =
                request(
                    SITE + path,
                    headers +
                        mapOf(
                            "Content-Type" to "application/json",
                            "Accept" to "*/*",
                            "Origin" to SITE,
                            "x-auth-token" to token,
                        ) +
                        pair.flatMap { (k, v) -> listOf("x-hp-key" to k, "x-hp-val" to v) },
                    searchBody(terms).apply { pair.forEach { (k, v) -> put(k, v) } }.toString(),
                    1024 * 1024,
                ) ?: return null
            val data = JSONObject(response).optJSONArray("data") ?: return null
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(::result) }
        } catch (error: JSONException) {
            return null
        }
    }

    private fun result(json: JSONObject): Result =
        Result(
            name = string(json, "game_name"),
            alias = string(json, "game_alias"),
            platforms = string(json, "profile_platform").split(',').map { it.trim() },
            times =
                Times(json.optInt("comp_main"), json.optInt("comp_plus"), json.optInt("comp_100")),
            steam = json.optLong("profile_steam"),
            completions = json.optInt("comp_all_count"),
        )

    private fun string(json: JSONObject, name: String): String =
        if (json.isNull(name)) "" else json.optString(name)

    private fun searchBody(terms: List<String>): JSONObject =
        JSONObject()
            .put("searchType", "games")
            .put("searchTerms", JSONArray(terms))
            .put("searchPage", 1)
            .put("size", 20)
            .put(
                "searchOptions",
                JSONObject()
                    .put(
                        "games",
                        JSONObject()
                            .put("userId", 0)
                            .put("platform", "")
                            .put("sortCategory", "popular")
                            .put("rangeCategory", "main")
                            .put("rangeTime", JSONObject().put("min", 0).put("max", 0))
                            .put(
                                "gameplay",
                                JSONObject()
                                    .put("perspective", "")
                                    .put("flow", "")
                                    .put("genre", "")
                                    .put("difficulty", ""),
                            )
                            .put("rangeYear", JSONObject().put("max", "").put("min", ""))
                            .put("modifier", "hide_dlc"),
                    )
                    .put("users", JSONObject().put("sortCategory", "postcount"))
                    .put("lists", JSONObject().put("sortCategory", "follows"))
                    .put("filter", "")
                    .put("sort", 0)
                    .put("randomizer", 0),
            )
            .put("useCache", true)

    /** The search endpoint named by the first POST fetch in the site's Next.js chunks. */
    private fun discover(): String {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$SITE/")
        val home = request("$SITE/", headers, null, 2 * 1024 * 1024)
        if (home != null) {
            for (src in SCRIPT.findAll(home).map { it.groupValues[1] }.distinct()) {
                val url = if (src.startsWith("/")) SITE + src else src
                val script = request(url, headers, null, 8 * 1024 * 1024) ?: continue
                val match = POST_FETCH.find(script) ?: continue
                return "/api/" + match.groupValues[1].trimEnd('/')
            }
        }
        Log.w("MielLibrary", "HowLongToBeat search path not found, using $FALLBACK_PATH")
        return FALLBACK_PATH
    }

    /** The response body, or null for a status other than 200. A `body` makes it a POST. */
    private fun request(
        url: String,
        headers: Map<String, String>,
        body: String?,
        limit: Int,
    ): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.instanceFollowRedirects = false
        headers.forEach(connection::setRequestProperty)
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != 200) return null
            return String(connection.inputStream.use { readLimited(it, limit) }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("hltb", Context.MODE_PRIVATE)
}
