package com.xeamum.puppyclicker

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.time.LocalDate

enum class Role {
    SENDER, RECEIVER;

    companion object {
        fun fromServer(value: String) = if (value == "sender") SENDER else RECEIVER
    }
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("puppy_clicker", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "").orEmpty()
        set(value) = sp.edit { putString("server_url", value) }

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(value) = sp.edit { putString("token", value) }

    var role: Role?
        get() = sp.getString("role", null)?.let(Role::valueOf)
        set(value) = sp.edit { putString("role", value?.name) }

    var relationshipStart: LocalDate?
        get() = sp.getString("relationship_start", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(value) = sp.edit { putString("relationship_start", value?.toString()) }

    var shareScreenStatus: Boolean
        get() = sp.getBoolean("share_screen_status", true)
        set(value) = sp.edit { putBoolean("share_screen_status", value) }

    /** Last seen `totalReceived`, used to detect clicks that arrived while offline. -1 = unknown. */
    var lastKnownTotal: Int
        get() = sp.getInt("last_total", -1)
        set(value) = sp.edit { putInt("last_total", value) }

    var lastKnownUsed: Int
        get() = sp.getInt("last_used", -1)
        set(value) = sp.edit { putInt("last_used", value) }

    /** This phone's copy of the totals; survives logout so unsent clicks aren't lost when the server address changes. */
    var localReceived: Int
        get() = sp.getInt("local_received", 0)
        set(value) = sp.edit { putInt("local_received", value) }

    var localUsed: Int
        get() = sp.getInt("local_used", 0)
        set(value) = sp.edit { putInt("local_used", value) }

    /** Keeps the local totals only if the same secret code is used again. */
    fun bindCounters(token: String) {
        val owner = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        if (sp.getString("counter_owner", null) == owner) return
        sp.edit {
            putString("counter_owner", owner)
            putInt("local_received", 0)
            putInt("local_used", 0)
        }
    }

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty() && token.isNotEmpty() && role != null

    fun api() = ApiClient(serverUrl, token)

    fun logout() = sp.edit {
        remove("token")
        remove("role")
        remove("last_total")
        remove("last_used")
        remove("share_screen_status")
    }
}
