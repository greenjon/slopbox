package llm.slop.slopbox

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Rockbox's sleep timer and idle poweroff (firmware/powermgmt.c,
 * apps/misc.c). On a phone, "power off" means what Rockbox's clean shutdown
 * does to playback: stop it (fading if Fade on Stop/Pause says so), save the
 * resume point, and end the playback service. See docs/DIFFERENCES.md.
 *
 * One per process; the playback service attaches the player to it.
 */
object PowerMgmt {
    interface Target {
        /** Playing, not paused (Rockbox AUDIO_STATUS_PLAY without PAUSE). */
        val isPlaying: Boolean
        /** Sleep timer ran out on the charger: just pause. */
        fun sleepPause()
        /** Power off: stop playback, save, end the service. */
        fun powerOff()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var target: Target? = null
    private var context: Context? = null
    private var sleepEndMs = 0L
    private var lastEventMs = SystemClock.elapsedRealtime()

    private fun setting(cfg: String) = context?.let { SettingsStore.get(it).int(cfg) } ?: 0

    /** Called when the playback service starts (Rockbox boot). */
    fun attach(ctx: Context, t: Target) {
        context = ctx.applicationContext
        target = t
        lastEventMs = SystemClock.elapsedRealtime()
        // "Start Sleep Timer on Boot" (settings.c settings_apply).
        if (setting("sleeptimer on startup") != 0) setSleepTimer(setting("sleeptimer duration"))
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    fun detach(t: Target) {
        if (target === t) {
            target = null
            handler.removeCallbacks(tick)
        }
    }

    /** Rockbox set_sleeptimer_duration(): 0 cancels. */
    fun setSleepTimer(minutes: Int) {
        sleepEndMs = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }

    /** Debug builds: a timer in seconds, for tests. */
    fun setSleepTimerSecondsForTest(seconds: Int) {
        sleepEndMs = SystemClock.elapsedRealtime() + seconds * 1000L
    }

    /** Seconds left, 0 when no timer runs (Rockbox get_sleep_timer()). */
    val sleepTimerSeconds: Int
        get() = if (sleepEndMs == 0L) 0
        else ((sleepEndMs - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)

    /** Rockbox sys_poweroff() from a "shutdown" shortcut. */
    fun powerOffNow() { target?.powerOff() }

    /** Rockbox toggle_sleeptimer(): cancel a running timer, else start the default one. */
    fun toggleSleepTimer() {
        setSleepTimer(if (sleepTimerSeconds > 0) 0 else setting("sleeptimer duration"))
    }

    /** Rockbox reset_poweroff_timer(): any key press (here: any user action). */
    fun userActivity() {
        lastEventMs = SystemClock.elapsedRealtime()
        if (sleepEndMs != 0L && setting("keypress restarts sleeptimer") != 0) {
            setSleepTimer(setting("sleeptimer duration"))
        }
    }

    private fun charging(): Boolean =
        context?.getSystemService(BatteryManager::class.java)?.isCharging == true

    /** Rockbox handle_auto_poweroff() + handle_sleep_timer(), once a second. */
    private val tick = object : Runnable {
        override fun run() {
            val t = target ?: return
            val now = SystemClock.elapsedRealtime()
            val charging = charging()
            if (t.isPlaying || charging) lastEventMs = now
            val timeoutMs = setting("idle poweroff") * 60_000L
            if (timeoutMs > 0 && !t.isPlaying && now - lastEventMs > timeoutMs) {
                t.powerOff()
                return
            }
            if (sleepEndMs != 0L && now >= sleepEndMs) {
                sleepEndMs = 0L
                if (charging) t.sleepPause() else { t.powerOff(); return }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private const val TICK_MS = 1000L
}
