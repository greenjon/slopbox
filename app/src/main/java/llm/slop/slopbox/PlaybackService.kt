package llm.slop.slopbox

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Keeps playback going in the background and publishes the MediaSession:
 * the media notification, lock screen controls, headset and Bluetooth buttons.
 * Media3 runs it in the foreground while something plays.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var power: PowerMgmt.Target? = null

    override fun onCreate() {
        super.onCreate()
        SettingsStore.get(this) // the user's Rockbox settings, applied to the engine
        Pitch.init(this)        // global_status resume_pitch / resume_speed
        val player = SlopPlayer(this)
        player.onAbChanged = { a, b ->
            session?.setSessionExtras(android.os.Bundle().apply {
                putLong(EXTRA_AB_A, a)
                putLong(EXTRA_AB_B, b)
            })
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        power = object : PowerMgmt.Target {
            override val isPlaying get() = player.isPlayingNow
            override fun sleepPause() = player.sleepPause()
            override fun powerOff() = player.powerOff { stopSelf() }
        }.also { PowerMgmt.attach(this, it) }
        // Rockbox's yes/no and bookmark-list prompts, asked through the app's screen.
        player.askUi = ask@{ kind, payload, answer ->
            val s = session ?: return@ask false
            val ui = s.connectedControllers.firstOrNull {
                it.packageName == packageName && !s.isMediaNotificationController(it)
            } ?: return@ask false
            val args = android.os.Bundle().apply {
                putString(ARG_KIND, kind)
                putStringArrayList(ARG_LINES, ArrayList(payload))
            }
            val f = s.sendCustomCommand(ui, SessionCommand(CMD_ASK, android.os.Bundle.EMPTY), args)
            f.addListener({
                val r = runCatching { f.get() }.getOrNull()
                answer(if (r?.resultCode == SessionResult.RESULT_SUCCESS) r.extras.getInt(ARG_CHOICE, -1) else -1)
            }, androidx.core.content.ContextCompat.getMainExecutor(this))
            true
        }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_AB_SET_A, android.os.Bundle.EMPTY))
                        .add(SessionCommand(CMD_AB_SET_B, android.os.Bundle.EMPTY))
                        .add(SessionCommand(CMD_AB_RESET, android.os.Bundle.EMPTY))
                        .add(SessionCommand(CMD_BOOKMARK_CREATE, android.os.Bundle.EMPTY))
                        .add(SessionCommand(CMD_BOOKMARK_PLAY, android.os.Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                // Every command from a controller (UI, notification, headset) is a
                // key press for the sleep timer and idle poweroff.
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int {
                    PowerMgmt.userActivity()
                    return super.onPlayerCommandRequest(session, controller, playerCommand)
                }

                // Rockbox's WPS A-B actions (ACTION_WPS_ABSETA_PREVDIR etc.).
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: android.os.Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_AB_SET_A -> player.setAbA()
                        CMD_AB_SET_B -> player.setAbB()
                        CMD_AB_RESET -> player.resetAb()
                        CMD_BOOKMARK_CREATE -> player.createBookmark(toFile = true)
                        CMD_BOOKMARK_PLAY -> args.getString(ARG_LINE)?.let { player.playBookmark(it) }
                        else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // Items arrive from controllers without their URI (it isn't sent
                // across); MainActivity puts it in requestMetadata.mediaUri.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                        if (uri == null) item else item.buildUpon().setUri(uri).build()
                    }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music playing, but not a paused player.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    companion object {
        /** A-B repeat, for controllers: send these as custom session commands. The
         *  markers come back in the session extras (ms, -1 when unset). */
        const val CMD_AB_SET_A = "slopbox.AB_SET_A"
        const val CMD_AB_SET_B = "slopbox.AB_SET_B"
        const val CMD_AB_RESET = "slopbox.AB_RESET"
        const val EXTRA_AB_A = "slopbox.abA"
        const val EXTRA_AB_B = "slopbox.abB"

        /** Bookmarks: create one for the current playlist; play one (ARG_LINE). */
        const val CMD_BOOKMARK_CREATE = "slopbox.BOOKMARK_CREATE"
        const val CMD_BOOKMARK_PLAY = "slopbox.BOOKMARK_PLAY"
        const val ARG_LINE = "line"
        /** Sent to the app's controller: ARG_KIND (SlopPlayer.ASK_*), ARG_LINES; the
         *  answer comes back as ARG_CHOICE (1 = yes / bookmark index, -1 = no). */
        const val CMD_ASK = "slopbox.ASK"
        const val ARG_KIND = "kind"
        const val ARG_LINES = "lines"
        const val ARG_CHOICE = "choice"
    }

    override fun onDestroy() {
        power?.let { PowerMgmt.detach(it) }
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
