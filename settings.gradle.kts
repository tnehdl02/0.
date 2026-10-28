package com.example.binancephonescanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = context.getSharedPreferences(ScannerService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("running", false)) return

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_POWER_CONNECTED) {
            val serviceIntent = Intent(context, ScannerService::class.java).apply {
                this.action = ScannerService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (_: Exception) {
            }
        }
    }
}
