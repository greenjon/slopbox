package llm.slop.slopbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController

enum class Screen { MainMenu, Browser, Database, NowPlaying, Playlist, QuickScreen, ContextMenu, TrackInfo, Sound, Settings, Bookmarks, BookmarkList, Playlists, CurrentPlaylist, AddToPlaylist, SettingsAt, AlbumArt, Cuesheet, Pitch, Shortcuts, Plugins, PluginRfa }

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
    Backdrop {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
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
            }
        }
    }
}
