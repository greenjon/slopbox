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
    fun setPaused(paused: Boolean) = nativeSetPaused(paused)
    val isPlaying: Boolean get() = nativeIsPlaying()
    val lastResult: Int get() = nativeLastResult()
    val elapsedMs: Long get() = nativeElapsedMs()
    fun seekMs(ms: Long) = nativeSeekMs(ms)

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
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeLastResult(): Int
    private external fun nativeElapsedMs(): Long
    private external fun nativeSeekMs(ms: Long)
    private external fun nativeTrackInfo(): Array<String>
    private external fun nativeSetSetting(id: Int, value: Int)
    private external fun nativeSetEqBand(band: Int, type: Int, hz: Int, q: Int, gain: Int)
    private external fun nativeSetShuffle(on: Boolean)
    private external fun nativeSetDuckDb(db: Int)
    private external fun nativeSetTempo(tempo: Double)
    private external fun nativeSetPitch(rate: Double)
}
