package com.example.musicplayer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Process
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import com.example.musicplayer.MainActivity
import com.example.musicplayer.R

/**
 * Servizio di riproduzione in background basato su AndroidX Media3 MediaSessionService
 * e gestito da un'istanza di ExoPlayer.
 *
 * Configurato per gestire sessioni audio, controlli da notifica di sistema e lock screen.
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var isForegroundActive = false

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel_name)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_music_note)
            }
        setMediaNotificationProvider(notificationProvider)

        // Configurazione degli AudioAttributes per la gestione automatica dell'Audio Focus di sistema
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        // Pre-allocazione deterministica della sessione audio di sistema
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val allocatedAudioSessionId = audioManager.generateAudioSessionId()

        // Creazione dell'istanza di ExoPlayer con gestione Audio Focus e disconnessione cuffie
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
            .build()

        exoPlayer.setAudioSessionId(allocatedAudioSessionId)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build()
            )
            .build()
        player = exoPlayer

        // Inizializzazione immediata di equalizzatore ed effetti hardware collegati alla sessione audio pre-allocata
        EqualizerManager.init(this, allocatedAudioSessionId)
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != androidx.media3.common.C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                    EqualizerManager.init(this@PlaybackService, audioSessionId)
                }
            }
        })

        // PendingIntent per consentire all'utente di toccare la notifica e riaprire l'app
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ForwardingPlayer per garantire l'avvio immediato della riproduzione
        // al passaggio traccia successivo/precedente anche se il lettore era in pausa
        val autoPlayForwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun seekToNextMediaItem() {
                super.seekToNextMediaItem()
                play()
            }

            override fun seekToPreviousMediaItem() {
                super.seekToPreviousMediaItem()
                play()
            }

            override fun seekToNext() {
                super.seekToNext()
                play()
            }

            override fun seekToPrevious() {
                super.seekToPrevious()
                play()
            }
        }

        // Configurazione del BitmapLoader per copertine su notifiche e lock screen
        val bitmapLoader = Media3BitmapLoader(this)

        // Configurazione della MediaSession associata al ForwardingPlayer
        // con verifica del chiamante e restrizione dei comandi concessi (CWE-284)
        mediaSession = MediaSession.Builder(this, autoPlayForwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setBitmapLoader(bitmapLoader)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    if (isSelfOrNotificationController(session, controller)) {
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
                    }
                    if (isTrustedSystemController(session, controller)) {
                        val safePlayerCommands = Player.Commands.Builder()
                            .addAll(
                                Player.COMMAND_PLAY_PAUSE,
                                Player.COMMAND_SEEK_TO_NEXT,
                                Player.COMMAND_SEEK_TO_PREVIOUS,
                                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                                Player.COMMAND_STOP,
                                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                                Player.COMMAND_GET_METADATA,
                                Player.COMMAND_GET_TIMELINE,
                                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                                Player.COMMAND_GET_VOLUME,
                                Player.COMMAND_GET_DEVICE_VOLUME
                            )
                            .build()
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(SessionCommands.EMPTY)
                            .setAvailablePlayerCommands(safePlayerCommands)
                            .build()
                    }
                    return MediaSession.ConnectionResult.reject()
                }
            })
            .build()
    }

    @OptIn(UnstableApi::class)
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = mediaSession ?: return null
        return if (
            isSelfOrNotificationController(session, controllerInfo) ||
            isTrustedSystemController(session, controllerInfo)
        ) {
            session
        } else {
            null
        }
    }

    @OptIn(UnstableApi::class)
    private fun isSelfOrNotificationController(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo
    ): Boolean {
        val isOwnApp = controllerInfo.uid == Process.myUid() &&
            controllerInfo.packageName == packageName
        return isOwnApp || session.isMediaNotificationController(controllerInfo)
    }

    @OptIn(UnstableApi::class)
    private fun isTrustedSystemController(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo
    ): Boolean {
        return controllerInfo.uid == Process.SYSTEM_UID ||
            controllerInfo.isTrusted ||
            session.isAutomotiveController(controllerInfo) ||
            session.isAutoCompanionController(controllerInfo)
    }

    @OptIn(UnstableApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_START_FOREGROUND && !isForegroundActive) {
            promoteToForegroundWithFallbackNotification()
        }
        return result
    }

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) {
            isForegroundActive = true
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    private fun ensurePlaybackNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun promoteToForegroundWithFallbackNotification() {
        ensurePlaybackNotificationChannel()
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(
                FALLBACK_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
            isForegroundActive = true
        } catch (_: Exception) {
            // Il sistema può bloccare la promozione in scenari restrittivi; Media3 tenterà al primo update.
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Arrestiamo il servizio dai recenti solo quando la riproduzione è effettivamente ferma o terminata.
        val activePlayer = player
        val shouldStop = activePlayer == null || shouldStopServiceOnTaskRemoved(
            isPlaying = activePlayer.isPlaying,
            playWhenReady = activePlayer.playWhenReady,
            playbackState = activePlayer.playbackState,
            mediaItemCount = activePlayer.mediaItemCount
        )
        if (shouldStop) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        isForegroundActive = false
        EqualizerManager.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_FOREGROUND = "com.example.musicplayer.action.START_FOREGROUND"
        const val CHANNEL_ID = "musical_playback_channel"
        const val FALLBACK_NOTIFICATION_ID = 1001

        /**
         * Determina se `PlaybackService` deve arrestarsi alla rimozione del task dai recenti.
         * Restituisce false finché c'è riproduzione attiva o `playWhenReady = true` (es. buffering
         * o perdita transitoria dell'Audio Focus) su una coda non vuota e non terminata.
         */
        fun shouldStopServiceOnTaskRemoved(
            isPlaying: Boolean,
            playWhenReady: Boolean,
            playbackState: Int,
            mediaItemCount: Int
        ): Boolean {
            if (mediaItemCount <= 0) return true
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) return true
            return !isPlaying && !playWhenReady
        }
    }
}
