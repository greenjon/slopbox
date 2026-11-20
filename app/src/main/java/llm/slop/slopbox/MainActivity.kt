package llm.slop.slopbox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File

/** Spike UI: open one file, play it through Rockbox's codecs and DSP. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Player() }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) Engine.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" (content URI), or in debug builds a raw path for adb tests:
     *  adb shell am start -n <pkg>/.MainActivity --es path /data/.../t.flac */
    private fun handleIntent(intent: Intent?) {
        val debugPath = if (BuildConfig.DEBUG) intent?.getStringExtra("path") else null
        when {
            debugPath != null -> {
                val pfd = ParcelFileDescriptor.open(File(debugPath), ParcelFileDescriptor.MODE_READ_ONLY)
                Engine.play(pfd.detachFd(), File(debugPath).name)
            }
            intent?.action == Intent.ACTION_VIEW && intent.data != null -> playUri(intent.data!!)
        }
    }

    private fun playUri(uri: Uri): String {
        val name = displayName(uri)
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return "Can't open $name"
        return if (Engine.play(pfd.detachFd(), name)) name else "Engine refused $name"
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "unknown"

    @Composable
    private fun Player() {
        var status by remember { mutableStateOf("Open a file to start.") }
        var info by remember { mutableStateOf<Engine.TrackInfo?>(null) }
        var elapsed by remember { mutableLongStateOf(0L) }
        var playing by remember { mutableStateOf(false) }
        var paused by remember { mutableStateOf(false) }
        var bassBoost by remember { mutableStateOf(false) }
        var crossfeed by remember { mutableStateOf(false) }
        var tempo by remember { mutableFloatStateOf(1f) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val result = playUri(uri)
            if (!Engine.isPlaying) status = result
            paused = false
        }

        LaunchedEffect(Unit) {
            while (true) {
                playing = Engine.isPlaying
                elapsed = Engine.elapsedMs
                val i = Engine.trackInfo()
                info = if (i.codec.isNotEmpty()) i else null
                status = when {
                    !playing && Engine.lastResult != 0 -> "Engine error ${Engine.lastResult}"
                    playing && paused -> "Paused"
                    playing -> "Playing"
                    info != null -> "Stopped"
                    else -> status
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
            info?.let {
                Text("${it.artist.ifEmpty { "?" }} - ${it.title.ifEmpty { "?" }}")
                Text("${it.codec}, ${it.frequency} Hz, ${it.bitrate} kb/s")
                Text("${fmt(elapsed)} / ${fmt(it.lengthMs)}")
                if (it.lengthMs > 0) {
                    Slider(
                        value = (elapsed.toFloat() / it.lengthMs).coerceIn(0f, 1f),
                        onValueChange = { f -> Engine.seekMs((f * it.lengthMs).toLong()) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Open") }
                Button(enabled = playing, onClick = {
                    paused = !paused
                    Engine.setPaused(paused)
                }) { Text(if (paused) "Resume" else "Pause") }
                Button(enabled = playing, onClick = { Engine.stop() }) { Text("Stop") }
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
