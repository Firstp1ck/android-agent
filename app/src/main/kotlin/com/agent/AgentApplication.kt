package com.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.agent.core.AgentConfig
import com.agent.core.AgentOrchestrator
import com.agent.memory.MemoryManager

/**
 * Application class for the AI Agent.
 *
 * Initializes core components on application startup:
 * - Memory system
 * - Agent orchestrator
 * - Notification channels
 */
class AgentApplication : Application() {

    lateinit var agentOrchestrator: AgentOrchestrator
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var config: AgentConfig
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize configuration
        config = AgentConfig.load(this)

        // Initialize memory system
        memoryManager = MemoryManager(this)

        // Initialize agent orchestrator
        agentOrchestrator = AgentOrchestrator(
            context = this,
            memoryManager = memoryManager,
            config = config
        )

        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Agent status channel
            val statusChannel = NotificationChannel(
                CHANNEL_AGENT_STATUS,
                "Agent Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows agent activity status"
            }

            // Consent request channel
            val consentChannel = NotificationChannel(
                CHANNEL_CONSENT_REQUEST,
                "Action Confirmation",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Requests your approval for agent actions"
            }

            notificationManager.createNotificationChannels(
                listOf(statusChannel, consentChannel)
            )
        }
    }

    companion object {
        const val CHANNEL_AGENT_STATUS = "agent_status"
        const val CHANNEL_CONSENT_REQUEST = "consent_request"

        lateinit var instance: AgentApplication
            private set
    }
}

