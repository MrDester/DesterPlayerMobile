package com.destur.phoneaudiosender

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log

class MediaSessionNotificationListener : NotificationListenerService() {
    private lateinit var sessionManager: MediaSessionManager

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            updateFromSessions(sessions.orEmpty())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, MediaSessionNotificationListener::class.java)
        sessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, component)
        refreshSessions(component)
        Log.i(TAG, "Media session listener connected")
    }

    override fun onListenerDisconnected() {
        if (::sessionManager.isInitialized) {
            sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
        }
        Log.i(TAG, "Media session listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (::sessionManager.isInitialized) {
            refreshSessions(ComponentName(this, MediaSessionNotificationListener::class.java))
        }
    }

    private fun refreshSessions(component: ComponentName) {
        try {
            updateFromSessions(sessionManager.getActiveSessions(component))
        } catch (error: SecurityException) {
            Log.w(TAG, "Media session access is not granted", error)
        }
    }

    private fun updateFromSessions(sessions: List<MediaController>) {
        val playing = sessions.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val selected = playing ?: sessions.firstOrNull()
        CurrentTrackStore.update(selected)
    }

    companion object {
        private const val TAG = "PhoneAudioSender"
    }
}
