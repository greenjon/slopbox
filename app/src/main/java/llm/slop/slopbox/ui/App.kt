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

enum class Screen { Browser, NowPlaying, Playlist, QuickScreen, ContextMenu, TrackInfo, Sound }

/** Screen stack. The file browser is always at the bottom, like Rockbox's "Files". */
class Nav {
    val stack = mutableStateListOf(Screen.Browser)
    val top: Screen get() = stack.last()

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun toBrowser() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun toNowPlaying() {
        toBrowser()
        stack.add(Screen.NowPlaying)
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
fun SlopApp(controller: MediaController?, nav: Nav, browser: BrowserState, pickRoot: () -> Unit) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    Backdrop {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (nav.top) {
                Screen.Browser -> BrowserScreen(controller, nav, browser, pickRoot)
                Screen.NowPlaying -> NowPlayingScreen(controller, nav)
                Screen.Playlist -> PlaylistScreen(controller, nav)
                Screen.QuickScreen -> QuickScreen(controller, nav)
                Screen.ContextMenu -> WpsContextMenu(nav)
                Screen.TrackInfo -> TrackInfoScreen(controller, nav)
                Screen.Sound -> SoundScreen(nav)
            }
        }
    }
}
