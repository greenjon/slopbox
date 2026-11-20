package llm.slop.slopbox

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * A Media3 [Player] backed by the native engine (Rockbox codecs + DSP), so the
 * MediaSession can drive it: notification, lock screen, headset and Bluetooth
 * buttons. Owns audio focus, pause-on-unplug and the wake lock.
 *
 * All methods run on the main thread.
 */
@UnstableApi
class SlopPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private class Entry(val uid: Long, val item: MediaItem) {
        var metadata: MediaMetadata? = null
        var durationMs: Long = C.TIME_UNSET
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlopBox:playback")
        .apply { setReferenceCounted(false) }

    private var nextUid = 1L
    private val entries = mutableListOf<Entry>()
    private var index = 0
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    /** The engine is playing (or paused inside) the current entry. */
    private var engineActive = false
    /** Position to use while the engine isn't active. */
    private var heldPositionMs = 0L
    /** Engine serial of the file playing as [index]. */
    private var engineSerial = 0
    /** Entry handed to the engine to follow the current one, if any. */
    private var queuedNext: Entry? = null

    // ---- Audio focus -------------------------------------------------------

    private var hasFocus = false
    private var resumeOnFocusGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
        .build()

    private fun requestFocus(): Boolean {
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return hasFocus
    }

    private fun abandonFocus() {
        if (hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        resumeOnFocusGain = false
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnFocusGain = false
                setPlaying(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = playWhenReady
                setPlaying(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Engine.setDuck(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                Engine.setDuck(false)
                if (resumeOnFocusGain) setPlaying(true)
                resumeOnFocusGain = false
            }
        }
        invalidateState()
    }

    // ---- Headphones unplugged ----------------------------------------------

    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                setPlaying(false)
                invalidateState()
            }
        }
    }

    // ---- Engine control ----------------------------------------------------

    /** Brings play/pause, focus, the unplug receiver and the wake lock in line. */
    private fun setPlaying(play: Boolean) {
        if (play && !requestFocus()) {
            playWhenReady = false
            return
        }
        playWhenReady = play
        if (engineActive) Engine.setPaused(!play)
        else if (play && playbackState == Player.STATE_READY && entries.isNotEmpty()) startCurrent(heldPositionMs)
        updateHolds()
    }

    @SuppressLint("WakelockTimeout") // Held only while audio plays; released on pause/stop/end.
    private fun updateHolds() {
        val active = playWhenReady && engineActive
        if (active && !wakeLock.isHeld) wakeLock.acquire()
        if (!active && wakeLock.isHeld) wakeLock.release()
        if (active && !noisyRegistered) {
            context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        } else if (!active && noisyRegistered) {
            context.unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
        if (playbackState != Player.STATE_READY) abandonFocus()
    }

    private fun startCurrent(startMs: Long) {
        val entry = entries.getOrNull(index) ?: return
        val uri = entry.item.localConfiguration?.uri ?: entry.item.requestMetadata.mediaUri
        val opened = uri?.let { open(it) }
        if (opened == null) {
            Log.w(TAG, "can't open $uri; skipping")
            engineActive = false
            advanceAfterEnd()
            return
        }
        val (pfd, name) = opened
        queuedNext = null
        engineActive = Engine.play(pfd.detachFd(), name, startMs, paused = !playWhenReady)
        engineSerial = Engine.trackSerial
        heldPositionMs = startMs
        if (engineActive) {
            queueFollowing()
            startPolling()
        }
    }

    /** Hand the engine the entry after the current one so it can play it gaplessly. */
    private fun queueFollowing() {
        if (!engineActive) return
        val next = entries.getOrNull(index + 1)
        if (next === queuedNext) return
        queuedNext = null
        if (next == null) {
            Engine.clearNext()
            return
        }
        val uri = next.item.localConfiguration?.uri ?: next.item.requestMetadata.mediaUri
        val opened = uri?.let { open(it) }
        if (opened == null) {
            // Unopenable: don't queue; the end-of-track path will skip it.
            Engine.clearNext()
            return
        }
        if (Engine.queueNext(opened.first.detachFd(), opened.second)) queuedNext = next
    }

    private fun stopEngine() {
        queuedNext = null
        if (engineActive) {
            heldPositionMs = Engine.elapsedMs
            Engine.stop()
        }
        engineActive = false
    }

    private fun open(uri: Uri): Pair<ParcelFileDescriptor, String>? = try {
        when (uri.scheme) {
            "file" -> {
                val f = File(uri.path!!)
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) to f.name
            }
            else -> {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: uri.lastPathSegment ?: "unknown"
                context.contentResolver.openFileDescriptor(uri, "r")?.let { it to name }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "open $uri failed", e)
        null
    }

    /** Current track ended (or failed): next entry, or the end of the queue. */
    private fun advanceAfterEnd() {
        if (index < entries.size - 1) {
            index++
            startCurrent(0)
        } else {
            playbackState = Player.STATE_ENDED
            heldPositionMs = entries.getOrNull(index)?.durationMs?.takeIf { it != C.TIME_UNSET } ?: 0L
        }
        updateHolds()
        invalidateState()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!engineActive) return
            val serial = Engine.trackSerial
            if (serial != engineSerial) {
                // The engine moved on to the queued entry without a gap.
                engineSerial = serial
                val next = queuedNext
                queuedNext = null
                val at = if (next != null) entries.indexOf(next) else -1
                index = if (at >= 0) at else (index + 1).coerceAtMost(entries.size - 1)
                heldPositionMs = 0
                queueFollowing()
                invalidateState()
            }
            val entry = entries.getOrNull(index)
            if (entry != null && entry.metadata == null) {
                val info = Engine.trackInfo()
                if (info.codec.isNotEmpty()) {
                    entry.durationMs = if (info.lengthMs > 0) info.lengthMs else C.TIME_UNSET
                    val given = entry.item.mediaMetadata
                    entry.metadata = given.buildUpon()
                        .setTitle(info.title.ifEmpty { given.title?.toString() })
                        .setArtist(info.artist.ifEmpty { given.artist?.toString() })
                        .setAlbumTitle(info.album.ifEmpty { given.albumTitle?.toString() })
                        .build()
                    invalidateState()
                }
            }
            if (!Engine.isPlaying) {
                if (Engine.lastResult != 0) Log.w(TAG, "engine error ${Engine.lastResult}; skipping")
                engineActive = false
                advanceAfterEnd()
                return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun startPolling() {
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    // ---- SimpleBasePlayer --------------------------------------------------

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
        ).build()
        val playlist = entries.map { e ->
            MediaItemData.Builder(e.uid)
                .setMediaItem(e.item)
                .setMediaMetadata(e.metadata ?: e.item.mediaMetadata)
                .setDurationUs(if (e.durationMs == C.TIME_UNSET) C.TIME_UNSET else e.durationMs * 1000)
                .setIsSeekable(true)
                .build()
        }
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaylist(playlist)
        if (playlist.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(index.coerceIn(0, playlist.size - 1))
            builder.setContentPositionMs {
                if (engineActive) Engine.elapsedMs else heldPositionMs
            }
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && playbackState == Player.STATE_ENDED && entries.isNotEmpty()) {
            // Play after the end of the queue: start it again from the top.
            index = 0
            heldPositionMs = 0
            playbackState = Player.STATE_READY
        }
        setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        playbackState = if (entries.isEmpty()) Player.STATE_ENDED else Player.STATE_READY
        if (playbackState == Player.STATE_READY && !engineActive) {
            if (playWhenReady && !requestFocus()) playWhenReady = false
            startCurrent(heldPositionMs)
        }
        updateHolds()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        stopEngine()
        playbackState = Player.STATE_IDLE
        updateHolds()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(poll)
        stopEngine()
        playWhenReady = false
        playbackState = Player.STATE_IDLE
        updateHolds()
        abandonFocus()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        stopEngine()
        entries.clear()
        mediaItems.forEach { entries += Entry(nextUid++, it) }
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, maxOf(0, entries.size - 1))
        heldPositionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        if (playbackState != Player.STATE_IDLE) {
            playbackState = if (entries.isEmpty()) Player.STATE_ENDED else Player.STATE_READY
            if (playbackState == Player.STATE_READY) startCurrent(heldPositionMs)
        }
        updateHolds()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, entries.size)
        entries.addAll(at, mediaItems.map { Entry(nextUid++, it) })
        if (entries.size == mediaItems.size) {
            this.index = 0
        } else if (at <= this.index) {
            this.index += mediaItems.size
        }
        if (playbackState == Player.STATE_ENDED) {
            // Adding to a finished queue continues with the new items.
            this.index = at.coerceAtMost(entries.size - 1)
            heldPositionMs = 0
            playbackState = Player.STATE_READY
            if (playWhenReady) startCurrent(0)
        }
        queueFollowing()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val removingCurrent = index in fromIndex until toIndex
        repeat(toIndex - fromIndex) { entries.removeAt(fromIndex) }
        if (removingCurrent) {
            stopEngine()
            heldPositionMs = 0
            if (entries.isEmpty()) {
                index = 0
                if (playbackState != Player.STATE_IDLE) playbackState = Player.STATE_ENDED
            } else {
                index = fromIndex.coerceAtMost(entries.size - 1)
                if (playbackState == Player.STATE_READY) startCurrent(0)
            }
        } else if (index >= toIndex) {
            index -= toIndex - fromIndex
        }
        updateHolds()
        queueFollowing()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val current = entries.getOrNull(index)
        val moved = entries.subList(fromIndex, toIndex).toList()
        repeat(toIndex - fromIndex) { entries.removeAt(fromIndex) }
        entries.addAll(newIndex.coerceIn(0, entries.size), moved)
        current?.let { index = entries.indexOf(it) }
        queueFollowing()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (entries.isEmpty()) return Futures.immediateVoidFuture()
        val target = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex.coerceIn(0, entries.size - 1)
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
        if (target == index && engineActive) {
            Engine.seekMs(pos)
        } else {
            stopEngine()
            index = target
            heldPositionMs = pos
            if (playbackState == Player.STATE_READY) startCurrent(pos)
        }
        updateHolds()
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val TAG = "SlopPlayer"
        private const val POLL_MS = 250L
    }
}
