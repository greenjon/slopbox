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
import androidx.media3.session.MediaController

/** Rockbox's main menu (the parts SlopBox has so far). */
@Composable
fun MainMenu(controller: MediaController?, nav: Nav) {
    var cursor by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TitleBar("SlopBox", onBack = null)
        // Rockbox's root menu shows Recent Bookmarks when there are any.
        val ctx = LocalContext.current
        if (remember(nav.stack.size) { llm.slop.slopbox.Bookmarks(ctx).hasRecent() }) {
            RockRow(llm.slop.slopbox.SettingsStore.get(ctx).catalog.str("LANG_BOOKMARK_MENU_RECENT_BOOKMARKS"), icon = G.Cursor) {
                BookmarkView.playlist = null
                nav.push(Screen.BookmarkList)
            }
        }
        RockRow("Files", icon = G.TangoFolder, selected = cursor == 0) { cursor = 0; nav.push(Screen.Browser) }
        RockRow("Database", icon = G.TangoNote, selected = cursor == 1) { cursor = 1; nav.push(Screen.Database) }
        RockRow(
            "Now Playing", icon = G.Cursor, selected = cursor == 2,
            sub = controller?.mediaMetadata?.title?.toString()?.takeIf { controller.mediaItemCount > 0 },
        ) { cursor = 2; nav.toNowPlaying() }
        RockRow("Settings", icon = G.Config, selected = cursor == 3) { cursor = 3; nav.push(Screen.Settings) }
    }
}
