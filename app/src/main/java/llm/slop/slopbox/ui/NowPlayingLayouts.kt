/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import llm.slop.slopbox.SettingsStore

/**
 * The While Playing Screen layouts, chosen by Rockbox's "wps" setting
 * (Theme Settings › While Playing Screen). SlopBox draws them itself rather
 * than reading .wps skins; [skin] is the name saved in config.cfg.
 */
enum class WpsLayout(val skin: String, val title: String, val about: String) {
    CABBIE("cabbiev2", "Cabbie", "cabbiev2's screen: art, track lines, all the controls"),
    BIG_ART("slop-bigart", "Big Art", "Art across the screen with the track over it, on a blurred backdrop"),
    LARGE("slop-large", "Large", "No art; big type and big buttons, for the car or the gym"),
    MINIMAL("slop-minimal", "Minimal", "No art; the track, the bar, skip and play"),
    ;

    companion object {
        fun of(store: SettingsStore): WpsLayout {
            val v = store.catalog.byCfg["wps"]?.let { store.text(it) }.orEmpty()
            val name = v.substringAfterLast('/').removeSuffix(".wps")
            return entries.firstOrNull { it.skin == name } ?: CABBIE
        }
    }
}

/** The part of the screen above the progress bar, for the layouts other than cabbiev2. */
@Composable
internal fun AltMiddle(
    layout: WpsLayout,
    art: ImageBitmap?,
    album: String, title: String, artist: String, codec: String, next: String,
    gestures: Modifier,
    overlay: (@Composable () -> Unit)?,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            layout == WpsLayout.BIG_ART && art != null -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
              // As big a square as fits, leaving room for the next-track line.
              val side = androidx.compose.ui.unit.min(maxWidth, maxHeight - 40.dp)
              Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(side - 8.dp)
                    .clip(RoundedCornerShape(8.dp)).then(gestures)) {
                    Image(art, contentDescription = "Album art", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                            .padding(start = 14.dp, end = 14.dp, top = 40.dp, bottom = 12.dp),
                    ) {
                        val white = Cabbie.body.copy(color = Color.White)
                        BasicText(title, style = white.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        BasicText(artist, style = white, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(listOf(album, codec).filter { it.isNotEmpty() }.joinToString("  ·  "),
                            style = white.copy(fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f, fill = true).height(0.dp))
                Line(next, scroll = true, dim = true)
              }
            }
            layout == WpsLayout.LARGE || layout == WpsLayout.BIG_ART -> Column(
                Modifier.fillMaxSize().then(gestures), verticalArrangement = Arrangement.Center,
            ) {
                val c = Cabbie.body.copy(textAlign = TextAlign.Center)
                BasicText(title, Modifier.fillMaxWidth(), style = c.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                BasicText(artist, Modifier.fillMaxWidth(), style = c.copy(fontSize = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                BasicText(album, Modifier.fillMaxWidth(), style = c.copy(fontSize = 20.sp, color = Cabbie.Dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(20.dp))
                BasicText(next, Modifier.fillMaxWidth(), style = c.copy(fontSize = 16.sp, color = Cabbie.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> Column(Modifier.fillMaxSize().then(gestures), verticalArrangement = Arrangement.Center) {
                Line(album, scroll = true, dim = true)
                Line(title, scroll = true, big = true)
                Line(artist, scroll = true)
            }
        }
        if (overlay != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp)
            .background(Cabbie.palette.bg.copy(alpha = 0.85f), RoundedCornerShape(10.dp))) { overlay() }
    }
}

/** Big Art's backdrop: the art, blurred and darkened, behind the whole screen (Android 12 and later). */
@Composable
internal fun BlurredArt(art: ImageBitmap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    Box(Modifier.fillMaxSize()) {
        Image(art, contentDescription = null, Modifier.fillMaxSize().blur(40.dp), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Cabbie.palette.bg.copy(alpha = 0.6f)))
    }
}

/** A colour that stands for the album art: the commonest strong hue, for Album Art Colours. */
object ArtAccent {
    fun of(art: ImageBitmap): Color? {
        val bmp = android.graphics.Bitmap.createScaledBitmap(art.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false), 32, 32, true)
        val bins = Array(12) { FloatArray(4) } // weight, r, g, b
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            val c = bmp.getPixel(x, y)
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.3f || hsv[2] < 0.3f) continue
            val w = hsv[1] * hsv[2]
            val b = bins[((hsv[0] / 30f).toInt()).coerceIn(0, 11)]
            b[0] += w; b[1] += w * android.graphics.Color.red(c); b[2] += w * android.graphics.Color.green(c); b[3] += w * android.graphics.Color.blue(c)
        }
        val best = bins.maxByOrNull { it[0] } ?: return null
        if (best[0] < 8f) return null // too grey to pick a colour
        // Averaging greys the hue out; lift it back to a strong, bright colour.
        val avg = android.graphics.Color.rgb((best[1] / best[0]).toInt(), (best[2] / best[0]).toInt(), (best[3] / best[0]).toInt())
        android.graphics.Color.colorToHSV(avg, hsv)
        hsv[1] = maxOf(hsv[1], 0.75f); hsv[2] = maxOf(hsv[2], 0.9f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}
