package llm.slop.slopbox

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import java.io.File

/** Spike UI. Playback lives in [PlaybackService]; this screen is a controller. */
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    /** Files to play once the controller connects. */
    private var pending: List<Uri>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Player() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get()
            pending?.let { playUris(it) }
            pending = null
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
                .setMediaMetadata(MediaMetadata.Builder().setTitle(displayName(uri)).build())
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
        c.play()
    }

    private fun displayName(uri: Uri): String =
        if (uri.scheme == "file") File(uri.path!!).name
        else contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "unknown"

    @Composable
    private fun Player() {
        val c = controller
        var title by remember { mutableStateOf("") }
        var artist by remember { mutableStateOf("") }
        var details by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Open files to start.") }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var queue by remember { mutableStateOf("") }
        var isPlaying by remember { mutableStateOf(false) }
        var itemCount by remember { mutableIntStateOf(0) }
        var bassBoost by remember { mutableStateOf(false) }
        var crossfeed by remember { mutableStateOf(false) }
        var tempo by remember { mutableFloatStateOf(1f) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            // Keep access after this screen goes away; the service plays them later.
            uris.forEach {
                runCatching {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            playUris(uris)
        }

        LaunchedEffect(c) {
            while (c != null) {
                isPlaying = c.isPlaying
                itemCount = c.mediaItemCount
                position = c.currentPosition
                duration = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                val md = c.mediaMetadata
                title = md.title?.toString() ?: ""
                artist = md.artist?.toString() ?: ""
                queue = if (itemCount > 0) "Track ${c.currentMediaItemIndex + 1} of $itemCount" else ""
                val info = Engine.trackInfo()
                details = if (info.codec.isNotEmpty()) "${info.codec}, ${info.frequency} Hz, ${info.bitrate} kb/s" else ""
                status = when {
                    itemCount == 0 -> "Open files to start."
                    c.playbackState == Player.STATE_ENDED -> "End of queue"
                    isPlaying -> "Playing"
                    c.playWhenReady -> "Waiting"
                    else -> "Paused"
                }
                delay(250)
            }
        }

        Column(
            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("SlopBox", style = MaterialTheme.typography.headlineMedium)
            Text(status)
            if (itemCount > 0) {
                Text("${artist.ifEmpty { "?" }} - ${title.ifEmpty { "?" }}")
                if (details.isNotEmpty()) Text(details)
                Text("${fmt(position)} / ${fmt(duration)}    $queue")
                if (duration > 0) {
                    Slider(
                        value = (position.toFloat() / duration).coerceIn(0f, 1f),
                        onValueChange = { f -> c?.seekTo((f * duration).toLong()) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = c != null, onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Open") }
                Button(enabled = itemCount > 0, onClick = { c?.seekToPrevious() }) { Text("Prev") }
                Button(enabled = itemCount > 0, onClick = {
                    if (c?.isPlaying == true) c.pause() else c?.play()
                }) { Text(if (isPlaying) "Pause" else "Play") }
                Button(enabled = itemCount > 0, onClick = { c?.seekToNext() }) { Text("Next") }
            }
            Toggle("Bass boost (EQ low shelf +6 dB at 120 Hz)", bassBoost) {
                bassBoost = it
                Engine.setEqBand(0, 0, 120, 7, if (it) 60 else 0)
                Engine.setEqPrecut(if (it) 60 else 0)
                Engine.setEqEnabled(it)
            }
            Toggle("Crossfeed (Meier)", crossfeed) {
                crossfeed = it
                Engine.setCrossfeed(if (it) 1 else 0)
            }
            Text("Tempo ${"%.2f".format(tempo)}x (pitch unchanged)")
            Slider(
                value = tempo,
                valueRange = 0.5f..2f,
                onValueChange = {
                    tempo = it
                    Engine.setTempo(it.toDouble())
                },
            )
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = checked, onCheckedChange = onChange)
            Text(label)
        }
    }

    private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
}
