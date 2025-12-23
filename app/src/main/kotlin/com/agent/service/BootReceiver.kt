package com.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives boot completed broadcast to restart agent service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Optionally start the agent service on boot
            // context.startForegroundService(Intent(context, AgentService::class.java))
        }
    }
}

