/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics.spicy

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.lyrics.spicy.canvas.DynamicBackgroundView
import com.metrolist.music.lyrics.spicy.canvas.kawarp.KawarpBackground
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified dynamic lyrics background composable.
 * Places the AGSL Kawarp shader engine (Android 13+) or 4-circle rotating blur engine (Android < 13 / AGSL fallback)
 * behind the lyrics canvas.
 *
 * Automatically suspends frame rendering when [animate] is false (e.g. playback is paused or lyrics screen is closed)
 * to save CPU/GPU cycles and battery life.
 */
@Composable
fun DynamicLyricsBackground(
    mediaMetadata: MediaMetadata?,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    animate: Boolean = true,
    blurIntensity: Int = 60,
    speed: (() -> Float?)? = null,
) {
    val context = LocalContext.current
    var coverArtBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(mediaMetadata?.id, mediaMetadata?.thumbnailUrl) {
        val url = mediaMetadata?.thumbnailUrl
        if (url == null) {
            coverArtBitmap = null
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(256, 256)
                .allowHardware(false)
                .build()
            runCatching { context.imageLoader.execute(request).image?.toBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            coverArtBitmap = bitmap
        }
    }

    val canUseKawarp = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // Test if AGSL RuntimeShader is supported on device
                val testShader = android.graphics.RuntimeShader("half4 main(float2 fragCoord) { return half4(0.0); }")
                testShader != null
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (canUseKawarp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            KawarpBackground(
                coverArtBitmap = coverArtBitmap,
                modifier = Modifier.fillMaxSize(),
                isPlaying = isPlaying,
                animate = animate,
                blurIntensity = blurIntensity,
                speed = speed,
            )
        } else {
            DynamicBackgroundView(
                coverArtBitmap = coverArtBitmap,
                modifier = Modifier.fillMaxSize(),
                blurIntensity = blurIntensity,
                animate = animate,
            )
        }
    }
}
