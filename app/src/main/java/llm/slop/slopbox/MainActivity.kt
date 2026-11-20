package llm.slop.slopbox

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import llm.slop.slopbox.ui.BrowserState
import llm.slop.slopbox.ui.DatabaseState
import llm.slop.slopbox.ui.db.TagDb
import llm.slop.slopbox.ui.MusicTree
import llm.slop.slopbox.ui.Nav
import llm.slop.slopbox.ui.Queue
import llm.slop.slopbox.ui.SlopApp
import llm.slop.slopbox.ui.Screen
import java.io.File

/** The UI. Playback lives in [PlaybackService]; this activity is a controller. */
class MainActivity : ComponentActivity() {
    private companion object { const val KEY_LAST_SCREEN = "lastScreen" }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    /** Files to play once the controller connects. */
    private var pending: List<Uri>? = null
    private val nav = Nav()
    private val browser = BrowserState()
    private val database = DatabaseState()
    /** On launch, land on the WPS if something is already playing. */
    private var resumeToWps = false

    private val pickRoot = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val changed = MusicTree.root(this) != tree
        MusicTree.setRoot(this, tree)
        browser.open(this, tree)
        // The old database points into the old folder.
        if (changed && TagDb.tracks.isNotEmpty()) TagDb.scan(this, tree, full = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        SettingsStore.get(this) // load the user's Rockbox settings into the engine
        browser.open(this, MusicTree.root(this))
        TagDb.load(this)
        if (savedInstanceState == null) {
            resumeToWps = true
            handleIntent(intent)
        }
        setContent {
            SlopApp(controller, nav, browser, database, pickRoot = { pickRoot.launch(null) })
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = future.get()
            controller = c
            pending?.let { playUris(it) }
            pending = null
            if (resumeToWps) openStartScreen(c)
            resumeToWps = false
        }, MoreExecutors.directExecutor())
    }

    /**
     * Rockbox "Start Screen" (General Settings › Startup/Shutdown), on a fresh
     * launch. If music is already playing, go to the WPS as before.
     */
    private fun openStartScreen(c: MediaController) {
        if (c.isPlaying) return nav.toNowPlaying()
        when (SettingsStore.get(this).int("start in screen")) {
            0 -> when (getPreferences(MODE_PRIVATE).getString(KEY_LAST_SCREEN, null)) {   // Previous Screen
                Screen.Browser.name -> nav.toFiles()
                Screen.Database.name -> nav.push(Screen.Database)
                Screen.Settings.name -> nav.push(Screen.Settings)
                Screen.NowPlaying.name -> if (c.mediaItemCount > 0) nav.toNowPlaying()
            }
            2 -> nav.toFiles()
            3 -> nav.push(Screen.Database)
            4 -> if (c.mediaItemCount > 0) nav.toNowPlaying()   // Resume Playback (the player starts it)
            5 -> nav.push(Screen.Settings)
            else -> {}   // Main Menu; Recent Bookmarks and Open Plugin aren't in SlopBox
        }
    }

    override fun onStop() {
        // For "Start Screen: Previous Screen".
        getPreferences(MODE_PRIVATE).edit().putString(KEY_LAST_SCREEN, nav.stack.lastOrNull {
            it in setOf(Screen.Browser, Screen.Database, Screen.Settings, Screen.NowPlaying)
        }?.name).apply()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    /** Screen touches count as key presses for the sleep timer and idle poweroff. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) PowerMgmt.userActivity()
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" (content URI), or in debug builds raw paths for adb tests:
     *  adb shell am start -n llm.slop.slopbox/.MainActivity --es path /data/.../a.flac,/data/.../b.mp3 */
    private fun handleIntent(intent: Intent?) {
        // Debug builds: "--es ab a|b|reset" sends the A-B repeat commands (for adb tests).
        if (BuildConfig.DEBUG) intent?.getStringExtra("ab")?.let { ab ->
            val cmd = when (ab) {
                "a" -> PlaybackService.CMD_AB_SET_A
                "b" -> PlaybackService.CMD_AB_SET_B
                else -> PlaybackService.CMD_AB_RESET
            }
            controller?.sendCustomCommand(androidx.media3.session.SessionCommand(cmd, Bundle.EMPTY), Bundle.EMPTY)
            return
        }
        if (BuildConfig.DEBUG) intent?.getStringExtra("sleep")?.toIntOrNull()?.let {
            PowerMgmt.setSleepTimerSecondsForTest(it)
            return
        }
        val debugPaths = if (BuildConfig.DEBUG) intent?.getStringExtra("path") else null
        val uris = when {
            debugPaths != null -> debugPaths.split(',').map { Uri.fromFile(File(it)) }
            intent?.action == Intent.ACTION_VIEW && intent.data != null -> listOf(intent.data!!)
            else -> return
        }
        if (controller != null) playUris(uris) else pending = uris
    }

    private fun playUris(uris: List<Uri>) {
        val c = controller ?: return
        val items = uris.map { uri ->
            MediaItem.Builder()
                .setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(displayName(uri).substringBeforeLast('.')).build())
                .build()
        }
        Queue.play(c, items, 0)
        nav.toNowPlaying()
    }

    private fun displayName(uri: Uri): String =
        if (uri.scheme == "file") File(uri.path!!).name
        else contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "unknown"
}
