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
    /** A seek the engine hasn't reported back yet: report this position meanwhile. */
    private var seekTarget = -1L
    private var seekAt = 0L
    /** Engine serials of the files handed to the engine, and their entries. */
    private val serialEntries = HashMap<Int, Entry>()
    /** Serial being heard (that's [index]) and serial being decoded, which can be
     *  ahead by the PCM buffer -- up to the crossfade length plus a second. */
    private var playSerial = -1
    private var decodeSerial = -1
    private var decodeEntry: Entry? = null
    /** Entry handed to the engine to follow the one being decoded, if any. */
    private var queuedNext: Entry? = null

    // ---- Rockbox Playback Settings -------------------------------------------

    private val store = SettingsStore.get(context)
    private fun setting(cfg: String) = store.int(cfg)
    /** Rockbox "repeat": 0 off, 1 all, 2 one, 3 shuffle, 4 A-B (not yet: acts as off). */
    private val repeat get() = setting("repeat")
    /** The playlist order before shuffling, restored when shuffle goes off. */
    private val original = mutableListOf<Entry>()
    private var shuffled = false

    private val settingsListener: () -> Unit = {
        val want = setting("shuffle") != 0
        if (want != shuffled) applyShuffle(want, keepCurrentFirst = true)
        Engine.setShuffle(shuffled)
        queueFollowing()
        invalidateState()
    }

    init {
        store.addListener(settingsListener)
    }

    /** Rockbox playlist_randomise() / playlist_sort(): reorder the queue itself. */
    private fun applyShuffle(on: Boolean, keepCurrentFirst: Boolean) {
        val current = entries.getOrNull(index)
        if (on) {
            val rest = entries.filter { it !== current || !keepCurrentFirst }.shuffled()
            entries.clear()
            if (keepCurrentFirst && current != null) entries += current
            entries += rest
            index = if (current != null) entries.indexOf(current) else 0
        } else {
            val restored = original.filter { e -> entries.any { it === e } }
            entries.clear()
            entries += restored
            index = current?.let { entries.indexOf(it) }?.coerceAtLeast(0) ?: 0
        }
        shuffled = on
        Engine.setShuffle(on)
    }

    /** Keep [original] in step while the queue is in its natural order. */
    private fun syncOriginal() {
        if (!shuffled) {
            original.clear()
            original += entries
        }
    }

    /** Index that follows [from] on an automatic track change. */
    private fun followingIndex(from: Int = index): Int? = when {
        entries.isEmpty() || from < 0 -> null
        repeat == 2 -> from                        // One
        from + 1 < entries.size -> from + 1
        repeat == 1 -> 0                           // All
        else -> null                               // Off, A-B; Shuffle reshuffles at the end
    }

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
        val wasPlaying = playWhenReady
        playWhenReady = play
        val fade = setting("volume fade") != 0
        if (engineActive) {
            Engine.setPaused(!play, fade)
            // Rockbox "Rewind on Pause" (wps.c pause_action).
            val rewind = setting("rewind duration on pause")
            if (!play && wasPlaying && rewind > 0) {
                seekWithin((position - rewind * 1000L).coerceAtLeast(0))
            }
        } else if (play && playbackState == Player.STATE_READY && entries.isNotEmpty()) {
            startCurrent(heldPositionMs)
        }
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
        seekTarget = -1
        serialEntries.clear()
        val serial = Engine.play(pfd.detachFd(), name, startMs, paused = !playWhenReady)
        engineActive = serial >= 0
        serialEntries[serial] = entry
        playSerial = serial
        decodeSerial = serial
        decodeEntry = entry
        heldPositionMs = startMs
        if (engineActive) {
            queueFollowing()
            startPolling()
        }
    }

    /** Hand the engine the entry after the one it's decoding (gapless or crossfade). */
    private fun queueFollowing() {
        if (!engineActive) return
        val from = decodeEntry?.let { e -> entries.indexOfFirst { it === e } } ?: index
        val next = followingIndex(from)?.let { entries.getOrNull(it) }
        if (next === queuedNext && next != null) return
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
        val serial = Engine.queueNext(opened.first.detachFd(), opened.second)
        if (serial >= 0) {
            queuedNext = next
            serialEntries[serial] = next
        }
    }

    private fun stopEngine() {
        queuedNext = null
        serialEntries.clear()
        decodeEntry = null
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
        val n = followingIndex()
        if (n == null && repeat == 3 && entries.isNotEmpty()) {
            // Repeat: Shuffle -- reshuffle the whole playlist and go again.
            applyShuffle(true, keepCurrentFirst = false)
            index = 0
            startCurrent(0)
        } else if (n != null) {
            index = n
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
            val d = Engine.decodeSerial
            if (d != decodeSerial) {
                // The decoder took the queued file: queue the one after it.
                decodeSerial = d
                serialEntries[d]?.let { decodeEntry = it }
                if (queuedNext === decodeEntry) queuedNext = null
                queueFollowing()
            }
            val p = Engine.trackSerial
            if (p != playSerial) {
                // The listener has reached the next file (gapless or crossfade).
                playSerial = p
                serialEntries[p]?.let { e ->
                    val at = entries.indexOfFirst { it === e }
                    if (at >= 0) index = at
                }
                serialEntries.keys.removeAll { it < p - 8 }
                heldPositionMs = 0
                seekTarget = -1
                invalidateState()
            }
            if (seekTarget >= 0) {
                val before = seekTarget
                position
                if (seekTarget < 0 && before >= 0) invalidateState()
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
                if (Engine.lastResult != 0) {
                    Log.w(TAG, "engine error ${Engine.lastResult}; skipping")
                    decodeEntry?.let { e -> entries.indexOfFirst { it === e }.takeIf { it >= 0 }?.let { index = it } }
                }
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
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
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
            .setShuffleModeEnabled(shuffled)
            .setRepeatMode(when (repeat) {
                1, 3 -> Player.REPEAT_MODE_ALL
                2 -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            })
        if (playlist.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(index.coerceIn(0, playlist.size - 1))
            builder.setContentPositionMs { position }
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

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        store.catalog.byCfg["shuffle"]?.let { store.set(it, if (shuffleModeEnabled) 1 else 0) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        // Media3's three modes onto Rockbox's: Off, All, One (Shuffle/A-B stay
        // reachable from the Settings menu).
        val v = when (repeatMode) {
            Player.REPEAT_MODE_ALL -> if (repeat == 3) 3 else 1
            Player.REPEAT_MODE_ONE -> 2
            else -> 0
        }
        store.catalog.byCfg["repeat"]?.let { store.set(it, v) }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        store.removeListener(settingsListener)
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
        shuffled = false
        syncOriginal()
        if (setting("shuffle") != 0 && entries.size > 1) {
            // Rockbox "Play Selected First": the chosen track leads the shuffled list.
            val selectedFirst = setting("play selected") != 0 && startIndex != C.INDEX_UNSET
            applyShuffle(true, keepCurrentFirst = selectedFirst)
            if (!selectedFirst) { index = 0; heldPositionMs = 0 }
        }
        if (playbackState != Player.STATE_IDLE) {
            playbackState = if (entries.isEmpty()) Player.STATE_ENDED else Player.STATE_READY
            if (playbackState == Player.STATE_READY) startCurrent(heldPositionMs)
        }
        updateHolds()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, entries.size)
        val added = mediaItems.map { Entry(nextUid++, it) }
        entries.addAll(at, added)
        if (shuffled) original += added else syncOriginal()
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
        val removed = entries.subList(fromIndex, toIndex).toList()
        repeat(toIndex - fromIndex) { entries.removeAt(fromIndex) }
        original.removeAll { e -> removed.any { it === e } }
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
        syncOriginal()
        queueFollowing()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (entries.isEmpty()) return Futures.immediateVoidFuture()
        // The Next/Previous buttons go through Rockbox's skip rules.
        if (seekCommand == Player.COMMAND_SEEK_TO_NEXT) { playHop(1); return Futures.immediateVoidFuture() }
        if (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS) { playHop(-1); return Futures.immediateVoidFuture() }
        jumpTo(mediaItemIndex, positionMs)
        return Futures.immediateVoidFuture()
    }

    private val position: Long get() {
        if (!engineActive) return heldPositionMs
        if (seekTarget >= 0) {
            val e = Engine.elapsedMs
            val settled = kotlin.math.abs(e - seekTarget) < 1500 ||
                android.os.SystemClock.uptimeMillis() - seekAt > 2000
            if (!settled) return seekTarget
            seekTarget = -1
        }
        return Engine.elapsedMs
    }

    private fun engineSeek(ms: Long) {
        Engine.seekMs(ms)
        seekTarget = ms
        seekAt = android.os.SystemClock.uptimeMillis()
    }

    /** Seek in the track being heard. If the decoder has already moved on to the
     *  next file (the buffer holds this one's end), start this one again there. */
    private fun seekWithin(ms: Long) {
        if (Engine.decodeSerial == Engine.trackSerial) {
            engineSeek(ms)
        } else {
            stopEngine()
            heldPositionMs = ms
            startCurrent(ms)
        }
    }

    /**
     * Rockbox gui/wps.c play_hop(): "Skip Length" seconds instead of a track,
     * "Skip to Outro", "Prevent Track Skipping" and "Rewind Across Tracks".
     */
    private fun playHop(direction: Int) {
        val step = setting("skip length") * 1000L
        var elapsed = position
        val length = entries.getOrNull(index)?.durationMs?.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE / 4
        val remaining = length - elapsed
        val prevent = setting("prevent track skip") != 0
        when {
            step < 0 -> {   // Skip to Outro
                if (direction < 0) return prevTrack(SKIP_THRESH_MS)
                if (remaining < SKIP_THRESH_MS * 2) return nextTrack()
                elapsed += remaining - SKIP_THRESH_MS * 2
            }
            !prevent && (step == 0L || (direction > 0 && step >= remaining) ||
                (direction < 0 && elapsed < SKIP_THRESH_MS)) -> {
                if (direction > 0) return nextTrack()
                if (step > 0 && setting("rewind across tracks") != 0 && elapsed < SKIP_THRESH_MS && index > 0) {
                    val prev = entries[index - 1]
                    val prevLen = prev.durationMs.takeIf { it != C.TIME_UNSET } ?: 0L
                    return jumpTo(index - 1, (prevLen - step).coerceAtLeast(0))
                }
                return prevTrack(SKIP_THRESH_MS)
            }
            direction > 0 && step >= remaining -> return   // "no more" beep in Rockbox
            direction < 0 && elapsed < step -> elapsed = 0
            else -> elapsed += step * direction
        }
        jumpTo(C.INDEX_UNSET, elapsed)
    }

    /** Rockbox prev_track(): restart the track, or the previous one in the first seconds. */
    private fun prevTrack(threshMs: Long) {
        if (position >= threshMs) return jumpTo(C.INDEX_UNSET, 0)
        val prev = when {
            index > 0 -> index - 1
            repeat == 1 || repeat == 3 -> entries.size - 1
            else -> return jumpTo(C.INDEX_UNSET, 0)
        }
        jumpTo(prev, 0)
    }

    /** Rockbox audio_next(): the next track; past the end, wrap with repeat, else stop. */
    private fun nextTrack() {
        when {
            index + 1 < entries.size -> jumpTo(index + 1, 0)
            repeat == 3 -> { stopEngine(); applyShuffle(true, keepCurrentFirst = false); jumpTo(0, 0) }
            repeat == 1 -> jumpTo(0, 0)
            else -> {
                stopEngine()
                playbackState = Player.STATE_ENDED
                heldPositionMs = 0
                updateHolds()
                invalidateState()
            }
        }
    }

    private fun jumpTo(mediaItemIndex: Int, positionMs: Long) {
        val target = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex.coerceIn(0, entries.size - 1)
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
        if (target == index && engineActive) {
            seekWithin(pos)
        } else if (engineActive && playbackState == Player.STATE_READY && skipTo(target, pos)) {
            // Manual track change through the engine: crossfades if the setting says so.
        } else {
            stopEngine()
            index = target
            heldPositionMs = pos
            if (playbackState == Player.STATE_READY) startCurrent(pos)
        }
        updateHolds()
        invalidateState()
    }

    /** Rockbox manual track change, keeping the output going. False if the engine can't. */
    private fun skipTo(target: Int, pos: Long): Boolean {
        val entry = entries.getOrNull(target) ?: return false
        val uri = entry.item.localConfiguration?.uri ?: entry.item.requestMetadata.mediaUri
        val (pfd, name) = uri?.let { open(it) } ?: return false
        val serial = Engine.skipTo(pfd.detachFd(), name, pos)
        if (serial < 0) return false
        serialEntries[serial] = entry
        queuedNext = null
        index = target
        heldPositionMs = pos
        seekTarget = pos
        seekAt = android.os.SystemClock.uptimeMillis()
        return true
    }

    companion object {
        private const val TAG = "SlopPlayer"
        private const val POLL_MS = 250L
        /** Rockbox gui/wps.h DEFAULT_SKIP_THRESH. */
        private const val SKIP_THRESH_MS = 3000L
    }
}
