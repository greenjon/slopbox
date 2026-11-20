/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/onplay.c - Copyright (C) 2002 Björn Stenberg
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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import llm.slop.slopbox.SettingsStore
import llm.slop.slopbox.SlopPlayer
import llm.slop.slopbox.SlopPlayer.Companion.PLAYLIST_INSERT
import llm.slop.slopbox.SlopPlayer.Companion.PLAYLIST_INSERT_FIRST
import llm.slop.slopbox.SlopPlayer.Companion.PLAYLIST_INSERT_LAST
import llm.slop.slopbox.SlopPlayer.Companion.PLAYLIST_INSERT_LAST_SHUFFLED
import llm.slop.slopbox.SlopPlayer.Companion.PLAYLIST_INSERT_SHUFFLED

/** One of Rockbox's add_to_playlist() choices. */
data class PlaylistChoice(val position: Int, val queue: Boolean, val replace: Boolean)

/**
 * Rockbox's "Playing Next..." context menu (apps/onplay.c tree_playlist_menu),
 * with treeplaylist_callback()'s rules: queue options per "Show Queue Options",
 * shuffled options only for folders/playlists and per "Show Shuffled Adding
 * Options", inserting only while something plays. [folderLike]: a folder, a
 * playlist or a database group (several tracks). [recursable]: a folder,
 * where "Recursively Insert Directories" applies. [onChoose] gets the choice and
 * whether to include subfolders.
 */
@Composable
fun PlayingNextMenu(
    title: String,
    folderLike: Boolean,
    recursable: Boolean,
    onBack: () -> Unit,
    onChoose: (PlaylistChoice, recurse: Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    fun str(id: String) = store.catalog.str(id)
    val playing = SlopPlayer.current?.busy == true
    val queueMode = store.int("show queue options")          // 0 no, 1 yes, 2 in submenu
    val shuffledOk = store.int("show shuffled adding options") != 0 && folderLike
    var inQueueMenu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askRecurse by remember { mutableStateOf<PlaylistChoice?>(null) }
    BackHandler(enabled = inQueueMenu || confirm != null || askRecurse != null) {
        when {
            confirm != null -> confirm = null
            askRecurse != null -> askRecurse = null
            else -> inQueueMenu = false
        }
    }

    fun go(c: PlaylistChoice) {
        // Rockbox warn_on_pl_erase() before replacing a changed playlist.
        val p = SlopPlayer.current
        val warn = c.replace && p != null && p.playlistModified &&
            store.int("warn when erasing dynamic playlist") != 0 && store.int("party mode") == 0
        val run = {
            when {
                !recursable -> onChoose(c, false)
                store.int("recursive directory insert") == 2 -> askRecurse = c   // Ask
                else -> onChoose(c, store.int("recursive directory insert") == 1)
            }
        }
        if (warn) confirm = run else run()
    }

    confirm?.let { action ->
        Column(Modifier.fillMaxSize()) {
            TitleBar(str("LANG_WARN_ERASEDYNPLAYLIST_PROMPT"), onBack = { confirm = null })
            RockRow(str("LANG_SET_BOOL_YES"), selected = true) { confirm = null; action() }
            RockRow(str("LANG_SET_BOOL_NO")) { confirm = null }
        }
        return
    }
    askRecurse?.let { c ->
        Column(Modifier.fillMaxSize()) {
            TitleBar(str("LANG_RECURSE_DIRECTORY_QUESTION"), onBack = { askRecurse = null })
            RockRow(str("LANG_SET_BOOL_YES"), selected = true) { askRecurse = null; onChoose(c, true) }
            RockRow(str("LANG_SET_BOOL_NO")) { askRecurse = null; onChoose(c, false) }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (inQueueMenu) {
            TitleBar(str("LANG_QUEUE_MENU"), onBack = { inQueueMenu = false })
            RockRow(str("LANG_QUEUE_FIRST"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_FIRST, true, false)) }
            RockRow(str("LANG_QUEUE"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT, true, false)) }
            if (shuffledOk) RockRow(str("LANG_QUEUE_SHUFFLED"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_SHUFFLED, true, false)) }
            RockRow(str("LANG_QUEUE_LAST"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_LAST, true, false)) }
            if (shuffledOk) RockRow(str("LANG_QUEUE_LAST_SHUFFLED"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_LAST_SHUFFLED, true, false)) }
            return@Column
        }
        TitleBar(title, onBack = onBack)
        if (playing) {
            RockRow(str("LANG_PLAY_NEXT"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_FIRST, false, false)) }
            RockRow(str("LANG_ADD"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT, false, false)) }
            RockRow(str("LANG_PLAY_LAST"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_LAST, false, false)) }
            if (shuffledOk) {
                RockRow(str("LANG_ADD_SHUFFLED"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_SHUFFLED, false, false)) }
                RockRow(str("LANG_PLAY_LAST_SHUFFLED"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_LAST_SHUFFLED, false, false)) }
            }
            if (queueMode == 1) {
                RockRow(str("LANG_QUEUE_FIRST"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_FIRST, true, false)) }
                RockRow(str("LANG_QUEUE"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT, true, false)) }
                RockRow(str("LANG_QUEUE_LAST"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_LAST, true, false)) }
                if (shuffledOk) {
                    RockRow(str("LANG_QUEUE_SHUFFLED"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_SHUFFLED, true, false)) }
                    RockRow(str("LANG_QUEUE_LAST_SHUFFLED"), icon = G.TangoNote) { go(PlaylistChoice(PLAYLIST_INSERT_LAST_SHUFFLED, true, false)) }
                }
            }
            if (queueMode == 2) RockRow(str("LANG_QUEUE_MENU"), icon = G.Config) { inQueueMenu = true }
        }
        RockRow(str("LANG_PLAY"), icon = G.Cursor) { go(PlaylistChoice(PLAYLIST_INSERT, false, true)) }
        if (shuffledOk) RockRow(str("LANG_PLAY_SHUFFLED"), icon = G.Cursor) { go(PlaylistChoice(PLAYLIST_INSERT_LAST_SHUFFLED, false, true)) }
    }
}
