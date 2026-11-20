package llm.slop.slopbox

/** Kotlin face of the native engine (Rockbox codecs + DSP). One track at a time. */
object Engine {
    init {
        System.loadLibrary("slopbox")
        nativeInit()
    }

    data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val codec: String,
        val lengthMs: Long,
        val frequency: Long,
        val bitrate: Int,
    )

    /** Takes ownership of [fd]; [name] is used to detect the format by extension. */
    fun play(fd: Int, name: String, startMs: Long = 0, paused: Boolean = false): Boolean =
        nativePlayFd(fd, name, startMs, paused)
    fun stop() = nativeStop()
    /** Queue [fd] to start the moment the current track ends (gapless). Takes ownership. */
    fun queueNext(fd: Int, name: String): Boolean = nativeQueueNext(fd, name)
    fun clearNext() = nativeClearNext()
    /** Changes each time a file starts, including gapless moves to a queued one. */
    val trackSerial: Int get() = nativeTrackSerial()
    /** [fade]: Rockbox's Fade on Stop/Pause, a ~1/3 s ramp. */
    fun setPaused(paused: Boolean, fade: Boolean = false) = nativeSetPaused(paused, fade)
    val isPlaying: Boolean get() = nativeIsPlaying()
    val lastResult: Int get() = nativeLastResult()
    val elapsedMs: Long get() = nativeElapsedMs()
    fun seekMs(ms: Long) = nativeSeekMs(ms)

    /** A file's tags as Rockbox's own parser (get_metadata) reads them. */
    data class RockboxTags(
        val title: String, val artist: String, val album: String, val albumArtist: String,
        val genre: String, val composer: String, val comment: String, val grouping: String,
        /** As written in the file, e.g. "1/2", "3/12", "1999-05-01". */
        val disc: String, val track: String, val year: String,
        val musicBrainzTrackId: String, val codec: String,
        val trackNum: Int, val discNum: Int, val yearNum: Int,
        val lengthMs: Long, val frequency: Long, val bitrate: Int,
        /** ReplayGain as Rockbox keeps it: linear factors, 1 shl 24 = unity, 0 = none. */
        val trackGain: Long, val albumGain: Long, val trackPeak: Long, val albumPeak: Long,
        /** Embedded art: 1 BMP, 2 PNG, 3 JPG, 0 none; flags bit 0 ID3 unsync, bit 1 Vorbis base64. */
        val artType: Int, val artFlags: Int, val artPos: Long, val artSize: Int,
    )

    /** Read tags without playing; [fd] stays open (caller closes it). Null if unreadable. */
    fun readTags(fd: Int, name: String): RockboxTags? {
        val a = nativeReadTags(fd, name) ?: return null
        fun i(k: Int) = a[k].toIntOrNull() ?: 0
        fun l(k: Int) = a[k].toLongOrNull() ?: 0L
        return RockboxTags(
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11], a[12],
            i(13), i(14), i(15), l(16), l(17), i(18), l(19), l(20), l(21), l(22),
            i(23), i(24), l(25), i(26),
        )
    }

    fun trackInfo(): TrackInfo {
        val a = nativeTrackInfo()
        return TrackInfo(a[0], a[1], a[2], a[3], a[4].toLong(), a[5].toLong(), a[6].toInt())
    }

    /** A Rockbox setting that reaches the DSP; [id] from [EngineSetting], value as Rockbox stores it. */
    fun setSetting(id: Int, value: Int) = nativeSetSetting(id, value)
    /** Rockbox "eq filter N": type 0 low shelf / 1 peak / 2 high shelf, Hz, Q and gain in tenths. */
    fun setEqBand(band: Int, type: Int, hz: Int, q: Int, gain: Int) = nativeSetEqBand(band, type, hz, q, gain)
    /** ReplayGain's "track gain if shuffling" mode follows this. */
    fun setShuffle(on: Boolean) = nativeSetShuffle(on)
    fun setTempo(tempo: Double) = nativeSetTempo(tempo)
    fun setPitch(rate: Double) = nativeSetPitch(rate)
    /** Lower the volume while another app briefly talks over us (navigation etc.). */
    fun setDuck(duck: Boolean) = nativeSetDuckDb(if (duck) DUCK_DB else 0)
    private const val DUCK_DB = -12

    private external fun nativeInit()
    private external fun nativePlayFd(fd: Int, name: String, startMs: Long, paused: Boolean): Boolean
    private external fun nativeStop()
    private external fun nativeQueueNext(fd: Int, name: String): Boolean
    private external fun nativeClearNext()
    private external fun nativeTrackSerial(): Int
    private external fun nativeSetPaused(paused: Boolean, fade: Boolean)
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeLastResult(): Int
    private external fun nativeElapsedMs(): Long
    private external fun nativeSeekMs(ms: Long)
    private external fun nativeTrackInfo(): Array<String>
    private external fun nativeReadTags(fd: Int, name: String): Array<String>?
    private external fun nativeSetSetting(id: Int, value: Int)
    private external fun nativeSetEqBand(band: Int, type: Int, hz: Int, q: Int, gain: Int)
    private external fun nativeSetShuffle(on: Boolean)
    private external fun nativeSetDuckDb(db: Int)
    private external fun nativeSetTempo(tempo: Double)
    private external fun nativeSetPitch(rate: Double)
}
