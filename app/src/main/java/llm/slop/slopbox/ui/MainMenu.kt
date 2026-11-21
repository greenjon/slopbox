/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/root_menu.c - Copyright (C) 2007 Jonathan Gordon
 *   apps/plugins/main_menu_config.c - Copyright (C) 2014 Jonathan Gordon
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import llm.slop.slopbox.SettingsStore

/**
 * Rockbox's root menu items (root_menu.c menu_table[]) that SlopBox has, in
 * Rockbox's default order. Recording and FM Radio don't exist here.
 */
object RootMenu {
    val DEFAULT = listOf("bookmarks", "files", "database", "wps", "settings", "playlists", "plugins",
        "system_menu", "shortcuts")

    /** root_menu_load_from_cfg(): the "root menu order" setting; Settings is always added. */
    fun order(store: SettingsStore): List<String> {
        val s = store.catalog.byCfg["root menu order"] ?: return DEFAULT
        val text = store.text(s).trim()
        if (text.isEmpty() || text.startsWith("-")) return DEFAULT
        val items = text.split(',').map { it.trim().substringBefore(' ') }.filter { it in DEFAULT }.distinct()
        return if ("settings" in items) items else items + "settings"
    }

    fun save(store: SettingsStore, enabled: List<String>?) {
        val s = store.catalog.byCfg["root menu order"] ?: return
        store.setText(s, enabled?.joinToString("") { "$it, " } ?: "")
    }

    fun title(store: SettingsStore, id: String, playing: Boolean = false): String = store.catalog.str(when (id) {
        "bookmarks" -> "LANG_BOOKMARK_MENU_RECENT_BOOKMARKS"
        "files" -> "LANG_DIR_BROWSER"
        "database" -> "LANG_TAGCACHE"
        // get_wps_item_name(): "Now Playing" while audio is loaded, else "Resume Playback".
        "wps" -> if (playing) "LANG_NOW_PLAYING" else "LANG_RESUME_PLAYBACK"
        "settings" -> "LANG_SETTINGS"
        "playlists" -> "LANG_PLAYLISTS"
        "plugins" -> "LANG_PLUGINS"
        "system_menu" -> "LANG_SYSTEM"
        else -> "LANG_SHORTCUTS"
    })
}

/** Rockbox's main menu, in the order and with the items set in Settings > Main Menu. */
@Composable
fun MainMenu(controller: MediaController?, nav: Nav) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    var cursor by remember { mutableStateOf("files") }
    val hasRecent = remember(nav.stack.size) { llm.slop.slopbox.Bookmarks(ctx).hasRecent() }
    val playing = controller != null && controller.mediaItemCount > 0 &&
        controller.playbackState != Player.STATE_IDLE && controller.playbackState != Player.STATE_ENDED
    Column(Modifier.fillMaxSize()) {
        TitleBar("SlopBox", onBack = null)
        for (id in remember(nav.stack.size) { RootMenu.order(store) }) {
            // Rockbox's item_callback(): Recent Bookmarks only when there are some.
            if (id == "bookmarks" && !hasRecent) continue
            val icon = when (id) {
                "files", "plugins" -> G.TangoFolder
                "database" -> G.TangoNote
                "settings", "system_menu" -> G.Config
                "playlists" -> G.ListC
                else -> G.Cursor
            }
            RockRow(RootMenu.title(store, id, playing), icon = icon, selected = cursor == id,
                sub = if (id == "wps") controller?.mediaMetadata?.title?.toString()?.takeIf { controller.mediaItemCount > 0 } else null) {
                cursor = id
                when (id) {
                    "bookmarks" -> { BookmarkView.playlist = null; nav.push(Screen.BookmarkList) }
                    "files" -> nav.push(Screen.Browser)
                    "database" -> nav.push(Screen.Database)
                    "wps" -> nav.toNowPlaying()
                    "settings" -> nav.push(Screen.Settings)
                    "playlists" -> nav.push(Screen.Playlists)
                    "plugins" -> nav.push(Screen.Plugins)
                    "system_menu" -> nav.push(Screen.System)
                    "shortcuts" -> nav.push(Screen.Shortcuts)
                }
            }
        }
    }
}

/**
 * Rockbox's main_menu_config plugin (Settings > Main Menu): each item "Name:
 * On/Off" in order; tap turns it on or off; a long press offers Move Item
 * Up, Move Item Down and Load Default Configuration. Saved on leaving.
 */
@Composable
fun MainMenuConfig(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SettingsStore.get(ctx) }
    fun str(id: String) = store.catalog.str(id)
    class Item(val id: String, var on: Boolean)
    // load_from_cfg(): the enabled ones in order, then the rest, off.
    fun load(): List<Item> {
        val on = RootMenu.order(store)
        return on.map { Item(it, true) } + RootMenu.DEFAULT.filter { it !in on }.map { Item(it, false) }
    }
    val items = remember { mutableStateListOf<Item>().apply { addAll(load()) } }
    var changed by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var confirmDefault by remember { mutableStateOf(false) }

    fun leave() {
        if (changed) RootMenu.save(store, items.filter { it.on }.map { it.id })
        onDone()
    }
    BackHandler(enabled = menuFor == null && !confirmDefault) { leave() }
    BackHandler(enabled = menuFor != null) { menuFor = null }

    if (confirmDefault) {
        YesNo(str("LANG_LOAD_DEFAULT_CONFIGURATION"), listOf(str("LANG_ARE_YOU_SURE"))) { yes ->
            confirmDefault = false
            if (yes) {
                RootMenu.save(store, null)
                items.clear(); items.addAll(load())
                changed = false
            }
        }
        return
    }
    menuFor?.let { i ->
        Column(Modifier.fillMaxSize()) {
            TitleBar(str("LANG_MAIN_MENU"), onBack = { menuFor = null })
            fun failed() = android.widget.Toast.makeText(ctx, str("LANG_FAILED"), android.widget.Toast.LENGTH_SHORT).show()
            RockRow(str("LANG_MOVE_ITEM_UP"), icon = G.Cursor) {
                if (i == 0) failed() else { items.add(i - 1, items.removeAt(i)); changed = true }
                menuFor = null
            }
            RockRow(str("LANG_MOVE_ITEM_DOWN"), icon = G.Cursor) {
                if (i + 1 == items.size) failed() else { items.add(i + 1, items.removeAt(i)); changed = true }
                menuFor = null
            }
            RockRow(str("LANG_LOAD_DEFAULT_CONFIGURATION"), icon = G.Cursor) { menuFor = null; confirmDefault = true }
        }
        return
    }
    @Suppress("UNUSED_EXPRESSION") rev
    Column(Modifier.fillMaxSize()) {
        TitleBar(str("LANG_MAIN_MENU"), onBack = { leave() })
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(items) { i, it ->
                RockRow("${RootMenu.title(store, it.id)}: ${str(if (it.on) "LANG_ON" else "LANG_OFF")}",
                    icon = if (it.on) G.Config else null, forceIcon = true,
                    onLongClick = { menuFor = i }) {
                    it.on = !it.on; changed = true; rev++
                }
            }
        }
    }
}
