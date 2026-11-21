/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/bookmark.c - Copyright (C) 2003 by Benjamin Metzler
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package llm.slop.slopbox.ui

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import llm.slop.slopbox.Bookmarks
import llm.slop.slopbox.PlaybackService
import llm.slop.slopbox.SettingsStore

/** Which bookmark list the list screen shows: a playlist's, or the recent ones. */
object BookmarkView {
    var playlist by mutableStateOf<String?>(null)
}

private fun str(ctx: android.content.Context, id: String) = SettingsStore.get(ctx).catalog.str(id)

/** The playing playlist's bookmark name, if it's a folder playlist (Rockbox: bookmarkable). */
fun bookmarkPlaylistOf(c: MediaController?): String? {
    c ?: return null
    if (c.mediaItemCount == 0) return null
    fun dir(i: Int): String? = c.getMediaItemAt(i).requestMetadata.extras?.getString(MusicTree.EXTRA_DIR)
    val d = dir(0) ?: return null
    return if ((0 until c.mediaItemCount).all { dir(it) == d }) Bookmarks.playlistName(d) else null
}

/** Rockbox's WPS context menu › Bookmarks: Create Bookmark, List Bookmarks. */
@Composable
fun BookmarksMenu(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val playlist = llm.slop.slopbox.SlopPlayer.current?.bookmarkPlaylist ?: bookmarkPlaylistOf(controller)
    Column(Modifier.fillMaxSize()) {
        TitleBar(str(ctx, "LANG_BOOKMARK_MENU"), onBack = { nav.pop() })
        RockRow(str(ctx, "LANG_BOOKMARK_MENU_CREATE"), icon = G.Cursor, enabled = playlist != null) {
            controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_BOOKMARK_CREATE, Bundle.EMPTY), Bundle.EMPTY)
            nav.pop()
        }
        RockRow(str(ctx, "LANG_BOOKMARK_MENU_LIST"), icon = G.Cursor, enabled = playlist != null) {
            if (Bookmarks(ctx).list(playlist!!).isEmpty()) {
                android.widget.Toast.makeText(ctx, str(ctx, "LANG_BOOKMARK_LOAD_EMPTY"), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                BookmarkView.playlist = playlist
                nav.push(Screen.BookmarkList)
            }
        }
    }
}

/** Rockbox select_bookmark(): two lines per bookmark; tap resumes, hold deletes. */
@Composable
fun BookmarkListScreen(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val store = remember { Bookmarks(ctx) }
    val playlist = BookmarkView.playlist
    var rev by remember { mutableIntStateOf(0) }
    val list = remember(rev, playlist) { if (playlist == null) store.recent() else store.list(playlist) }
    val title = if (playlist == null) str(ctx, "LANG_BOOKMARK_MENU_RECENT_BOOKMARKS") else str(ctx, "LANG_BOOKMARK_SELECT_BOOKMARK")
    Column(Modifier.fillMaxSize()) {
        TitleBar(title, onBack = { nav.pop() })
        if (list.isEmpty()) ListNote(str(ctx, "LANG_BOOKMARK_LOAD_EMPTY"))
        RockList(Modifier.fillMaxSize()) {
            itemsIndexed(list) { _, bm ->
                RockRow(
                    bookmarkLine1(bm, showPlaylist = playlist == null),
                    sub = bookmarkLine2(ctx, bm),
                    icon = G.Cursor,
                    onLongClick = { store.delete(playlist, bm); rev++ },
                ) {
                    controller?.sendCustomCommand(
                        SessionCommand(PlaybackService.CMD_BOOKMARK_PLAY, Bundle.EMPTY),
                        Bundle().apply { putString(PlaybackService.ARG_LINE, bm.line) },
                    )
                    nav.toNowPlaying()
                }
            }
        }
    }
}

/** Rockbox get_bookmark_info() line 1: "Album : Track" in the recent list, else "Track". */
private fun bookmarkLine1(bm: Bookmarks.Bookmark, showPlaylist: Boolean): String {
    if (!showPlaylist) return bm.title
    val name = bm.playlist.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    return "$name : ${bm.title}"
}

/** Line 2: "time, track number[, Shuffle]". */
private fun bookmarkLine2(ctx: android.content.Context, bm: Bookmarks.Bookmark): String {
    val s = bm.elapsedMs / 1000
    val time = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    return "$time, ${bm.index + 1}" + if (bm.shuffle) str(ctx, "LANG_BOOKMARK_SHUFFLE") else ""
}

/** A question from the player (PlaybackService.CMD_ASK), drawn over the screen. */
class AskRequest(val kind: String, val lines: List<String>, val answer: (Int) -> Unit)

@Composable
fun AskOverlay(req: AskRequest) {
    val ctx = LocalContext.current
    Backdrop(Modifier.background(Cabbie.backdrop)) {
        Column(Modifier.fillMaxSize()) {
            if (req.kind == llm.slop.slopbox.SlopPlayer.ASK_CREATE) {
                // Rockbox yesno_pop(LANG_AUTO_BOOKMARK_QUERY).
                TitleBar(str(ctx, "LANG_AUTO_BOOKMARK_QUERY"), onBack = { req.answer(-1) })
                RockRow(str(ctx, "LANG_SET_BOOL_YES"), selected = true) { req.answer(1) }
                RockRow(str(ctx, "LANG_SET_BOOL_NO")) { req.answer(-1) }
            } else {
                // Rockbox select_bookmark() with "[Don't Resume]" first.
                TitleBar(str(ctx, "LANG_BOOKMARK_SELECT_BOOKMARK"), onBack = { req.answer(-1) })
                RockRow(str(ctx, "LANG_BOOKMARK_DONT_RESUME"), selected = true) { req.answer(-1) }
                RockList(Modifier.fillMaxSize()) {
                    itemsIndexed(req.lines.mapNotNull { Bookmarks.parse(it) }) { i, bm ->
                        RockRow(bookmarkLine1(bm, false), sub = bookmarkLine2(ctx, bm), icon = G.Cursor) { req.answer(i) }
                    }
                }
            }
        }
    }
}
