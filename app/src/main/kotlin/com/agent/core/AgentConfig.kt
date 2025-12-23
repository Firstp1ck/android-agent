package com.agent.core

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * Configuration for the AI Agent.
 *
 * Controls agent behavior, model settings, and safety parameters.
 */
@Serializable
data class AgentConfig(
    /** Operating mode: LOCAL_ONLY, API_FALLBACK, or LOCAL_ENHANCED */
    val operatingMode: OperatingMode = OperatingMode.LOCAL_ONLY,

    /** Timeout before API fallback (milliseconds) */
    val fallbackTimeoutMs: Long = 30000L,

    /** Maximum retry attempts for failed operations */
    val maxRetries: Int = 3,

    /** Model configuration */
    val model: ModelConfig = ModelConfig(),

    /** Memory configuration */
    val memory: MemoryConfig = MemoryConfig(),

    /** Safety configuration */
    val safety: SafetyConfig = SafetyConfig()
) {
    companion object {
        fun load(context: Context): AgentConfig {
            // TODO: Load from SharedPreferences or assets
            return AgentConfig()
        }
    }
}

@Serializable
enum class OperatingMode {
    /** 100% on-device processing */
    LOCAL_ONLY,

    /** Local with API fallback after timeout */
    API_FALLBACK,

    /** Local processing with API enrichment */
    LOCAL_ENHANCED
}

@Serializable
data class ModelConfig(
    /** Path to local model file (in assets or external storage) */
    val modelPath: String = "models/llama-3.2-3b-q4_k_m.gguf",

    /** Context window length */
    val contextLength: Int = 4096,

    /** Number of threads for inference */
    val threads: Int = 4,

    /** Temperature for sampling */
    val temperature: Float = 0.7f,

    /** Maximum tokens to generate */
    val maxTokens: Int = 512
)

@Serializable
data class MemoryConfig(
    /** Maximum templates in experience memory */
    val maxTemplates: Int = 5000,

    /** Similarity threshold for template matching (0.0-1.0) */
    val similarityThreshold: Float = 0.85f,

    /** Maximum action sequences in action memory */
    val maxActionSequences: Int = 2000
)

@Serializable
data class SafetyConfig(
    /** Actions that always require user confirmation */
    val criticalActions: List<String> = listOf(
        "send_message",
        "delete",
        "payment",
        "uninstall",
        "settings_change",
        "email",
        "call"
    ),

    /** Show preview before any action */
    val alwaysPreview: Boolean = true,

    /** Enable automatic rollback on failure */
    val autoRollback: Boolean = true,

    /** Maximum actions in a single sequence */
    val maxActionsPerSequence: Int = 20
)

