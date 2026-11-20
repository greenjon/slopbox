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
import kotlinx.coroutines.launch

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
        /** Rockbox tags, read on demand (Single Mode compares them). */
        var tags: Engine.RockboxTags? = null
        var tagsRead = false
        val uri: Uri? get() = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        val dirId: String? get() = item.requestMetadata.extras?.getString(llm.slop.slopbox.ui.MusicTree.EXTRA_DIR)
        val treeUri: String? get() = item.requestMetadata.extras?.getString(llm.slop.slopbox.ui.MusicTree.EXTRA_TREE)
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

    private val memory = PlaybackMemory(context)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var folderJob: kotlinx.coroutines.Job? = null
    /** Rockbox party mode: no pausing, stopping, seeking or skipping while music
     *  plays (see docs/DIFFERENCES.md for resuming from a pause). */
    private val partyLocked get() = setting("party mode") != 0 && engineActive && playWhenReady
    /** Rockbox A-B repeat markers (ms in the current track), -1 when unset. */
    private var abA = -1L
    private var abB = -1L
    /** Lets the service publish the A-B markers to controllers. */
    var onAbChanged: ((Long, Long) -> Unit)? = null
    private var lastSavedAt = 0L
    /** Serial started by a manual change: its arrival isn't a natural track end. */
    private var manualSerial = -1

    private val settingsListener: () -> Unit = {
        val want = setting("shuffle") != 0
        if (want != shuffled) applyShuffle(want, keepCurrentFirst = true)
        Engine.setShuffle(shuffled)
        queueFollowing()
        invalidateState()
    }

    init {
        store.addListener(settingsListener)
        if (setting("play log") != 0) memory.startLog()
        restorePlaylist()
    }

    // ---- Resume, Automatic Resume, logging -------------------------------------

    /** Rockbox playlist resume at startup: the saved playlist, paused at its position
     *  less "Rewind Before Resume"; "Start Screen: Resume Playback" also starts it. */
    private fun restorePlaylist() {
        val saved = memory.loadPlaylist() ?: return
        entries.clear()
        saved.items.forEach { entries += Entry(nextUid++, it) }
        original.clear()
        saved.original.mapNotNull { entries.getOrNull(it) }.forEach { original += it }
        if (original.size != entries.size) { original.clear(); original += entries }
        shuffled = saved.shuffled
        Engine.setShuffle(shuffled)
        index = saved.index.coerceIn(0, entries.size - 1)
        heldPositionMs = (saved.positionMs - setting("resume rewind") * 1000L).coerceAtLeast(0)
        playbackState = Player.STATE_READY
        playWhenReady = false
        if (setting("start in screen") == START_SCREEN_RESUME_PLAYBACK) {
            handler.post { setPlaying(true); invalidateState() }
        }
    }

    private fun savePlaylist() {
        lastSavedAt = android.os.SystemClock.uptimeMillis()
        if (entries.isEmpty()) { memory.clearPlaylist(); return }
        memory.savePlaylist(
            entries.map { it.item },
            original.map { e -> entries.indexOfFirst { it === e } }.filter { it >= 0 },
            shuffled, index, position,
        )
    }

    private fun keyOf(e: Entry) = e.uri?.toString() ?: e.item.mediaId

    /**
     * Rockbox's track-finish event: the playback log entry, and the Automatic
     * Resume point (none if never played or skipped in its first 3 s; 0 when it
     * finished by itself) -- tagtree_track_finish_event().
     */
    private fun trackFinished(e: Entry?, elapsedMs: Long, autoSkip: Boolean) {
        e ?: return
        val length = e.durationMs.takeIf { it != C.TIME_UNSET } ?: 0L
        if (setting("play log") != 0) e.uri?.let { memory.log(PlaybackMemory.pathOf(it), elapsedMs, length) }
        if (setting("autoresume enable") != 0) {
            if (elapsedMs == 0L || (elapsedMs < SKIP_THRESH_MS && !autoSkip)) return
            memory.setResumePoint(keyOf(e), if (autoSkip) 0 else elapsedMs)
        }
    }

    /** Rockbox audio_start_codec(): where a track starts that's given no position. */
    private fun resumeStartFor(e: Entry, autoSkip: Boolean): Long {
        if (setting("autoresume enable") == 0) return 0
        val resume = !autoSkip || when (setting("autoresume next track")) {
            1 -> true                   // Always
            2 -> false                  // In custom directories only: no directory list yet
            else -> false               // No
        }
        val at = if (resume) memory.resumePoint(keyOf(e)) else 0L
        return if (at > 0) (at - setting("resume rewind") * 1000L).coerceAtLeast(0) else 0
    }

    private fun tagsOf(e: Entry): Engine.RockboxTags? {
        if (!e.tagsRead) {
            e.tagsRead = true
            e.tags = e.uri?.let { RockboxTagFile.read(context, it)?.tags }
        }
        return e.tags
    }

    /** Rockbox single_mode_do_pause(): stop at the start of [next]? */
    private fun singleModePause(prev: Entry?, next: Entry, newPlaylist: Boolean): Boolean {
        val mode = setting("single mode")
        if (mode == 0 || setting("party mode") != 0) return false
        if (mode == SINGLE_TRACK) return true
        if (mode == SINGLE_PLAYLIST) return newPlaylist
        fun tag(e: Entry?): String? {
            val t = e?.let { tagsOf(it) } ?: return null
            return when (mode) {
                2 -> t.album; 3 -> t.albumArtist; 4 -> t.artist; 5 -> t.composer
                6 -> t.grouping; 7 -> t.genre; else -> null
            }?.takeIf { it.isNotEmpty() }
        }
        val a = tag(prev)
        val b = tag(next)
        return a == null || b == null || a != b
    }

    /** Rockbox system_sound_play(SOUND_TRACK_SKIP / SOUND_TRACK_NO_MORE). */
    private fun beep(noMore: Boolean) {
        val strength = setting("beep")
        if (strength == 0) return
        if (noMore) Engine.beep(1000, 100, 1500 * strength) else Engine.beep(2000, 100, 2500 * strength)
    }

    // ---- A-B repeat (Rockbox abrepeat.c) ---------------------------------------

    private val abMode get() = repeat == 4

    fun setAbA() {
        if (!abMode || !engineActive) return
        val p = position - EAR_TO_HAND_MS
        abA = if (p >= 0) p else -1
        if (abB in 0 until abA) abB = -1
        abChanged()
    }

    fun setAbB() {
        if (!abMode || !engineActive) return
        val p = position - EAR_TO_HAND_MS
        abB = if (p >= 0) p else -1
        if (abA > abB) abA = -1
        abChanged()
        jumpToA()
    }

    fun resetAb() {
        abA = -1
        abB = -1
        abChanged()
    }

    private fun jumpToA() { if (abA >= 0) seekWithin(abA) else seekWithin(0) }

    private fun abChanged() {
        onAbChanged?.invoke(abA, abB)
        queueFollowing()
        startPolling()
    }

    // ---- Auto-Change Directory (Rockbox playlist.c get_next_dir) -----------------

    /** A playlist made from one folder of the music tree (Rockbox's "dirplay"). */
    private fun folderPlaylist(): Pair<Uri, String>? {
        val first = entries.firstOrNull() ?: return null
        val dir = first.dirId ?: return null
        val tree = first.treeUri?.let(Uri::parse) ?: return null
        return if (entries.all { it.dirId == dir }) tree to dir else null
    }

    /** Go to the next (or previous, or a random) folder with music. False if it doesn't apply. */
    private fun changeFolder(direction: Int, autoSkip: Boolean): Boolean {
        val mode = setting("folder navigation")
        if (mode == 0) return false
        val (tree, dir) = folderPlaylist() ?: return false
        folderJob?.cancel()
        folderJob = scope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FolderWalk.find(context, tree, dir, direction, random = mode == 2)
            }
            if (found == null) {
                if (autoSkip) endOfPlaylist() else beep(noMore = true)
                return@launch
            }
            val (folder, items) = found
            trackFinished(entries.getOrNull(index), if (autoSkip) entries.getOrNull(index)?.durationMs ?: 0 else position, autoSkip)
            stopEngine()
            entries.clear()
            items.forEach { entries += Entry(nextUid++, it) }
            shuffled = false
            syncOriginal()
            if (setting("shuffle") != 0 && entries.size > 1) applyShuffle(true, keepCurrentFirst = false)
            index = if (direction < 0 && !autoSkip) entries.size - 1 else 0
            heldPositionMs = 0
            playbackState = Player.STATE_READY
            if (autoSkip && singleModePause(null, entries[index], newPlaylist = true)) playWhenReady = false
            if (!autoSkip) beep(noMore = false)
            Log.i(TAG, "folder change to ${folder.name}")
            startCurrent(0, autoSkip)
            updateHolds()
            invalidateState()
            savePlaylist()
        }
        return true
    }

    private fun endOfPlaylist() {
        playbackState = Player.STATE_ENDED
        heldPositionMs = entries.getOrNull(index)?.durationMs?.takeIf { it != C.TIME_UNSET } ?: 0L
        updateHolds()
        invalidateState()
        savePlaylist()
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

    private fun startCurrent(startMs: Long, autoSkip: Boolean = false) {
        val entry = entries.getOrNull(index) ?: return
        @Suppress("NAME_SHADOWING")
        val startMs = if (startMs == 0L) resumeStartFor(entry, autoSkip) else startMs
        val uri = entry.item.localConfiguration?.uri ?: entry.item.requestMetadata.mediaUri
        val opened = uri?.let { open(it) }
        if (opened == null) {
            Log.w(TAG, "can't open $uri; skipping")
            engineActive = false
            advanceAfterEnd(failed = true)
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
        var next = followingIndex(from)?.let { entries.getOrNull(it) }
        // Single Mode stops at the next track; A-B with only A set loops this one:
        // either way the engine finishes here and advanceAfterEnd decides.
        if (next != null && singleModePause(decodeEntry, next, newPlaylist = false)) next = null
        if (abMode && abA >= 0 && abB < 0) next = null
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
        val serial = Engine.queueNext(opened.first.detachFd(), opened.second, resumeStartFor(next, autoSkip = true))
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

    /** Current track ended by itself (or failed): next entry, or the end of the queue. */
    private fun advanceAfterEnd(failed: Boolean = false) {
        val current = entries.getOrNull(index)
        if (!failed) trackFinished(current, current?.durationMs?.takeIf { it != C.TIME_UNSET } ?: position, autoSkip = true)
        // A-B repeat with only A set: back to A at the end of the track.
        if (!failed && abMode && abA >= 0 && abB < 0) {
            startCurrent(abA)
            updateHolds()
            invalidateState()
            return
        }
        val n = followingIndex()
        if (n == null && repeat == 3 && entries.isNotEmpty()) {
            // Repeat: Shuffle -- reshuffle the whole playlist and go again.
            applyShuffle(true, keepCurrentFirst = false)
            index = 0
            startCurrent(0, autoSkip = true)
        } else if (n != null) {
            if (current != null && singleModePause(current, entries[n], newPlaylist = false)) playWhenReady = false
            index = n
            startCurrent(0, autoSkip = true)
        } else if (changeFolder(1, autoSkip = true)) {
            return
        } else {
            endOfPlaylist()
            return
        }
        updateHolds()
        invalidateState()
        savePlaylist()
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
                if (p != manualSerial) serialEntries[playSerial]?.let { old ->
                    trackFinished(old, old.durationMs.takeIf { it != C.TIME_UNSET } ?: 0L, autoSkip = true)
                }
                if (abA >= 0 || abB >= 0) resetAb()
                playSerial = p
                serialEntries[p]?.let { e ->
                    val at = entries.indexOfFirst { it === e }
                    if (at >= 0) index = at
                }
                serialEntries.keys.removeAll { it < p - 8 }
                heldPositionMs = 0
                seekTarget = -1
                invalidateState()
                savePlaylist()
            }
            // A-B: reaching B (within Rockbox's 200 ms window) jumps back to A.
            if (abMode && abB >= 0 && playWhenReady) {
                val pos = position
                if (pos >= abB && pos <= abB + B_WINDOW_MS) jumpToA()
            }
            if (android.os.SystemClock.uptimeMillis() - lastSavedAt > SAVE_EVERY_MS) savePlaylist()
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
            handler.postDelayed(this, if (abMode && abB >= 0) AB_POLL_MS else POLL_MS)
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
        if (partyLocked && !playWhenReady) return Futures.immediateVoidFuture()   // Rockbox: no pausing in party mode
        if (playWhenReady && playbackState == Player.STATE_ENDED && entries.isNotEmpty()) {
            // Play after the end of the queue: start it again from the top.
            index = 0
            heldPositionMs = 0
            playbackState = Player.STATE_READY
        }
        setPlaying(playWhenReady)
        savePlaylist()
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
        if (partyLocked) return Futures.immediateVoidFuture()
        if (engineActive) trackFinished(entries.getOrNull(index), position, autoSkip = false)
        savePlaylist()
        resetAb()
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
        savePlaylist()
        if (engineActive) trackFinished(entries.getOrNull(index), position, autoSkip = false)
        folderJob?.cancel()
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
        if (partyLocked) {
            // Rockbox party mode: playing something new queues it last instead.
            return handleAddMediaItems(entries.size, mediaItems)
        }
        folderJob?.cancel()
        if (engineActive) trackFinished(entries.getOrNull(index), position, autoSkip = false)
        resetAb()
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
        savePlaylist()
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
        if (entries.isEmpty() || partyLocked) return Futures.immediateVoidFuture()
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
        // Rockbox wps.c: in A-B mode Previous goes back to A once past it, and Next
        // jumps to A while before it (and otherwise does nothing).
        if (abMode) {
            if (direction < 0 && abA >= 0 && position > abA + A_MARKER_VIRTUAL_MS) return jumpToA()
            if (direction > 0) { if (abA >= 0 && position < abA) jumpToA(); return }
        }
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
            direction > 0 && step >= remaining -> return beep(noMore = true)
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
            changeFolder(-1, autoSkip = false) -> return
            else -> return beep(noMore = true)   // audio_skip(): no track to go to
        }
        beep(noMore = false)
        jumpTo(prev, 0)
    }

    /** Rockbox audio_next(): the next track; past the end, wrap with repeat or change
     *  folder, else just the "no more" beep. */
    private fun nextTrack() {
        when {
            index + 1 < entries.size -> { beep(noMore = false); jumpTo(index + 1, 0) }
            repeat == 3 -> {
                beep(noMore = false)
                trackFinished(entries.getOrNull(index), position, autoSkip = false)
                stopEngine(); applyShuffle(true, keepCurrentFirst = false); jumpTo(0, 0)
            }
            repeat == 1 -> { beep(noMore = false); jumpTo(0, 0) }
            changeFolder(1, autoSkip = false) -> {}
            else -> beep(noMore = true)
        }
    }

    private fun jumpTo(mediaItemIndex: Int, positionMs: Long) {
        val target = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex.coerceIn(0, entries.size - 1)
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
        if (target != index && engineActive) {
            trackFinished(entries.getOrNull(index), position, autoSkip = false)
            resetAb()
        }
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
        savePlaylist()
    }

    // ---- Power management (PowerMgmt) ---------------------------------------------

    val isPlayingNow: Boolean get() = playWhenReady && engineActive

    /** Sleep timer ran out on the charger: pause (Rockbox audio_pause()). */
    fun sleepPause() {
        setPlaying(false)
        invalidateState()
        savePlaylist()
    }

    /** Rockbox clean shutdown, for playback: stop (with the stop fade), save resume. */
    fun powerOff(done: () -> Unit) {
        if (setting("show shutdown message") != 0) {
            android.widget.Toast.makeText(context, store.catalog.str("LANG_SHUTTINGDOWN"), android.widget.Toast.LENGTH_SHORT).show()
        }
        val fade = setting("volume fade") != 0 && isPlayingNow
        if (engineActive) Engine.setPaused(true, fade)
        handler.postDelayed({
            if (engineActive) trackFinished(entries.getOrNull(index), position, autoSkip = false)
            heldPositionMs = position
            savePlaylist()
            stopEngine()
            playWhenReady = false
            playbackState = Player.STATE_IDLE
            updateHolds()
            invalidateState()
            done()
        }, if (fade) 400L else 0L)
    }

    /** Rockbox manual track change, keeping the output going. False if the engine can't. */
    private fun skipTo(target: Int, pos: Long): Boolean {
        val entry = entries.getOrNull(target) ?: return false
        val uri = entry.item.localConfiguration?.uri ?: entry.item.requestMetadata.mediaUri
        val (pfd, name) = uri?.let { open(it) } ?: return false
        @Suppress("NAME_SHADOWING")
        val pos = if (pos == 0L) resumeStartFor(entry, autoSkip = false) else pos
        val serial = Engine.skipTo(pfd.detachFd(), name, pos)
        if (serial < 0) return false
        manualSerial = serial
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
        /** Rockbox abrepeat.c / abrepeat.h constants. */
        private const val EAR_TO_HAND_MS = 200L
        private const val B_WINDOW_MS = 200L
        private const val A_MARKER_VIRTUAL_MS = 1000L
        private const val AB_POLL_MS = 50L
        private const val SAVE_EVERY_MS = 15_000L
        /** "single mode" values (settings.h SINGLE_MODE_*). */
        private const val SINGLE_TRACK = 1
        private const val SINGLE_PLAYLIST = 8
        /** "start in screen": Resume Playback. */
        const val START_SCREEN_RESUME_PLAYBACK = 4
    }
}
