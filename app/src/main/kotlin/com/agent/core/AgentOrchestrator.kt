package com.agent.core

import android.content.Context
import com.agent.llm.LlmEngine
import com.agent.memory.MemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis

/**
 * Main orchestrator for the AI Agent.
 *
 * Coordinates the flow from user query to action execution:
 * 1. Receive user query
 * 2. Check memory for matching templates
 * 3. Generate action plan (via LLM or template)
 * 4. Request user consent
 * 5. Execute actions
 * 6. Update memory with results
 *
 * @property context Android context
 * @property memoryManager Memory system for templates and patterns
 * @property config Agent configuration
 */
class AgentOrchestrator(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val config: AgentConfig
) {
    private val llmEngine = LlmEngine(context, config.model)
    private val taskPlanner = TaskPlanner(llmEngine, memoryManager, config)
    private val actionExecutor = ActionExecutor()

    private val _currentState = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val currentState: StateFlow<OrchestratorState> = _currentState.asStateFlow()

    private val _conversationHistory = MutableStateFlow<List<ConversationEntry>>(emptyList())
    val conversationHistory: StateFlow<List<ConversationEntry>> = _conversationHistory.asStateFlow()

    /**
     * Process a user query and return a response.
     *
     * This is the main entry point for user interactions.
     *
     * @param queryText The user's input text
     * @return AgentResponse with the result
     */
    suspend fun processQuery(queryText: String): AgentResponse {
        val query = AgentQuery(text = queryText)
        
        // Add to conversation history
        addToHistory(ConversationEntry.UserMessage(query.id, queryText))

        _currentState.value = OrchestratorState.Processing(query.id, "Understanding your request...")

        var response: AgentResponse
        val latencyMs = measureTimeMillis {
            response = try {
                // Step 1: Check memory for matching template
                val templateMatch = memoryManager.findMatchingTemplate(queryText)

                if (templateMatch != null && templateMatch.confidence >= config.memory.similarityThreshold) {
                    // Use cached template
                    _currentState.value = OrchestratorState.Processing(
                        query.id, 
                        "Found similar task in memory..."
                    )
                    createResponseFromTemplate(query, templateMatch)
                } else {
                    // Generate new plan via LLM
                    generateNewPlan(query)
                }
            } catch (e: Exception) {
                AgentResponse(
                    queryId = query.id,
                    text = "I encountered an error: ${e.message}",
                    status = ResponseStatus.FAILED
                )
            }
        }

        response = response.copy(latencyMs = latencyMs)

        // Add response to history
        addToHistory(ConversationEntry.AgentMessage(response.id, response.text, response.plan))

        // Update state based on response
        _currentState.value = when (response.status) {
            ResponseStatus.AWAITING_CONSENT -> OrchestratorState.AwaitingConsent(
                query.id,
                response.plan!!
            )
            ResponseStatus.COMPLETED -> OrchestratorState.Idle
            ResponseStatus.FAILED -> OrchestratorState.Error(response.text)
            else -> OrchestratorState.Idle
        }

        return response
    }

    /**
     * Handle user consent decision.
     */
    suspend fun handleConsent(consent: ConsentResponse): AgentResponse {
        val state = _currentState.value
        if (state !is OrchestratorState.AwaitingConsent) {
            return AgentResponse(
                queryId = "",
                text = "No action pending consent",
                status = ResponseStatus.FAILED
            )
        }

        return when (consent.decision) {
            ConsentDecision.APPROVED -> executeApprovedPlan(state.plan)
            ConsentDecision.REJECTED -> {
                _currentState.value = OrchestratorState.Idle
                AgentResponse(
                    queryId = state.queryId,
                    text = "Action cancelled as requested.",
                    status = ResponseStatus.CANCELLED
                )
            }
            ConsentDecision.MODIFIED -> {
                consent.modifiedPlan?.let { executeApprovedPlan(it) }
                    ?: AgentResponse(
                        queryId = state.queryId,
                        text = "Modified plan was empty",
                        status = ResponseStatus.FAILED
                    )
            }
        }
    }

    private suspend fun generateNewPlan(query: AgentQuery): AgentResponse {
        _currentState.value = OrchestratorState.Processing(query.id, "Planning actions...")

        return try {
            withTimeout(config.fallbackTimeoutMs) {
                val plan = taskPlanner.createPlan(query)

                if (plan != null) {
                    // Determine if consent is needed
                    val requiresConsent = plan.requiresConsent ||
                            plan.safetyLevel == SafetyLevel.CRITICAL ||
                            config.safety.alwaysPreview

                    AgentResponse(
                        queryId = query.id,
                        text = generatePlanDescription(plan),
                        plan = plan,
                        status = if (requiresConsent) {
                            ResponseStatus.AWAITING_CONSENT
                        } else {
                            ResponseStatus.PENDING
                        },
                        confidence = 0.9f
                    )
                } else {
                    // No actionable plan - just respond
                    val response = taskPlanner.generateTextResponse(query)
                    AgentResponse(
                        queryId = query.id,
                        text = response,
                        status = ResponseStatus.COMPLETED,
                        confidence = 0.8f
                    )
                }
            }
        } catch (e: Exception) {
            // Fallback to API if configured
            if (config.operatingMode == OperatingMode.API_FALLBACK) {
                // TODO: Implement API fallback
            }
            AgentResponse(
                queryId = query.id,
                text = "I couldn't process that request: ${e.message}",
                status = ResponseStatus.FAILED
            )
        }
    }

    private suspend fun createResponseFromTemplate(
        query: AgentQuery,
        templateMatch: TemplateMatch
    ): AgentResponse {
        // Adapt template to current query
        val adaptedPlan = taskPlanner.adaptTemplate(templateMatch.template, query)

        return AgentResponse(
            queryId = query.id,
            text = generatePlanDescription(adaptedPlan),
            plan = adaptedPlan,
            status = ResponseStatus.AWAITING_CONSENT,
            confidence = templateMatch.confidence
        )
    }

    private suspend fun executeApprovedPlan(plan: ActionPlan): AgentResponse {
        _currentState.value = OrchestratorState.Executing(plan.id, 0, plan.actions.size)

        val results = mutableListOf<ActionResult>()
        var success = true

        for ((index, action) in plan.actions.withIndex()) {
            _currentState.value = OrchestratorState.Executing(
                plan.id,
                index + 1,
                plan.actions.size
            )

            val result = actionExecutor.execute(action)
            results.add(result)

            if (result is ActionResult.Failure) {
                success = false
                if (!result.recoverable) {
                    // Attempt rollback if configured
                    if (config.safety.autoRollback && plan.rollbackActions.isNotEmpty()) {
                        executeRollback(plan.rollbackActions)
                    }
                    break
                }
            }
        }

        // Update memory with execution result
        if (success) {
            memoryManager.recordSuccessfulExecution(plan, results)
        }

        _currentState.value = OrchestratorState.Idle

        return AgentResponse(
            queryId = plan.id,
            text = if (success) "✓ Completed successfully!" else "✗ Some actions failed",
            plan = plan,
            status = if (success) ResponseStatus.COMPLETED else ResponseStatus.FAILED
        )
    }

    private suspend fun executeRollback(rollbackActions: List<AgentAction>) {
        for (action in rollbackActions) {
            actionExecutor.execute(action)
        }
    }

    private fun generatePlanDescription(plan: ActionPlan): String {
        return buildString {
            appendLine("I'll help you with that. Here's what I'll do:")
            appendLine()
            plan.actions.forEachIndexed { index, action ->
                appendLine("${index + 1}. ${action.description}")
            }
            if (plan.safetyLevel == SafetyLevel.CRITICAL) {
                appendLine()
                appendLine("⚠️ This includes sensitive actions that cannot be undone.")
            }
        }
    }

    private fun addToHistory(entry: ConversationEntry) {
        _conversationHistory.value = _conversationHistory.value + entry
    }

    fun clearHistory() {
        _conversationHistory.value = emptyList()
    }
}

/**
 * State of the orchestrator.
 */
sealed class OrchestratorState {
    data object Idle : OrchestratorState()

    data class Processing(
        val queryId: String,
        val statusMessage: String
    ) : OrchestratorState()

    data class AwaitingConsent(
        val queryId: String,
        val plan: ActionPlan
    ) : OrchestratorState()

    data class Executing(
        val planId: String,
        val currentStep: Int,
        val totalSteps: Int
    ) : OrchestratorState()

    data class Error(val message: String) : OrchestratorState()
}

/**
 * Entry in the conversation history.
 */
sealed class ConversationEntry {
    abstract val id: String
    abstract val timestamp: Long

    data class UserMessage(
        override val id: String,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationEntry()

    data class AgentMessage(
        override val id: String,
        val text: String,
        val plan: ActionPlan?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : ConversationEntry()
}

/**
 * Result of template matching.
 */
data class TemplateMatch(
    val template: TaskTemplate,
    val confidence: Float,
    val extractedParameters: Map<String, String>
)

/**
 * A reusable task template.
 */
data class TaskTemplate(
    val id: String,
    val pattern: String,
    val actions: List<AgentAction>,
    val parameterSlots: List<ParameterSlot>,
    val successRate: Float,
    val useCount: Int
)

data class ParameterSlot(
    val name: String,
    val type: ParameterType,
    val required: Boolean = true
)

enum class ParameterType {
    TEXT, TIME, DATE, CONTACT, APP, NUMBER
}

