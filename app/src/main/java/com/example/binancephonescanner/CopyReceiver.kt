package com.example.binancephonescanner

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Copies a coin symbol to the clipboard when the "종목명 복사" notification button is tapped. */
class CopyReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val symbol = intent?.getStringExtra(EXTRA_SYMBOL) ?: return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("symbol", symbol))
            Toast.makeText(context, "$symbol 복사됨", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "복사하지 못했습니다", Toast.LENGTH_SHORT).show()
        }
    }
}
