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
import java.io.File

/** The UI. Playback lives in [PlaybackService]; this activity is a controller. */
class MainActivity : ComponentActivity() {
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
            if (resumeToWps && c.mediaItemCount > 0) nav.toNowPlaying()
            resumeToWps = false
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" (content URI), or in debug builds raw paths for adb tests:
     *  adb shell am start -n llm.slop.slopbox/.MainActivity --es path /data/.../a.flac,/data/.../b.mp3 */
    private fun handleIntent(intent: Intent?) {
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
