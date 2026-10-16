/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableSpicyLyricsKey
import com.metrolist.music.constants.SpicyLyricsApiKey
import com.metrolist.music.lyrics.spicy.api.SpicyLyrics
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import timber.log.Timber

object SpicyLyricsProvider : LyricsProvider {
    private const val TAG = "SpicyLyricsProvider"

    override val name = "SpicyLyrics"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableSpicyLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        Timber.tag(TAG).d("getLyrics: title='$title', artist='$artist', duration=$duration")
        val apiKey = context.dataStore[SpicyLyricsApiKey]?.trim()?.takeIf { it.isNotEmpty() } ?: SpicyLyrics.DEFAULT_API_KEY
        return SpicyLyrics.getLyrics(
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            apiKey = apiKey,
        )
    }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        Timber.tag(TAG).d("getAllLyrics called: title='$title', artist='$artist'")
        val apiKey = context.dataStore[SpicyLyricsApiKey]?.trim()?.takeIf { it.isNotEmpty() } ?: SpicyLyrics.DEFAULT_API_KEY
        SpicyLyrics.getAllLyrics(
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            apiKey = apiKey,
            callback = callback,
        )
    }
}
