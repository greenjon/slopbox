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

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.SlopBoxSettings
import llm.slop.slopbox.ui.db.TagDb

/**
 * The Rater While Playing Screen (SlopBox's own; Rockbox skins can show a
 * rating but not set one). One tap gives the playing track 1 to 5 stars,
 * stored as Rockbox ratings (stars × 2, the 0-10 scale of Set Song Rating),
 * and database_changelog.txt is exported after each rating so a laptop can
 * read them off the card. Two settings, in the context menu while this layout
 * is on: Skip Rated Tracks (SlopPlayer passes over rated tracks) and Next
 * Track After Rating.
 */
object Rater {
    /** Is the Rater layout the While Playing Screen? */
    fun active(store: SettingsStore) = WpsLayout.of(store) == WpsLayout.RATER
    fun skipRated(store: SettingsStore) = store.int(SlopBoxSettings.RATER_SKIP_RATED) != 0
    fun advance(store: SettingsStore) = store.int(SlopBoxSettings.RATER_NEXT) != 0
    /** SlopPlayer: pass over rated tracks on a track change? */
    fun skipping(store: SettingsStore) = active(store) && skipRated(store)

    fun rated(mediaId: String) = TagDb.statsOf(mediaId).rating > 0

    /** A note under the track (not in the database, last one rated...); cleared on a track change. */
    var message by mutableStateOf<String?>(null)
    var exportFailed by mutableStateOf(false)
        private set

    private val undo = ArrayDeque<Pair<String, Int>>()
    var undoCount by mutableIntStateOf(0)
        private set

    /** The next queue entry after [from], passing over rated ones with Skip Rated on; -1 at the end. */
    private fun nextIndex(store: SettingsStore, c: MediaController, from: Int): Int {
        var j = from + 1
        while (j < c.mediaItemCount && skipRated(store) && rated(c.getMediaItemAt(j).mediaId)) j++
        return if (j < c.mediaItemCount) j else -1
    }

    /** A swipe on the Rater layout: the next track (unrated, with Skip Rated on), not a Skip Length hop. */
    fun next(ctx: Context, c: MediaController) {
        val store = SettingsStore.get(ctx)
        val j = nextIndex(store, c, c.currentMediaItemIndex)
        if (j < 0) { message = if (skipRated(store)) "No unrated tracks after this one" else "End of the playlist"; return }
        c.seekTo(j, 0); c.play()
    }

    fun previous(c: MediaController) {
        if (c.currentMediaItemIndex <= 0) { message = "Start of the playlist"; return }
        c.seekTo(c.currentMediaItemIndex - 1, 0); c.play()
    }

    fun rate(ctx: Context, c: MediaController, stars: Int) {
        val store = SettingsStore.get(ctx)
        val mid = c.currentMediaItem?.mediaId ?: return
        if (TagDb.track(mid) == null) { message = "Not in the database: Database › Update Now"; return }
        undo.addLast(mid to TagDb.statsOf(mid).rating); if (undo.size > 50) undo.removeFirst(); undoCount = undo.size
        TagDb.setRating(ctx, mid, stars * 2)
        scheduleExport(ctx)
        if (!advance(store)) return
        // By queue index, not seekToNext(): that would hop by Skip Length.
        val j = nextIndex(store, c, c.currentMediaItemIndex)
        if (j < 0) { c.pause(); message = "That was the last one to rate in this playlist" }
        else { c.seekTo(j, 0); c.play() }
    }

    fun undoLast(ctx: Context, c: MediaController) {
        val (mid, prev) = undo.removeLastOrNull() ?: return
        undoCount = undo.size
        TagDb.setRating(ctx, mid, prev)
        scheduleExport(ctx)
        val i = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == mid }
        if (i != null && i != c.currentMediaItemIndex) { c.seekTo(i, 0); c.play() }
    }

    // The changelog export, a moment after the last rating, outliving the screen.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private fun scheduleExport(ctx: Context) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            delay(1500)
            exportFailed = !TagDb.exportChangelog(app)
        }
    }

    fun stars(rating: Int) = (rating + 1) / 2
    val StarColors = listOf(Color(0xFFC62828), Color(0xFFE65100), Color(0xFFF9A825), Color(0xFF7CB342), Color(0xFF2E7D32))
    val StarWords = listOf("remove", "", "revisit", "", "keep")
}

/** The Rater layout's lines under the track: the stars, and a note when there is one. */
@Composable
internal fun RaterStatus(mediaId: String?) {
    @Suppress("UNUSED_VARIABLE") val rev = TagDb.statsRevision   // redraw when a rating changes
    val inDb = mediaId != null && TagDb.track(mediaId) != null
    val rating = mediaId?.let { TagDb.statsOf(it).rating } ?: 0
    val s = Rater.stars(rating)
    val c = Cabbie.body.copy(textAlign = TextAlign.Center)
    BasicText(if (rating > 0) "★".repeat(s) + "☆".repeat(5 - s) else "unrated", Modifier.fillMaxWidth().padding(top = 8.dp),
        style = c.copy(color = if (rating > 0) Cabbie.Gold else Cabbie.Dim, fontSize = 26.sp))
    val note = when {
        Rater.exportFailed -> "Couldn't write ${TagDb.CHANGELOG} to the music folder"
        mediaId != null && !inDb -> "Not in the database, so it can't be rated: Database › Update Now"
        else -> Rater.message
    }
    note?.let { BasicText(it, Modifier.fillMaxWidth().padding(top = 4.dp), style = Cabbie.small.copy(color = Cabbie.Gold, textAlign = TextAlign.Center)) }
}

/** The Rater layout's keys: Undo, then 1 to 5 stars. */
@Composable
internal fun RaterKeys(controller: MediaController?, mediaId: String?) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val rev = TagDb.statsRevision
    val inDb = mediaId != null && TagDb.track(mediaId) != null
    val rating = mediaId?.let { TagDb.statsOf(it).rating } ?: 0
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (n in 1..5) {
                RaterKey("$n", Rater.StarColors[n - 1], Rater.StarWords[n - 1], enabled = inDb,
                    selected = Rater.stars(rating) == n) { controller?.let { Rater.rate(ctx, it, n) } }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            RaterKey("Undo", null, "", enabled = Rater.undoCount > 0, height = 40) { controller?.let { Rater.undoLast(ctx, it) } }
        }
    }
}

@Composable
private fun RowScope.RaterKey(
    text: String, color: Color?, sub: String, enabled: Boolean, selected: Boolean = false, height: Int = 76,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color(0xFF2A2A2A)
        color != null -> color
        else -> Color(0xFF3C3C3C)
    }
    Box(
        Modifier.weight(1f).height(height.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.18f)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(text, style = Cabbie.body.copy(
                color = if (enabled) Color.White else Cabbie.Dim, fontSize = if (color != null) 26.sp else 16.sp,
                fontWeight = if (color != null) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center), maxLines = 1)
            if (sub.isNotEmpty()) BasicText(sub, style = Cabbie.small.copy(color = if (enabled) Color.White else Cabbie.Dim, fontSize = 11.sp),
                maxLines = 1)
        }
    }
}
