/*
 * SlopBox - https://github.com/greenjon/slopbox
 * Copyright (C) 2026 greenjon and the SlopBox contributors
 *
 * Derived from Rockbox (https://www.rockbox.org/):
 *   apps/playlist.c - Copyright (C) 2002 by wavey@wavey.org
 *   apps/playback.c - Copyright (C) 2005-2007 Miika Pekkarinen; 2007-2008 Nicolas Pennequin; 2011      Michael Sevakis
 *   apps/gui/wps.c - Copyright (C) 2002 Jerome Kuptz
 *   apps/bookmark.c - Copyright (C) 2003 by Benjamin Metzler
 *   apps/cuesheet.c - Copyright (C) 2007 Nicolas Pennequin, Jonathan Gordon
 *   apps/onplay.c - Copyright (C) 2002 Björn Stenberg
 *   apps/tagtree.c - Copyright (C) 2005 by Miika Pekkarinen
 * and the other Rockbox contributors (third_party/rockbox/docs/CREDITS).
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
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

    private class Entry(val uid: Long, var item: MediaItem) {
        var metadata: MediaMetadata? = null
        var durationMs: Long = C.TIME_UNSET
        /** Rockbox tags, read on demand (Single Mode compares them). */
        var tags: Engine.RockboxTags? = null
        var tagsRead = false
        /** Rockbox cuesheet for this file, if Cuesheet Support found one. */
        /** Rockbox PLAYLIST_QUEUED: played once, then taken out of the playlist. */
        var queued = false
        /** Added by an insert (Rockbox PLAYLIST_INSERT_TYPE_INSERT). */
        var inserted = false
        var cue: Cuesheet? = null
        var cueLoaded = false
        var cueTrack = -1
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
    /** setMediaItems arrived before prepare: check for a bookmark at prepare. */
    private var pendingAutoload = false
    /** A bookmark is being loaded (or the user asked which): don't start the queue meanwhile. */
    private var holdStart = false

    private val settingsListener: () -> Unit = {
        val want = setting("shuffle") != 0
        if (want != shuffled) applyShuffle(want, keepCurrentFirst = true)
        Engine.setShuffle(shuffled)
        queueFollowing()
        invalidateState()
    }

    init {
        current = this
        Pitch.onChange = { handler.post { invalidateState() } }
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
        // Rockbox runtime database: play count, play time, last played.
        llm.slop.slopbox.ui.db.TagDb.trackFinished(context, e.item.mediaId, elapsedMs, length, autoSkip)
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
            2 -> autoresumable(e)       // In the custom folders
            else -> false               // No
        }
        val at = if (resume) memory.resumePoint(keyOf(e)) else 0L
        return if (at > 0) (at - setting("resume rewind") * 1000L).coerceAtLeast(0) else 0
    }

    /**
     * Rockbox playback.c autoresumable(): the track is inside one of the
     * "autoresume next track paths" (colon-separated, whole folder names,
     * case-insensitive).
     */
    private fun autoresumable(e: Entry): Boolean {
        val path = e.uri?.let { PlaybackMemory.pathOf(it) } ?: return false
        val paths = store.catalog.byCfg["autoresume next track paths"]?.let { store.text(it) } ?: return false
        return paths.split(':').filter { it.isNotEmpty() }.any { p ->
            path.length > p.length && path.startsWith(p, ignoreCase = true) &&
                (path[p.length] == '/' || p.endsWith('/'))
        }
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

    /** The A and B markers (ms, -1 when unset), for the WPS to draw. */
    val abMarkers: Pair<Long, Long> get() = abA to abB

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
        if (playlistFileOf() != null) return null
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
        } else if (play && playbackState == Player.STATE_READY && entries.isNotEmpty() && !holdStart) {
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
        loadCue(entry)
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
            val next = entries[n]
            index = n
            dropIfQueued(current)
            index = entries.indexOfFirst { it === next }.coerceAtLeast(0)
            movedOn()
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
                val leaving = serialEntries[playSerial]
                if (p != manualSerial) leaving?.let { old ->
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
                loadCue(entries.getOrNull(index))
                dropIfQueued(leaving)
                movedOn()
                invalidateState()
                savePlaylist()
            }
            // Rockbox cuesheet_subtrack_changed(): follow the cue track being heard.
            entries.getOrNull(index)?.let { e ->
                val cue = currentCue
                if (cue != null) {
                    val t = cue.currentTrack(position)
                    if (t != e.cueTrack) { e.cueTrack = t; invalidateState() }
                }
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
                .setMediaMetadata(cueMetadata(e, e.metadata ?: e.item.mediaMetadata))
                .setDurationUs(if (e.durationMs == C.TIME_UNSET) C.TIME_UNSET else e.durationMs * 1000)
                .setIsSeekable(true)
                .build()
        }
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            // Rockbox pitch x timestretch: how fast the track's time runs.
            .setPlaybackParameters(androidx.media3.common.PlaybackParameters(
                Pitch.getSpeed(Pitch.pitch, Pitch.stretch) / Pitch.P100.toFloat()))
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
            val autoload = pendingAutoload
            pendingAutoload = false
            if (!(autoload && autoloadBookmark())) startCurrent(heldPositionMs)
        }
        updateHolds()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (partyLocked) return Futures.immediateVoidFuture()
        autobookmark(promptOk = true)
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
        if (current === this) { current = null; Pitch.onChange = null }
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
        if (engineActive) {
            autobookmark(promptOk = true)
            trackFinished(entries.getOrNull(index), position, autoSkip = false)
        }
        resetAb()
        stopEngine()
        entries.clear()
        lastInsertPos = -1
        playlistModified = false
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
            if (playbackState == Player.STATE_READY && !autoloadBookmark()) startCurrent(heldPositionMs)
        } else {
            pendingAutoload = true
        }
        updateHolds()
        savePlaylist()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, entries.size)
        playlistModified = true
        val added = mediaItems.map { Entry(nextUid++, it).also { e -> e.inserted = true } }
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
        playlistModified = true
        if (lastInsertPos >= fromIndex) lastInsertPos = (lastInsertPos - (toIndex - fromIndex)).coerceAtLeast(-1)
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
        playlistModified = true
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
        var remaining = length - elapsed
        // With a cuesheet, "remaining" is to the next cue track (wps.c play_hop()).
        currentCue?.let { cue ->
            val next = cue.currentTrack(elapsed) + 1
            if (next < cue.tracks.size) remaining = cue.tracks[next].offsetMs - elapsed
        }
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
        if (position >= threshMs) {
            currentCue?.skip(-1, position)?.let { return seekWithin(it) }   // curr_cuesheet_skip()
            return jumpTo(C.INDEX_UNSET, 0)
        }
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
        currentCue?.skip(1, position)?.let { return seekWithin(it) }        // curr_cuesheet_skip()
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
        val leaving = entries.getOrNull(index)
        if (target != index && engineActive) {
            trackFinished(leaving, position, autoSkip = false)
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
        if (leaving != null && leaving !== entries.getOrNull(index)) {
            val now = entries.getOrNull(index)
            dropIfQueued(leaving)
            now?.let { t -> entries.indexOfFirst { it === t }.takeIf { it >= 0 }?.let { index = it } }
        }
        movedOn()
        updateHolds()
        invalidateState()
        savePlaylist()
    }

    // ---- Bookmarks (Rockbox apps/bookmark.c) ----------------------------------------

    val bookmarks = Bookmarks(context)

    /** Asks the UI a question: kind ASK_CREATE (answer 1 = yes) or ASK_LOAD (payload =
     *  bookmark lines, answer = chosen index, -1 = don't resume). False if no UI. */
    var askUi: ((kind: String, payload: List<String>, answer: (Int) -> Unit) -> Boolean)? = null

    /** The playlist's bookmark name, if it can be bookmarked (a folder playlist). */
    val bookmarkPlaylist: String? get() =
        playlistFileOf() ?: folderPlaylist()?.second?.let { Bookmarks.playlistName(it) }

    /** The playlist file the whole playlist came from (Rockbox playlist name), if any. */
    private fun playlistFileOf(): String? {
        val name = entries.firstOrNull()?.item?.requestMetadata?.extras?.getString(PlaylistFiles.EXTRA_PLAYLIST) ?: return null
        return if (entries.all { it.item.requestMetadata.extras?.getString(PlaylistFiles.EXTRA_PLAYLIST) == name }) name else null
    }

    /** Current playlist as track paths, without queued tracks (Rockbox pl_save_playlist()). */
    fun playlistPaths(): List<String> = entries.filter { !it.queued }.mapNotNull { e -> e.uri?.let { PlaybackMemory.pathOf(it) } }

    /** After Save Current Playlist the playlist has that name (Rockbox playlist->filename). */
    fun namePlaylist(path: String) {
        entries.forEach { e ->
            val ex = (e.item.requestMetadata.extras ?: android.os.Bundle()).apply { putString(PlaylistFiles.EXTRA_PLAYLIST, path) }
            e.item = e.item.buildUpon().setRequestMetadata(e.item.requestMetadata.buildUpon().setExtras(ex).build()).build()
        }
        playlistModified = false
        invalidateState()
        savePlaylist()
    }

    private fun fileName(e: Entry): String =
        e.uri?.let { u -> PlaybackMemory.pathOf(u).substringAfterLast('/') } ?: e.item.mediaId

    /** Rockbox create_bookmark() from the current state. */
    private fun bookmarkLine(): String? {
        val playlist = bookmarkPlaylist ?: return null
        val e = entries.getOrNull(index) ?: return null
        val natural = if (shuffled) original.indexOfFirst { it === e }.coerceAtLeast(0) else index
        return Bookmarks.format(natural, position, 0, repeat, shuffled,
            Pitch.pitch.toLong(), Pitch.stretch.toLong(), playlist, fileName(e))
    }

    /** Rockbox write_bookmark(); the "Create Bookmark" menu item writes the file. */
    fun createBookmark(toFile: Boolean = true): Boolean {
        val line = bookmarkLine()
        val ok = line != null && bookmarks.write(line, toFile, toRecent = true, recentMode = setting("use most-recent-bookmarks"))
        android.widget.Toast.makeText(context,
            store.catalog.str(if (ok) "LANG_BOOKMARK_CREATE_SUCCESS" else "LANG_BOOKMARK_CREATE_FAILURE"),
            android.widget.Toast.LENGTH_SHORT).show()
        return ok
    }

    /** Rockbox bookmark_autobookmark(), when playback stops. */
    private fun autobookmark(promptOk: Boolean) {
        val playlist = bookmarkPlaylist ?: return
        if (!engineActive && entries.isEmpty()) return
        if (setting("autoupdate bookmarks") != 0 && bookmarks.exists(playlist)) { createBookmark(true); return }
        when (setting("autocreate bookmarks")) {
            1 -> createBookmark(true)                       // Yes
            3 -> createBookmark(false)                      // Yes - Recent only
            2, 4 -> if (promptOk) {                         // Ask / Ask - Recent only
                val line = bookmarkLine() ?: return
                val toFile = setting("autocreate bookmarks") == 2
                askUi?.invoke(ASK_CREATE, listOf(line)) { yes ->
                    if (yes == 1) {
                        val ok = bookmarks.write(line, toFile, true, setting("use most-recent-bookmarks"))
                        android.widget.Toast.makeText(context, store.catalog.str(
                            if (ok) "LANG_BOOKMARK_CREATE_SUCCESS" else "LANG_BOOKMARK_CREATE_FAILURE"),
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** Rockbox play_bookmark(): the bookmark's folder, repeat and shuffle, at its track and time. */
    fun playBookmark(line: String) {
        val bm = Bookmarks.parse(line) ?: return
        val tree = llm.slop.slopbox.ui.MusicTree.root(context) ?: return
        // Rockbox bookmark_play(): the bookmark's pitch and speed, when it has them.
        bm.pitch?.let { Pitch.setPitch(it) }
        bm.speed?.let { Pitch.setStretch(it) }
        val dirId = Bookmarks.dirDocId(tree, bm.playlist)
        folderJob?.cancel()
        holdStart = true
        folderJob = scope.launch {
            // A folder playlist's name ends in "/"; otherwise it's a playlist file.
            val items: List<MediaItem> = if (bm.playlist.endsWith("/")) {
                val dirDoc = llm.slop.slopbox.ui.Doc(dirId, bm.playlist.trim('/').substringAfterLast('/'), true)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    llm.slop.slopbox.ui.MusicTree.list(context, tree, dirId)
                }.filter { it.isTrack }.map { llm.slop.slopbox.ui.MusicTree.item(tree, it, dirDoc) }
            } else {
                PlaylistFiles.load(context, tree, dirId, bm.playlist.substringAfterLast('/'))
            }
            if (items.isEmpty()) { holdStart = false; return@launch }
            val names = items.map { it.requestMetadata.mediaUri?.let { u -> PlaybackMemory.pathOf(u).substringAfterLast('/') } }
            if (engineActive) trackFinished(entries.getOrNull(index), position, autoSkip = false)
            stopEngine()
            entries.clear()
            items.forEach { entries += Entry(nextUid++, it) }
            shuffled = false
            syncOriginal()
            store.catalog.byCfg["repeat"]?.let { store.set(it, bm.repeat) }
            index = names.indexOf(bm.file).takeIf { it >= 0 } ?: bm.index.coerceIn(0, entries.size - 1)
            if (bm.shuffle) applyShuffle(true, keepCurrentFirst = true)
            store.catalog.byCfg["shuffle"]?.let { store.set(it, if (bm.shuffle) 1 else 0) }
            playbackState = Player.STATE_READY
            playWhenReady = requestFocus()
            holdStart = false
            startCurrent((bm.elapsedMs - setting("resume rewind") * 1000L).coerceAtLeast(1))
            updateHolds()
            invalidateState()
            savePlaylist()
        }
    }

    /** Rockbox bookmark_autoload(), for a folder playlist that was just set up.
     *  True if it took over starting playback. */
    private fun autoloadBookmark(): Boolean {
        val mode = setting("autoload bookmarks")
        val playlist = bookmarkPlaylist ?: return false
        if (mode == 0 || !bookmarks.exists(playlist)) return false
        val list = bookmarks.list(playlist)
        if (list.isEmpty()) return false
        if (mode == 1) { playBookmark(list.first().line); return true }
        holdStart = true
        val asked = askUi?.invoke(ASK_LOAD, list.map { it.line }) { choice ->
            holdStart = false
            val bm = list.getOrNull(choice)
            if (bm != null) playBookmark(bm.line)
            else if (!engineActive) { startCurrent(heldPositionMs); updateHolds(); invalidateState() }
        } ?: false
        if (!asked) holdStart = false
        return asked
    }

    // ---- The current playlist (Rockbox apps/playlist.c, onplay.c) --------------------

    /** Rockbox last_insert_pos: where the next "Add" goes after earlier ones. */
    private var lastInsertPos = -1
    private var lastShuffledStart = 0
    /** Rockbox playlist_modified(): inserted into, removed from or reordered. */
    var playlistModified = false
        private set
    /** Something is playing (or paused) -- Rockbox audio_status(). */
    val busy: Boolean get() = engineActive

    /**
     * Rockbox add_to_playlist() / add_track_to_playlist(): [position] is one of
     * the PLAYLIST_* values, [queue] makes the tracks play once only, and
     * [replace] starts a new playlist (keeping the current track if "Keep Current
     * Track When Replacing Playlist" says so and something is playing).
     */
    fun addToPlaylist(items: List<MediaItem>, position: Int, queue: Boolean, replace: Boolean) {
        if (items.isEmpty()) return
        PowerMgmt.userActivity()
        var newPlaylist = replace
        if (replace && setting("keep current track when replacing playlist") != 0 && engineActive) {
            // playlist_remove_all_tracks(): everything but the current track goes.
            val current = entries.getOrNull(index)
            entries.retainAll { it === current }
            original.retainAll { it === current }
            index = 0
            lastInsertPos = -1
            newPlaylist = false
        }
        if (newPlaylist) {
            if (engineActive) {
                autobookmark(promptOk = true)
                trackFinished(entries.getOrNull(index), this.position, autoSkip = false)
            }
            folderJob?.cancel()
            resetAb()
            stopEngine()
            entries.clear()
            original.clear()
            shuffled = false
            index = 0
            lastInsertPos = -1
        }
        if (position == PLAYLIST_INSERT_LAST_SHUFFLED) lastShuffledStart = entries.size
        var pos = position
        for (item in items) {
            val e = Entry(nextUid++, item).also { it.queued = queue; it.inserted = !newPlaylist }
            val at = insertPosition(pos).coerceIn(0, entries.size)
            entries.add(at, e)
            original += e
            if (!newPlaylist && entries.size > 1 && at <= index) index++
            if (lastInsertPos >= 0 && at < lastInsertPos) lastInsertPos++
            // Rockbox keeps a directory in order: after the first track "Play Next" adds after it.
            if (pos == PLAYLIST_INSERT_FIRST) pos = PLAYLIST_INSERT
        }
        if (newPlaylist) {
            playlistModified = false
            if (setting("shuffle") != 0 && entries.size > 1) applyShuffle(true, keepCurrentFirst = false)
            index = 0
            heldPositionMs = 0
            playbackState = Player.STATE_READY
            playWhenReady = requestFocus()
            startCurrent(0)
        } else {
            playlistModified = true
            if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
            queueFollowing()
        }
        updateHolds()
        invalidateState()
        savePlaylist()
    }

    /** Rockbox add_track_to_playlist_unlocked(): where one track goes. */
    private fun insertPosition(position: Int): Int {
        val n = entries.size
        return when (position) {
            PLAYLIST_PREPEND -> 0
            PLAYLIST_INSERT -> {
                val p = if (lastInsertPos in 0 until n && entries[lastInsertPos].inserted) lastInsertPos + 1
                    else if (n > 0) index + 1 else 0
                lastInsertPos = p
                p
            }
            PLAYLIST_INSERT_FIRST -> (if (n > 0) index + 1 else 0).also { lastInsertPos = it }
            PLAYLIST_INSERT_LAST -> n.also { lastInsertPos = it }
            PLAYLIST_INSERT_SHUFFLED -> if (n > 0) index + 1 + kotlin.random.Random.nextInt(n - index) else 0
            PLAYLIST_INSERT_LAST_SHUFFLED -> lastShuffledStart + kotlin.random.Random.nextInt(n - lastShuffledStart + 1)
            else -> n
        }
    }

    /** Rockbox playlist_set_modified(NULL, true), e.g. after a plugin builds the playlist. */
    fun markModified() { playlistModified = true }

    /** Rockbox playlist_randomise() from the WPS: Reshuffle. */
    fun reshuffle() {
        applyShuffle(true, keepCurrentFirst = true)
        playlistModified = true
        queueFollowing()
        invalidateState()
        savePlaylist()
    }

    /** A queued track that has been left is removed (Rockbox playlist_next()). */
    private fun dropIfQueued(e: Entry?) {
        e ?: return
        if (!e.queued || e === entries.getOrNull(index)) return
        val at = entries.indexOfFirst { it === e }
        if (at < 0) return
        entries.removeAt(at)
        original.removeAll { it === e }
        if (at < index) index--
        if (lastInsertPos >= 0 && at <= lastInsertPos) lastInsertPos--
        queueFollowing()
    }

    /** Rockbox playlist_next(): passing the last inserted track resets the insert point. */
    private fun movedOn() {
        if (lastInsertPos >= 0 && index > lastInsertPos) lastInsertPos = -1
    }

    // ---- Cuesheets (Rockbox apps/cuesheet.c) -----------------------------------------

    /** Rockbox playback.c: look for the track's cuesheet when it's buffered. */
    private fun loadCue(e: Entry?) {
        e ?: return
        if (e.cueLoaded || setting("cuesheet support") == 0) return
        e.cueLoaded = true
        val uri = e.uri ?: return
        val tree = e.treeUri?.let(Uri::parse)
        val dir = e.dirId
        val name = fileName(e)
        scope.launch {
            val cue = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Cuesheet.find(context, uri, name, tree, dir) }.getOrNull()
            }
            if (cue != null) {
                e.cue = cue
                e.cueTrack = -1
                invalidateState()
            }
        }
    }

    /** The cuesheet of the track being heard, when Cuesheet Support is on. */
    private val currentCue: Cuesheet?
        get() = if (setting("cuesheet support") != 0) entries.getOrNull(index)?.cue else null

    /** For the WPS context menu's "Browse Cuesheet" (Rockbox browse_cuesheet()). */
    val cuesheet: Cuesheet? get() = currentCue

    /** Rockbox browse_cuesheet(): jump to a cue track of the playing file. */
    fun seekToCue(ms: Long) {
        if (entries.isEmpty()) return
        PowerMgmt.userActivity()
        seekWithin(ms)
        invalidateState()
    }

    /** What the skin shows for a cue track: its title and performer, the sheet as album. */
    private fun cueMetadata(e: Entry, base: MediaMetadata): MediaMetadata {
        val cue = e.cue ?: return base
        if (e.cueTrack < 0 || setting("cuesheet support") == 0) return base
        val t = cue.tracks.getOrNull(e.cueTrack) ?: return base
        return base.buildUpon()
            .setTitle(t.title.ifEmpty { base.title?.toString() })
            .setArtist(t.performer.ifEmpty { base.artist?.toString() })
            .setAlbumTitle(cue.title.ifEmpty { base.albumTitle?.toString() })
            .setTrackNumber(e.cueTrack + 1)
            .setTotalTrackCount(cue.tracks.size)
            .build()
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
        autobookmark(promptOk = false)
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
        /** Rockbox playlist.h insert positions. */
        const val PLAYLIST_PREPEND = -1
        const val PLAYLIST_INSERT = -2
        const val PLAYLIST_INSERT_LAST = -3
        const val PLAYLIST_INSERT_FIRST = -4
        const val PLAYLIST_INSERT_SHUFFLED = -5
        const val PLAYLIST_INSERT_LAST_SHUFFLED = -7

        /** The player of the running playback service (same process, main thread). */
        @Volatile var current: SlopPlayer? = null

        const val ASK_CREATE = "create"
        const val ASK_LOAD = "load"
        /** "start in screen": Resume Playback. */
        const val START_SCREEN_RESUME_PLAYBACK = 4
    }
}
