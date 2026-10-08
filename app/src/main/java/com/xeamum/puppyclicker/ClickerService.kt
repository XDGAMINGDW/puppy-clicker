package com.xeamum.puppyclicker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class ClickerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var socket: WebSocket? = null
    private var retryDelayMs = MIN_RETRY_MS
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.serviceNotification(this), type)
        running = true
        connect()
        scope.launch {
            var previousSocket: WebSocket? = null
            var previousScreen: Boolean? = null
            var ticks = 0
            while (isActive) {
                val currentSocket = socket
                if (currentSocket != null && ClickRepository.connected.value) {
                    val prefs = Prefs(this@ClickerService)
                    val screenOn = if (prefs.shareScreenStatus) {
                        getSystemService(PowerManager::class.java).isInteractive
                    } else null
                    if (currentSocket !== previousSocket || screenOn != previousScreen || ticks >= 6) {
                        currentSocket.send(JSONObject().put("type", "presence")
                            .put("screenOn", screenOn ?: JSONObject.NULL).toString())
                        previousSocket = currentSocket
                        previousScreen = screenOn
                        ticks = 0
                    }
                    ticks += 1
                }
                delay(5_000L)
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { Updater.check() }.getOrNull()?.let { Notifications.showUpdate(this@ClickerService, it.versionName) }
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        socket?.close(1000, null)
        ClickRepository.connected.value = false
        scope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        val prefs = Prefs(this)
        if (!prefs.isConfigured) {
            stopSelf()
            return
        }
        socket = prefs.api().openSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    retryDelayMs = MIN_RETRY_MS
                    ClickRepository.connected.value = true
                    // Upload clicks used while offline.
                    runCatching { Sync.pushOrQueue(this@ClickerService) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val state = runCatching { ApiClient.parseState(json) }.getOrNull() ?: return
                ClickRepository.recordServerUpdate()
                scope.launch {
                    if (webSocket !== socket || !running) return@launch
                    prefs.role?.let { ApiClient.updatePartnerPresence(json, it) }
                    onState(state)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { reconnectLater(webSocket) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { reconnectLater(webSocket) }
            }
        })
    }

    private suspend fun reconnectLater(closed: WebSocket) {
        if (closed !== socket) return
        socket = null
        ClickRepository.connected.value = false
        delay(retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        if (running) connect()
    }

    private fun onState(server: ClickState) {
        val prefs = Prefs(this)
        val previous = prefs.lastKnownTotal
        val previousUsed = prefs.lastKnownUsed
        // Merged totals never go down, so a restored server doesn't cause duplicate notifications.
        val state = Sync.merge(this, server)
        if (prefs.role == Role.RECEIVER && previous in 0 until state.totalReceived) {
            Notifications.showClicks(this, state.totalReceived - previous, state.available)
        }
        if (prefs.role == Role.SENDER && previousUsed in 0 until state.totalUsed) {
            Notifications.showUsedClicks(this, state.totalUsed - previousUsed, state.available)
        }
        prefs.lastKnownTotal = state.totalReceived
        prefs.lastKnownUsed = state.totalUsed
    }

    companion object {
        private const val MIN_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ClickerService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, ClickerService::class.java))
    }
}
