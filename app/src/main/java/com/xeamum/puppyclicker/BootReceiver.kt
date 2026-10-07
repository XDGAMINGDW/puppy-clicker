package com.xeamum.puppyclicker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the receiver service after a reboot or after the app updates itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (prefs.isConfigured && prefs.role == Role.RECEIVER) ClickerService.start(context)
    }
}
