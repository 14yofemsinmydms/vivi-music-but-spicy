package com.metrolist.music.lyrics.spicy.api

import com.metrolist.music.lyrics.spicy.api.LyricsEnvelope
import com.metrolist.music.betterlyrics.TTMLParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import timber.log.Timber

object SpicyLyrics {
    private const val BASE_URL = "https://api.spicylyrics.org"
    private const val TAG = "SpicyLyrics"
    const val DEFAULT_API_KEY = "sl_sk_bwgEcADuZYJT3wwUIxpATi9F7X8MDPV5NS-hgVq9v4o"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }
            defaultRequest {
                url(BASE_URL)
            }
            expectSuccess = false
        }
    }

    private val spotifyResolver by lazy { SpotifyTrackResolver() }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        apiKey: String? = null,
        directSpotifyId: String? = null,
    ): Result<String> = runCatching {
        // Step 1: Resolve Spotify Track ID if not directly provided
        val spotifyTrackId = directSpotifyId ?: spotifyResolver.resolve(
            LocalTrackMetadata(
                title = title,
                artist = artist,
                album = album.orEmpty(),
                durationMs = duration.toLong() * 1000L
            )
        ) ?: throw IllegalStateException("Could not resolve Spotify track ID for $title by $artist")

        Timber.tag(TAG).d("Resolved Spotify Track ID: $spotifyTrackId for '$title' by '$artist'")

        // Step 2: Fetch lyrics from Spicy Lyrics API
        val effectiveKey = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_API_KEY
        val response = client.get("/v1/lyrics/$spotifyTrackId") {
            if (effectiveKey.isNotBlank()) {
                header("Authorization", "Bearer $effectiveKey")
            }
        }

        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("Spicy Lyrics API returned HTTP ${response.status.value}")
        }

        val envelope = response.body<LyricsEnvelope>()
        val body = envelope.body ?: throw IllegalStateException("Spicy Lyrics returned empty response body")

        val attributionHeader = buildString {
            append("[attribution:source=${body.source}")
            val uploader = body.uploadAttribution?.uploader
            val maker = body.uploadAttribution?.maker
            if (uploader != null) {
                append("|uploader=${uploader.username}|uploaderUrl=${uploader.url}")
            }
            if (maker != null) {
                append("|maker=${maker.username}|makerUrl=${maker.url}")
            }
            append("]")
        }

        when (body.type.lowercase()) {
            "syllable" -> {
                val parsedLines = SpicyLyricsTtmlConverter.convertToParsedLines(body)
                if (parsedLines.isEmpty()) {
                    throw IllegalStateException("Failed to parse syllable lyrics")
                }
                val lrc = TTMLParser.toLRC(parsedLines)
                "$attributionHeader\n$lrc"
            }
            "line" -> {
                val parsedLines = SpicyLyricsTtmlConverter.convertLineLyrics(body)
                if (parsedLines.isEmpty()) {
                    throw IllegalStateException("Failed to parse line lyrics")
                }
                val lrc = TTMLParser.toLRC(parsedLines)
                "$attributionHeader\n$lrc"
            }
            "static" -> {
                val staticLyrics = SpicyLyricsTtmlConverter.convertStaticLyrics(body)
                if (staticLyrics.isBlank()) {
                    throw IllegalStateException("Static lyrics were empty")
                }
                "$attributionHeader\n$staticLyrics"
            }
            else -> {
                throw IllegalStateException("Unknown lyrics type: ${body.type}")
            }
        }
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        apiKey: String? = null,
        callback: (String) -> Unit,
    ) {
        getLyrics(title, artist, duration, album, apiKey)
            .onSuccess { lrcString ->
                callback(lrcString)
            }
            .onFailure {
                Timber.tag(TAG).w(it, "getAllLyrics failed for '$title' by '$artist'")
            }
    }
}
