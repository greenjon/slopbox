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
    fun play(fd: Int, name: String): Boolean = nativePlayFd(fd, name)
    fun stop() = nativeStop()
    fun setPaused(paused: Boolean) = nativeSetPaused(paused)
    val isPlaying: Boolean get() = nativeIsPlaying()
    val lastResult: Int get() = nativeLastResult()
    val elapsedMs: Long get() = nativeElapsedMs()
    fun seekMs(ms: Long) = nativeSeekMs(ms)

    fun trackInfo(): TrackInfo {
        val a = nativeTrackInfo()
        return TrackInfo(a[0], a[1], a[2], a[3], a[4].toLong(), a[5].toLong(), a[6].toInt())
    }

    fun setEqEnabled(on: Boolean) = nativeSetEqEnabled(on)
    /** [q] in tenths, [gain] in tenths of a dB. [type]: 0 low shelf, 1 peak, 2 high shelf. */
    fun setEqBand(band: Int, type: Int, hz: Int, q: Int, gain: Int) =
        nativeSetEqBand(band, type, hz, q, gain)
    fun setEqPrecut(tenthsDb: Int) = nativeSetEqPrecut(tenthsDb)
    /** 0 off, 1 Meier, 2 custom. */
    fun setCrossfeed(type: Int) = nativeSetCrossfeed(type)
    fun setTempo(tempo: Double) = nativeSetTempo(tempo)
    fun setPitch(rate: Double) = nativeSetPitch(rate)

    private external fun nativeInit()
    private external fun nativePlayFd(fd: Int, name: String): Boolean
    private external fun nativeStop()
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeLastResult(): Int
    private external fun nativeElapsedMs(): Long
    private external fun nativeSeekMs(ms: Long)
    private external fun nativeTrackInfo(): Array<String>
    private external fun nativeSetEqEnabled(on: Boolean)
    private external fun nativeSetEqBand(band: Int, type: Int, hz: Int, q: Int, gain: Int)
    private external fun nativeSetEqPrecut(precut: Int)
    private external fun nativeSetCrossfeed(type: Int)
    private external fun nativeSetTempo(tempo: Double)
    private external fun nativeSetPitch(rate: Double)
}
