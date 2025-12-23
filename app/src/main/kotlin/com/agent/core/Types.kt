package com.agent.core

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Core type definitions for the AI Agent.
 *
 * These types define the data structures used throughout the agent
 * for task processing, action execution, and memory.
 */

// =============================================================================
// Query and Response Types
// =============================================================================

/**
 * User query with context.
 *
 * @property id Unique identifier for this query
 * @property text The user's input text
 * @property timestamp When the query was received (epoch millis)
 * @property context Additional context for the query
 */
@Serializable
data class AgentQuery(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val context: QueryContext = QueryContext()
)

/**
 * Contextual information for a query.
 */
@Serializable
data class QueryContext(
    val currentApp: String? = null,
    val timeOfDay: TimeOfDay = TimeOfDay.fromCurrentTime(),
    val recentApps: List<String> = emptyList()
)

@Serializable
enum class TimeOfDay {
    MORNING,    // 5:00 - 11:59
    AFTERNOON,  // 12:00 - 16:59
    EVENING,    // 17:00 - 20:59
    NIGHT;      // 21:00 - 4:59

    companion object {
        fun fromCurrentTime(): TimeOfDay {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..11 -> MORNING
                in 12..16 -> AFTERNOON
                in 17..20 -> EVENING
                else -> NIGHT
            }
        }
    }
}

/**
 * Agent response with planned actions.
 *
 * @property id Unique identifier
 * @property queryId Reference to the original query
 * @property text Response text for the user
 * @property plan The action plan (if any)
 * @property status Current status of the response
 * @property confidence Confidence score (0.0-1.0)
 * @property latencyMs Processing latency in milliseconds
 */
@Serializable
data class AgentResponse(
    val id: String = UUID.randomUUID().toString(),
    val queryId: String,
    val text: String,
    val plan: ActionPlan? = null,
    val status: ResponseStatus = ResponseStatus.PENDING,
    val confidence: Float = 0.0f,
    val latencyMs: Long = 0L
)

@Serializable
enum class ResponseStatus {
    PENDING,            // Still processing
    AWAITING_CONSENT,   // Waiting for user approval
    EXECUTING,          // Actions being executed
    COMPLETED,          // Successfully completed
    FAILED,             // Execution failed
    CANCELLED           // User cancelled
}

// =============================================================================
// Action Types
// =============================================================================

/**
 * A plan of actions to execute.
 *
 * @property id Unique identifier
 * @property description Human-readable description
 * @property actions List of actions to execute in order
 * @property safetyLevel Overall safety level of the plan
 * @property requiresConsent Whether user consent is required
 * @property rollbackActions Actions to undo this plan (if possible)
 */
@Serializable
data class ActionPlan(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val actions: List<AgentAction>,
    val safetyLevel: SafetyLevel = SafetyLevel.NORMAL,
    val requiresConsent: Boolean = true,
    val rollbackActions: List<AgentAction> = emptyList()
)

/**
 * Safety classification for actions.
 */
@Serializable
enum class SafetyLevel {
    /** Read-only operations, no side effects */
    SAFE,

    /** Has side effects but reversible */
    NORMAL,

    /** Irreversible or sensitive (financial, delete, send) */
    CRITICAL
}

/**
 * A single action the agent can perform.
 */
@Serializable
sealed class AgentAction {
    abstract val id: String
    abstract val description: String
    abstract val safetyLevel: SafetyLevel

    /**
     * Launch an app.
     */
    @Serializable
    data class LaunchApp(
        override val id: String = UUID.randomUUID().toString(),
        override val description: String,
        override val safetyLevel: SafetyLevel = SafetyLevel.SAFE,
        val packageName: String,
        val activityName: String? = null
    ) : AgentAction()

    /**
     * Click on a UI element.
     */
    @Serializable
    data class Click(
        override val id: String = UUID.randomUUID().toString(),
        override val description: String,
        override val safetyLevel: SafetyLevel = SafetyLevel.NORMAL,
        val selector: UiSelector,
        val timeoutMs: Long = 5000L
    ) : AgentAction()

    /**
     * Input text into a field.
     */
    @Serializable
    data class InputText(
        override val id: String = UUID.randomUUID().toString(),
        override val description: String,
        override val safetyLevel: SafetyLevel = SafetyLevel.NORMAL,
        val selector: UiSelector,
        val text: String
    ) : AgentAction()

    /**
     * Scroll in a direction.
     */
    @Serializable
    data class Scroll(
        override val id: String = UUID.randomUUID().toString(),
        override val description: String,
        override val safetyLevel: SafetyLevel = SafetyLevel.SAFE,
        val direction: ScrollDirection,
        val amount: Float = 0.5f  // Fraction of screen
    ) : AgentAction()

    /**
     * Wait for a condition.
     */
    @Serializable
    data class Wait(
        override val id: String = UUID.randomUUID().toString(),
        override val description: String,
        override val safetyLevel: SafetyLevel = SafetyLevel.SAFE,
        val condition: WaitCondition,
        val timeoutMs: Long = 10000L
    ) : AgentAction()

    /**
     * Press the back button.
     */
    @Serializable
    data class Back(
        override val id: String = UUID.randomUUID().toString(),
        override val description: String = "Press back button",
        override val safetyLevel: SafetyLevel = SafetyLevel.SAFE
    ) : AgentAction()

    /**
     * Go to home screen.
     */
    @Serializable
    data class Home(
        override val id: String = UUID.randomUUID().toString(),
        override val description: String = "Go to home screen",
        override val safetyLevel: SafetyLevel = SafetyLevel.SAFE
    ) : AgentAction()
}

/**
 * Selector for finding UI elements.
 */
@Serializable
data class UiSelector(
    val text: String? = null,
    val textContains: String? = null,
    val contentDescription: String? = null,
    val contentDescriptionContains: String? = null,
    val resourceId: String? = null,
    val resourceIdContains: String? = null,
    val className: String? = null,
    val index: Int? = null
) {
    fun toAccessibilityQuery(): String {
        return buildString {
            text?.let { append("text=$it ") }
            textContains?.let { append("textContains=$it ") }
            contentDescription?.let { append("desc=$it ") }
            contentDescriptionContains?.let { append("descContains=$it ") }
            resourceId?.let { append("id=$it ") }
            resourceIdContains?.let { append("idContains=$it ") }
            className?.let { append("class=$it ") }
            index?.let { append("index=$it") }
        }.trim()
    }
}

@Serializable
enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

@Serializable
sealed class WaitCondition {
    @Serializable
    data class ElementVisible(val selector: UiSelector) : WaitCondition()

    @Serializable
    data class ElementGone(val selector: UiSelector) : WaitCondition()

    @Serializable
    data class Delay(val durationMs: Long) : WaitCondition()
}

// =============================================================================
// Execution Types
// =============================================================================

/**
 * Result of executing an action.
 */
@Serializable
sealed class ActionResult {
    abstract val actionId: String
    abstract val durationMs: Long

    @Serializable
    data class Success(
        override val actionId: String,
        override val durationMs: Long,
        val message: String? = null
    ) : ActionResult()

    @Serializable
    data class Failure(
        override val actionId: String,
        override val durationMs: Long,
        val error: String,
        val recoverable: Boolean = false
    ) : ActionResult()
}

/**
 * Consent decision from the user.
 */
@Serializable
enum class ConsentDecision {
    APPROVED,
    REJECTED,
    MODIFIED
}

/**
 * User's consent response.
 */
@Serializable
data class ConsentResponse(
    val planId: String,
    val decision: ConsentDecision,
    val modifiedPlan: ActionPlan? = null,
    val reason: String? = null
)

