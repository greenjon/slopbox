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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController

enum class Screen { MainMenu, Browser, Database, NowPlaying, Playlist, QuickScreen, ContextMenu, TrackInfo, Sound, Settings, Bookmarks, BookmarkList, Playlists, CurrentPlaylist, AddToPlaylist, SettingsAt, AlbumArt, Cuesheet, Pitch, Shortcuts, Plugins, PluginRfa, PluginLrc, PluginScrobbler, PluginAlarm, PluginRater, System }

/** Screen stack. Rockbox's main menu is always at the bottom. */
class Nav {
    val stack = mutableStateListOf(Screen.MainMenu)
    val top: Screen get() = stack.last()

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    /** Back to the browser playback was started from (Files or Database), else the main menu. */
    fun toBrowser() {
        while (stack.size > 1 && top !in BROWSERS) stack.removeAt(stack.lastIndex)
    }

    fun toFiles() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(Screen.Browser)
    }

    fun toNowPlaying() {
        if (Screen.NowPlaying in stack) {
            while (top != Screen.NowPlaying) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(Screen.NowPlaying)
        }
    }

    private companion object {
        val BROWSERS = setOf(Screen.Browser, Screen.Database, Screen.MainMenu)
    }
}

/** Playlist edits, named after Rockbox's "Current Playlist" context menu. */
object Queue {
    fun play(c: MediaController, items: List<MediaItem>, start: Int) {
        if (items.isEmpty()) return
        c.setMediaItems(items, start, 0)
        c.prepare()
        c.play()
    }

    /** Play [items] from [start] without Autoload Bookmarks (Rockbox's playlist viewer). */
    fun playFrom(c: MediaController, items: List<MediaItem>, start: Int) {
        val item = items.getOrNull(start) ?: return
        val ex = android.os.Bundle(item.requestMetadata.extras ?: android.os.Bundle())
            .apply { putBoolean(llm.slop.slopbox.SlopPlayer.EXTRA_NO_AUTOLOAD, true) }
        val marked = item.buildUpon().setRequestMetadata(item.requestMetadata.buildUpon().setExtras(ex).build()).build()
        play(c, items.toMutableList().apply { set(start, marked) }, start)
    }

    /** Rockbox warn_on_pl_erase(): ask before replacing a changed playlist? */
    fun warnOnErase(ctx: android.content.Context): Boolean {
        val p = llm.slop.slopbox.SlopPlayer.current ?: return false
        val store = llm.slop.slopbox.SettingsStore.get(ctx)
        return p.playlistModified && store.int("warn when erasing dynamic playlist") != 0 && store.int("party mode") == 0
    }

    fun insertNext(c: MediaController, items: List<MediaItem>) {
        if (c.mediaItemCount == 0) return play(c, items, 0)
        c.addMediaItems((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), items)
    }

    fun insertLast(c: MediaController, items: List<MediaItem>) {
        if (c.mediaItemCount == 0) return play(c, items, 0)
        c.addMediaItems(items)
    }
}

@Composable
fun SlopApp(
    controller: MediaController?,
    nav: Nav,
    browser: BrowserState,
    database: DatabaseState,
    pickRoot: () -> Unit,
) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    // Dark system bar icons on a light background.
    val view = androidx.compose.ui.platform.LocalView.current
    val light = Cabbie.isLight
    androidx.compose.runtime.LaunchedEffect(light) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
    // Theme Settings colours: read now and whenever a setting changes.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val store = llm.slop.slopbox.SettingsStore.get(ctx)
        Cabbie.apply(store)
        val l: () -> Unit = { Cabbie.apply(store) }
        store.addListener(l)
        onDispose { store.removeListener(l) }
    }
    Backdrop {
        // Rockbox's Status Bar setting places the mini-player: Off, Top or Bottom.
        val bar = statusBarPlace()
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
          if (bar == 1) MiniPlayer(controller, nav, atTop = true)
          Box(Modifier.weight(1f).fillMaxWidth()) {
            when (nav.top) {
                Screen.MainMenu -> MainMenu(controller, nav)
                Screen.Browser -> BrowserScreen(controller, nav, browser, pickRoot)
                Screen.Database -> DatabaseScreen(controller, nav, database, browser)
                Screen.Settings -> SettingsTreeScreen(nav, pickRoot)
                Screen.NowPlaying -> NowPlayingScreen(controller, nav)
                Screen.Playlist -> PlaylistScreen(controller, nav)
                Screen.QuickScreen -> QuickScreen(controller, nav)
                Screen.ContextMenu -> WpsContextMenu(controller, nav, browser)
                Screen.TrackInfo -> TrackInfoScreen(controller, nav)
                Screen.Sound -> SettingsTreeScreen(nav, pickRoot, startAt = "Sound Settings")
                Screen.Bookmarks -> BookmarksMenu(controller, nav)
                Screen.BookmarkList -> BookmarkListScreen(controller, nav)
                Screen.Playlists -> CatalogScreen(controller, nav)
                Screen.CurrentPlaylist -> CurrentPlaylistMenu(controller, nav)
                Screen.AddToPlaylist -> AddToPlaylistScreen(nav)
                Screen.SettingsAt -> SettingsTreeScreen(nav, pickRoot, startAt = SettingsView.startAt)
                Screen.AlbumArt -> AlbumArtScreen(controller, nav)
                Screen.Cuesheet -> CuesheetScreen(controller, nav)
                Screen.Pitch -> PitchScreen(nav)
                Screen.Shortcuts -> ShortcutsScreen(controller, nav, browser)
                Screen.Plugins -> PluginsScreen(nav)
                Screen.PluginRfa -> RandomFolderAdvanceScreen(controller, nav)
                Screen.PluginLrc -> LrcPlayerScreen(controller, nav)
                Screen.PluginScrobbler -> ScrobblerScreen(nav)
                Screen.PluginAlarm -> AlarmClockScreen(controller, nav)
                Screen.PluginRater -> TrackRaterScreen(controller, nav)
                Screen.System -> SystemMenu(nav)
            }
          }
          BottomNav.bar?.let { if (Cabbie.palette.bottomNav) it() }
          if (bar == 2) MiniPlayer(controller, nav, atTop = false)
        }
    }
}
