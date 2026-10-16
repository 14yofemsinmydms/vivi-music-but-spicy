package com.metrolist.music.lyrics.spicy.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class LocalTrackMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
)

data class SpotifyTrackCandidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
)

data class ScoredSpotifyTrack(
    val candidate: SpotifyTrackCandidate,
    val score: Int,
    val titleSimilarity: Double,
    val artistSimilarity: Double,
    val durationDeltaMs: Long,
    val hasVersionConflict: Boolean,
)

internal data class SpotifyAnonymousSession(
    val accessToken: String,
    val expiresAtMs: Long,
)

class SpotifyTrackResolver(
    private val client: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 15000
        }
        defaultRequest {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        }
    }
) {
    private val tokenMutex = Mutex()
    @Volatile private var cachedSession: SpotifyAnonymousSession? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val trackIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun resolve(track: LocalTrackMetadata): String? = withContext(Dispatchers.IO) {
        val cacheKey = "${track.title.lowercase(Locale.ROOT).trim()}|${track.artist.lowercase(Locale.ROOT).trim()}"
        trackIdCache[cacheKey]?.let { return@withContext it }
        val candidates = search(track)
        val winner = resolveBestCandidate(track, candidates)
        val id = winner?.candidate?.id
        if (id != null) {
            trackIdCache[cacheKey] = id
        }
        id
    }

    suspend fun search(track: LocalTrackMetadata): List<SpotifyTrackCandidate> = withContext(Dispatchers.IO) {
        val query = "${cleanSearchTerm(track.title)} ${cleanSearchTerm(track.artist)}".trim()
        if (query.isBlank()) return@withContext emptyList()

        try {
            searchOnce(query)
        } catch (e: Exception) {
            Timber.tag("SpotifyResolver").w(e, "Spotify search failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun searchOnce(query: String): List<SpotifyTrackCandidate> {
        val session = getSession()
        val response = executeSearch(query, session.accessToken)
        if (response.statusCode == 401) {
            invalidate(session.accessToken)
            val refreshed = getSession()
            val retryResponse = executeSearch(query, refreshed.accessToken)
            return retryResponse.candidates
        }
        return response.candidates
    }

    private suspend fun getSession(): SpotifyAnonymousSession {
        val now = System.currentTimeMillis()
        cachedSession?.takeIf { it.expiresAtMs - EXPIRY_SKEW_MS > now }?.let { return it }

        return tokenMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            cachedSession?.takeIf { it.expiresAtMs - EXPIRY_SKEW_MS > lockedNow }
                ?: bootstrapSession().also { cachedSession = it }
        }
    }

    private fun invalidate(rejectedToken: String) {
        if (cachedSession?.accessToken == rejectedToken) {
            cachedSession = null
        }
    }

    private suspend fun bootstrapSession(): SpotifyAnonymousSession {
        val response = client.get(BOOTSTRAP_URL)
        val html = response.bodyAsText()
        return parseAnonymousSession(html)
    }

    private fun parseAnonymousSession(html: String): SpotifyAnonymousSession {
        val nextDataRegex = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match = nextDataRegex.find(html)?.groupValues?.get(1)
            ?: throw IOException("Spotify embed page does not contain __NEXT_DATA__")

        val root = json.parseToJsonElement(match).jsonObject
        val session = root["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject
            ?.get("state")?.jsonObject
            ?.get("settings")?.jsonObject
            ?.get("session")?.jsonObject
            ?: throw IOException("Failed to extract Spotify session from embed props")

        val token = session["accessToken"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Spotify access token missing")
        val expiry = session["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() + 3600_000L)

        return SpotifyAnonymousSession(token, expiry)
    }

    private data class SearchResponse(
        val statusCode: Int,
        val candidates: List<SpotifyTrackCandidate>,
    )

    private suspend fun executeSearch(query: String, token: String): SearchResponse {
        val variables = buildJsonObject {
            put("searchTerm", query)
            put("offset", 0)
            put("limit", 20)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", true)
        }.toString()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", SEARCH_SHA256)
            }
        }.toString()

        val response = client.get(PATHFINDER_URL) {
            parameter("operationName", SEARCH_OPERATION)
            parameter("variables", variables)
            parameter("extensions", extensions)
            header("Authorization", "Bearer $token")
            header("app-platform", "WebPlayer")
            header("Origin", "https://open.spotify.com")
            header("Referer", "https://open.spotify.com/")
        }

        if (response.status.value == 401) {
            return SearchResponse(401, emptyList())
        }

        val bodyText = response.bodyAsText()
        val candidates = parseSearchCandidates(bodyText)
        return SearchResponse(response.status.value, candidates)
    }

    private fun parseSearchCandidates(body: String): List<SpotifyTrackCandidate> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val tracksV2 = root["data"]?.jsonObject
            ?.get("searchV2")?.jsonObject
            ?.get("tracksV2")?.jsonObject
            ?.get("items")?.jsonArray
            ?: return emptyList()

        return tracksV2.mapNotNull { element ->
            val data = element.jsonObject["item"]?.jsonObject?.get("data")?.jsonObject ?: return@mapNotNull null
            val id = data["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = data["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val duration = data["duration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull ?: 0L
            val artists = data["artists"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull { artistItem ->
                artistItem.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            }.orEmpty()
            val album = data["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()

            SpotifyTrackCandidate(
                id = id,
                title = title,
                artists = artists,
                album = album,
                durationMs = duration
            )
        }
    }

    private fun resolveBestCandidate(
        source: LocalTrackMetadata,
        candidates: List<SpotifyTrackCandidate>
    ): ScoredSpotifyTrack? {
        val ranked = candidates
            .distinctBy { it.id }
            .map { score(source, it) }
            .filter { it.score >= 50 }
            .sortedWith(
                compareByDescending<ScoredSpotifyTrack> { it.score }
                    .thenBy { it.durationDeltaMs }
            )
        return ranked.firstOrNull()
    }

    private fun score(source: LocalTrackMetadata, candidate: SpotifyTrackCandidate): ScoredSpotifyTrack {
        val sourceTitle = normalize(source.title)
        val candTitle = normalize(candidate.title)
        val titleSim = similarity(sourceTitle, candTitle)

        val sourceArtist = normalize(source.artist)
        val candArtist = candidate.artists.joinToString(" ") { normalize(it) }
        val artistSim = similarity(sourceArtist, candArtist)

        val durationDelta = if (source.durationMs > 0 && candidate.durationMs > 0) {
            abs(source.durationMs - candidate.durationMs)
        } else 0L

        val durationScore = if (source.durationMs > 0 && candidate.durationMs > 0) {
            (1.0 - (durationDelta.toDouble() / 15_000.0).coerceIn(0.0, 1.0))
        } else 0.8

        val totalScore = ((titleSim * 55.0) + (artistSim * 30.0) + (durationScore * 15.0)).roundToInt()

        return ScoredSpotifyTrack(
            candidate = candidate,
            score = totalScore,
            titleSimilarity = titleSim,
            artistSimilarity = artistSim,
            durationDeltaMs = durationDelta,
            hasVersionConflict = false
        )
    }

    private fun similarity(s1: String, s2: String): Double {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0
        if (s1.contains(s2) || s2.contains(s1)) return 0.85

        val distance = levenshtein(s1, s2)
        val maxLen = max(s1.length, s2.length)
        return 1.0 - (distance.toDouble() / maxLen.toDouble())
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), "")
            .trim()

    private fun cleanSearchTerm(term: String): String {
        return term.replace(Regex("""\s*\(.*?(official|video|audio|lyrics|hd|4k|remix|version).*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[.*?(official|video|audio|lyrics|hd|4k|remix|version).*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private companion object {
        const val BOOTSTRAP_TRACK_ID = "4uLU6hMCjMI75M1A2tKUQC"
        const val BOOTSTRAP_URL = "https://open.spotify.com/embed/track/$BOOTSTRAP_TRACK_ID"
        const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        const val SEARCH_OPERATION = "searchDesktop"
        const val SEARCH_SHA256 = "eff59fa0a3d026b88b56fddbcf4bdfa16a186b8175a5c1a358c072e053c2e5b0"
        const val EXPIRY_SKEW_MS = 60_000L
    }
}
