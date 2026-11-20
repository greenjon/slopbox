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

    override fun onCreate() {
        super.onCreate()
        SettingsStore.get(this) // the user's Rockbox settings, applied to the engine
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
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
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
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
