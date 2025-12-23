package com.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agent.AgentApplication
import com.agent.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the agent screen.
 */
class AgentViewModel : ViewModel() {

    private val orchestrator = AgentApplication.instance.agentOrchestrator

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    val conversationHistory = orchestrator.conversationHistory

    private var pendingPlan: ActionPlan? = null

    /**
     * Send a query to the agent.
     */
    fun sendQuery(queryText: String) {
        viewModelScope.launch {
            _uiState.value = AgentUiState.Processing("Understanding your request...")

            val response = orchestrator.processQuery(queryText)

            when (response.status) {
                ResponseStatus.AWAITING_CONSENT -> {
                    pendingPlan = response.plan
                    _uiState.value = AgentUiState.AwaitingConsent(response.plan!!)
                }
                ResponseStatus.COMPLETED -> {
                    _uiState.value = AgentUiState.Idle
                }
                ResponseStatus.FAILED -> {
                    _uiState.value = AgentUiState.Error(response.text)
                }
                else -> {
                    _uiState.value = AgentUiState.Idle
                }
            }
        }
    }

    /**
     * Approve a pending action plan.
     */
    fun approvePlan(plan: ActionPlan) {
        android.util.Log.d("AgentViewModel", "approvePlan called for: ${plan.description}")
        viewModelScope.launch {
            _uiState.value = AgentUiState.Processing("Executing actions...")

            val consent = ConsentResponse(
                planId = plan.id,
                decision = ConsentDecision.APPROVED
            )

            try {
                val response = orchestrator.handleConsent(consent)
                pendingPlan = null
                android.util.Log.d("AgentViewModel", "Consent handled, status: ${response.status}")

                _uiState.value = when (response.status) {
                    ResponseStatus.COMPLETED -> AgentUiState.Idle
                    ResponseStatus.FAILED -> AgentUiState.Error(response.text)
                    else -> AgentUiState.Idle
                }
            } catch (e: Exception) {
                android.util.Log.e("AgentViewModel", "Error in approvePlan", e)
                _uiState.value = AgentUiState.Error("Error: ${e.message}")
            }
        }
    }

    /**
     * Reject a pending action plan.
     */
    fun rejectPlan(plan: ActionPlan) {
        android.util.Log.d("AgentViewModel", "rejectPlan called for: ${plan.description}")
        viewModelScope.launch {
            val consent = ConsentResponse(
                planId = plan.id,
                decision = ConsentDecision.REJECTED,
                reason = "User rejected"
            )

            orchestrator.handleConsent(consent)
            pendingPlan = null
            _uiState.value = AgentUiState.Idle
        }
    }

    /**
     * Clear conversation history.
     */
    fun clearHistory() {
        orchestrator.clearHistory()
    }
}

/**
 * UI state for the agent screen.
 */
sealed class AgentUiState {
    data object Idle : AgentUiState()
    data class Processing(val message: String) : AgentUiState()
    data class AwaitingConsent(val plan: ActionPlan) : AgentUiState()
    data class Executing(val progress: Float) : AgentUiState()
    data class Error(val message: String) : AgentUiState()
}

