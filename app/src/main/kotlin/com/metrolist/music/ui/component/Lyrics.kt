/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.LyricsClickKey
import com.metrolist.music.constants.LyricsRenderer
import com.metrolist.music.constants.LyricsRendererKey
import com.metrolist.music.constants.LyricsRomanizeCyrillicByLineKey
import com.metrolist.music.constants.LyricsRomanizeList
import com.metrolist.music.constants.LyricsTextSizeKey
import com.metrolist.music.constants.ShowIntervalIndicatorKey
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.spicy.DynamicLyricsBackground
import com.metrolist.music.lyrics.spicy.RenderConfig
import com.metrolist.music.lyrics.spicy.canvas.LyricsView
import com.metrolist.music.lyrics.spicy.models.Line
import com.metrolist.music.lyrics.spicy.models.LineRole
import com.metrolist.music.lyrics.spicy.models.LyricsFooter
import com.metrolist.music.lyrics.spicy.models.LyricsProvenance
import com.metrolist.music.lyrics.spicy.models.LyricsType
import com.metrolist.music.lyrics.spicy.models.Word
import com.metrolist.music.lyrics.spicy.models.buildDisplayTimeline
import com.metrolist.music.lyrics.spicy.parser.TtmlLyricsParser
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.ui.screens.settings.defaultList
import com.metrolist.music.ui.utils.fadingEdge
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LyricsViewModel

@Composable
fun Lyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyrics: Boolean,
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
) {
    val lyricsRenderer by rememberEnumPreference(
        key = LyricsRendererKey,
        defaultValue = LyricsRenderer.SPICY,
    )

    when (lyricsRenderer) {
        LyricsRenderer.METRO -> {
            ExperimentalLyrics(
                sliderPositionProvider = sliderPositionProvider,
                modifier = modifier,
                showLyrics = showLyrics,
                lyricsViewModel = lyricsViewModel,
            )
        }
        LyricsRenderer.SPICY -> {
            SpicyLyricsScreen(
                sliderPositionProvider = sliderPositionProvider,
                modifier = modifier,
                showLyrics = showLyrics,
                lyricsViewModel = lyricsViewModel,
            )
        }
    }
}

@Composable
fun SpicyLyricsScreen(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyrics: Boolean,
    lyricsViewModel: LyricsViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.isInRoom == true && !listenTogetherManager.isHost

    val changeLyrics by rememberPreference(LyricsClickKey, defaultValue = true)
    val lyricsTextSize by rememberPreference(LyricsTextSizeKey, defaultValue = 24f)
    val romanizeLyricsList = rememberPreference(LyricsRomanizeList, defaultValue = "")
    val romanizeCyrillicByLine by rememberPreference(LyricsRomanizeCyrillicByLineKey, defaultValue = false)
    val showIntervalIndicator by rememberPreference(ShowIntervalIndicatorKey, defaultValue = true)

    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val lyrics = remember(currentLyricsEntity) { currentLyricsEntity?.lyrics?.trim() }

    val enabledLanguages = remember(romanizeLyricsList.value) {
        if (romanizeLyricsList.value.isEmpty()) {
            defaultList
        } else {
            romanizeLyricsList.value.split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) parts[0] to parts[1].toBoolean() else null
            }
        }.filter { it.second }.map { it.first }
    }

    val lines by lyricsViewModel.lines.collectAsStateWithLifecycle()

    LaunchedEffect(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator) {
        lyricsViewModel.processLyrics(lyrics, enabledLanguages, romanizeCyrillicByLine, showIntervalIndicator)
    }

    val lyricsOffset = currentSong?.song?.lyricsOffset?.toLong() ?: 0L

    val spicyLines = remember(lines, lyrics) {
        if (lyrics != null && (lyrics.startsWith("<tt") || lyrics.startsWith("<?xml"))) {
            TtmlLyricsParser.parse(lyrics.byteInputStream()).lines
        } else {
            lines.mapIndexed { index, entry ->
                val nextTime = lines.getOrNull(index + 1)?.time
                val spicyWords = if (!entry.words.isNullOrEmpty()) {
                    entry.words.mapIndexed { idx, wt ->
                        val isLast = idx == entry.words.size - 1
                        Word(
                            text = wt.text,
                            startMs = (wt.startTime * 1000).toLong(),
                            endMs = (wt.endTime * 1000).toLong(),
                            isPartOfWord = !isLast && !wt.text.endsWith(" "),
                        )
                    }
                } else {
                    val lineEnd = nextTime ?: (entry.time + 4000L)
                    listOf(
                        Word(
                            text = entry.text,
                            startMs = entry.time,
                            endMs = lineEnd,
                        )
                    )
                }
                val role = if (entry.isBackground) LineRole.BACKGROUND
                           else LineRole.LEAD
                val endMs = spicyWords.lastOrNull()?.endMs ?: (nextTime ?: (entry.time + 4000L))
                Line(
                    words = spicyWords,
                    startMs = entry.time,
                    endMs = endMs,
                    agent = entry.agent,
                    role = role,
                    oppositeAligned = entry.agent == "v2",
                )
            }
        }
    }

    val displayTimeline = remember(spicyLines) {
        buildDisplayTimeline(spicyLines, minimalMode = false, holdThroughShortGaps = false)
    }

    val hasSyllableSync = remember(spicyLines) {
        spicyLines.any { it.words.size > 1 || (it.words.firstOrNull()?.duration ?: 0L) > 0L }
    }

    val docId = remember(currentSong?.song?.id, lyrics) {
        "${currentSong?.song?.id ?: ""}|${currentLyricsEntity?.provider ?: ""}|${lyrics?.hashCode() ?: 0}"
    }

    val renderConfig = remember {
        RenderConfig(
            simpleLyricsMode = false,
            minimalLyricsMode = false,
            glowEnabled = true,
            appleMusic = false,
        )
    }

    val spicyFooter = remember(currentLyricsEntity?.provider) {
        LyricsFooter(
            provenance = LyricsProvenance(
                provider = currentLyricsEntity?.provider ?: "Spicy Lyrics"
            )
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        DynamicLyricsBackground(
            mediaMetadata = mediaMetadata,
            modifier = Modifier.fillMaxSize(),
            isPlaying = isPlaying,
            animate = showLyrics && isPlaying,
            blurIntensity = 60,
        )

        if (lyrics == LYRICS_NOT_FOUND) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.alpha(0.5f),
                )
            }
        } else if (lyrics == null) {
            Column(modifier = Modifier.padding(top = 100.dp)) {
                ShimmerHost {
                    repeat(10) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp),
                        ) {
                            TextPlaceholder()
                        }
                    }
                }
            }
        } else {
            LyricsView(
                lines = displayTimeline,
                documentId = docId,
                footer = spicyFooter,
                currentTimeMs = {
                    val pos = sliderPositionProvider() ?: playerConnection.player.currentPosition
                    (pos + lyricsOffset).coerceAtLeast(0L)
                },
                onSeekWord = { seekTimeMs ->
                    if (changeLyrics && !isGuest) {
                        playerConnection.seekTo((seekTimeMs - lyricsOffset).coerceAtLeast(0L))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .fadingEdge(vertical = 48.dp),
                fontSizeScale = (lyricsTextSize / 24f).coerceIn(0.7f, 1.8f),
                config = renderConfig,
                lyricsType = if (hasSyllableSync) LyricsType.Syllable else LyricsType.Line,
                isPlaying = isPlaying,
            )
        }
    }
}
