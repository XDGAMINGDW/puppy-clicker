package com.xeamum.puppyclicker

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ClickState(val available: Int, val totalReceived: Int, val totalUsed: Int)

data class PartnerPresence(
    val online: Boolean,
    val screenOn: Boolean?,
    val lastSeen: Long,
    val lastScreenOn: Long,
    val ageAtReceipt: Long,
    val receivedAt: Long,
)

/** Live state shared between the background service and the UI. */
object ClickRepository {
    val state = MutableStateFlow<ClickState?>(null)
    val connected = MutableStateFlow(false)
    val lastServerUpdate = MutableStateFlow<Long?>(null)
    val partnerPresence = MutableStateFlow<PartnerPresence?>(null)

    fun recordServerUpdate() {
        lastServerUpdate.value = SystemClock.elapsedRealtime()
    }

    /** Clicks done on this phone that the server hasn't confirmed yet. */
    val pending = MutableStateFlow(0)
}

/** [code] is the HTTP status, or 0 if the response was unreadable. */
class ApiException(message: String, val code: Int) : IOException(message) {
    val isClientError get() = code in 400..499
}

class ApiClient(baseUrl: String, private val token: String) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun fetchRole(): Role = withContext(Dispatchers.IO) {
        Role.fromServer(execute(request("/api/state").get()).getString("role"))
    }

    suspend fun getState(): ClickState = withContext(Dispatchers.IO) {
        parseState(execute(request("/api/state").get()))
    }

    suspend fun sync(total: Int): ClickState = post("/api/sync", JSONObject().put("total", total))

    fun openSocket(listener: WebSocketListener): WebSocket = http.newWebSocket(request("/ws").build(), listener)

    private suspend fun post(path: String, body: JSONObject): ClickState = withContext(Dispatchers.IO) {
        parseState(execute(request(path).post(body.toString().toRequestBody(JSON_TYPE))))
    }

    private fun request(path: String) =
        Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token")

    private fun execute(builder: Request.Builder): JSONObject =
        http.newCall(builder.build()).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            if (!response.isSuccessful) {
                throw ApiException(
                    json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Server error ${response.code}",
                    response.code,
                )
            }
            val result = json ?: throw ApiException("Invalid server response", 0)
            ClickRepository.recordServerUpdate()
            result.optString("role").takeIf { it == "sender" || it == "receiver" }?.let {
                updatePartnerPresence(result, Role.fromServer(it))
            }
            result
        }

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        private val JSON_TYPE = "application/json".toMediaType()

        fun updatePartnerPresence(json: JSONObject, role: Role) {
            val partnerRole = if (role == Role.SENDER) "receiver" else "sender"
            val presence = json.optJSONObject("presence")?.optJSONObject(partnerRole)
            if (presence == null) {
                ClickRepository.partnerPresence.value = null
                return
            }
            val lastSeen = presence.optLong("lastSeen")
            ClickRepository.partnerPresence.value = PartnerPresence(
                online = presence.optBoolean("online"),
                screenOn = if (presence.isNull("screenOn")) null else presence.optBoolean("screenOn"),
                lastSeen = lastSeen,
                lastScreenOn = presence.optLong("lastScreenOn"),
                ageAtReceipt = (json.optLong("serverTime", lastSeen) - lastSeen).coerceAtLeast(0L),
                receivedAt = SystemClock.elapsedRealtime(),
            )
        }

        fun parseState(json: JSONObject): ClickState {
            val available = json.getInt("available")
            val totalReceived = json.getInt("totalReceived")
            return ClickState(available, totalReceived, json.optInt("totalUsed", totalReceived - available))
        }
    }
}
