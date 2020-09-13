package org.rocstreaming.rocdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationListener : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        when (action) {
            DEAF, MUTE, RECV, SEND -> {
                Intent(context, RocStreamService::class.java).apply {
                    this.action = action
                    context.startForegroundService(this)
                }
            }
            STOP -> Intent(context,RocStreamService::class.java).apply {
                context.stopService(this)
            }
        }
    }
}