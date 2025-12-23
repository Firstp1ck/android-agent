package com.agent.memory

import android.content.Context
import com.agent.core.ActionPlan
import com.agent.core.ActionResult
import com.agent.core.AgentAction
import com.agent.core.ParameterSlot
import com.agent.core.TaskTemplate
import com.agent.core.TemplateMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Memory manager implementing a simplified MobiMem architecture.
 *
 * Three layers:
 * 1. Profile Memory - User preferences and patterns
 * 2. Experience Memory - Task templates for reuse
 * 3. Action Memory - Recorded action sequences
 *
 * @property context Android context for database access
 */
class MemoryManager(private val context: Context) {

    // In-memory caches (would be backed by Room in production)
    private val profileMemory = ProfileMemory()
    private val experienceMemory = ExperienceMemory()
    private val actionMemory = ActionMemory()

    /**
     * Find a matching template for the given query.
     *
     * @param queryText The user's query text
     * @return TemplateMatch if a suitable template is found
     */
    suspend fun findMatchingTemplate(queryText: String): TemplateMatch? {
        return withContext(Dispatchers.Default) {
            experienceMemory.findMatch(queryText)
        }
    }

    /**
     * Record a successful execution for learning.
     */
    suspend fun recordSuccessfulExecution(
        plan: ActionPlan,
        results: List<ActionResult>
    ) {
        withContext(Dispatchers.Default) {
            // Create or update template
            experienceMemory.learnFromExecution(plan, results)

            // Record action sequence
            actionMemory.recordSequence(plan.actions, results)
        }
    }

    /**
     * Get user preferences from profile memory.
     */
    fun getUserPreference(key: String): String? {
        return profileMemory.getPreference(key)
    }

    /**
     * Update user preference.
     */
    fun setUserPreference(key: String, value: String) {
        profileMemory.setPreference(key, value)
    }

    /**
     * Get execution statistics.
     */
    fun getStats(): MemoryStats {
        return MemoryStats(
            templateCount = experienceMemory.templateCount,
            actionSequenceCount = actionMemory.sequenceCount,
            totalExecutions = experienceMemory.totalExecutions,
            averageSuccessRate = experienceMemory.averageSuccessRate
        )
    }
}

/**
 * Profile Memory - User preferences and patterns.
 */
class ProfileMemory {
    private val preferences = mutableMapOf<String, String>()
    private val patterns = mutableMapOf<String, UserPattern>()

    fun getPreference(key: String): String? = preferences[key]

    fun setPreference(key: String, value: String) {
        preferences[key] = value
    }

    fun recordInteraction(type: String, details: Map<String, Any>) {
        val pattern = patterns.getOrPut(type) { UserPattern(type) }
        pattern.recordOccurrence(details)
    }

    fun getPattern(type: String): UserPattern? = patterns[type]
}

/**
 * User behavior pattern.
 */
data class UserPattern(
    val type: String,
    var occurrenceCount: Int = 0,
    val timeDistribution: MutableMap<Int, Int> = mutableMapOf(), // Hour -> count
    val parameters: MutableMap<String, MutableList<String>> = mutableMapOf()
) {
    fun recordOccurrence(details: Map<String, Any>) {
        occurrenceCount++
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        timeDistribution[hour] = (timeDistribution[hour] ?: 0) + 1

        details.forEach { (key, value) ->
            parameters.getOrPut(key) { mutableListOf() }.add(value.toString())
        }
    }
}

/**
 * Experience Memory - Task templates.
 */
class ExperienceMemory {
    private val templates = mutableListOf<StoredTemplate>()

    val templateCount: Int get() = templates.size
    val totalExecutions: Int get() = templates.sumOf { it.useCount }
    val averageSuccessRate: Float
        get() = if (templates.isEmpty()) 0f
                else templates.map { it.successRate }.average().toFloat()

    /**
     * Find a matching template using simple text similarity.
     */
    fun findMatch(queryText: String): TemplateMatch? {
        val normalizedQuery = normalizeText(queryText)

        var bestMatch: StoredTemplate? = null
        var bestScore = 0f

        for (template in templates) {
            val score = calculateSimilarity(normalizedQuery, template.normalizedPattern)
            if (score > bestScore && score >= 0.7f) {
                bestScore = score
                bestMatch = template
            }
        }

        return bestMatch?.let {
            TemplateMatch(
                template = it.toTaskTemplate(),
                confidence = bestScore,
                extractedParameters = extractParams(queryText, it.parameterSlots)
            )
        }
    }

    /**
     * Learn from a successful execution.
     */
    fun learnFromExecution(plan: ActionPlan, results: List<ActionResult>) {
        val successCount = results.count { it is ActionResult.Success }
        val successRate = successCount.toFloat() / results.size

        // Try to find existing template
        val existing = templates.find {
            calculateSimilarity(it.normalizedPattern, normalizeText(plan.description)) > 0.9f
        }

        if (existing != null) {
            // Update existing template
            existing.useCount++
            existing.successRate = (existing.successRate * (existing.useCount - 1) + successRate) / existing.useCount
        } else {
            // Create new template
            templates.add(
                StoredTemplate(
                    id = UUID.randomUUID().toString(),
                    pattern = plan.description,
                    normalizedPattern = normalizeText(plan.description),
                    actions = plan.actions,
                    parameterSlots = detectParameterSlots(plan),
                    successRate = successRate,
                    useCount = 1
                )
            )
        }
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateSimilarity(a: String, b: String): Float {
        val wordsA = a.split(" ").toSet()
        val wordsB = b.split(" ").toSet()

        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size

        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun extractParams(query: String, slots: List<ParameterSlot>): Map<String, String> {
        // Simplified parameter extraction
        return emptyMap()
    }

    private fun detectParameterSlots(plan: ActionPlan): List<ParameterSlot> {
        // Simplified slot detection
        return emptyList()
    }
}

/**
 * Stored template in memory.
 */
data class StoredTemplate(
    val id: String,
    val pattern: String,
    val normalizedPattern: String,
    val actions: List<AgentAction>,
    val parameterSlots: List<ParameterSlot>,
    var successRate: Float,
    var useCount: Int
) {
    fun toTaskTemplate(): TaskTemplate {
        return TaskTemplate(
            id = id,
            pattern = pattern,
            actions = actions,
            parameterSlots = parameterSlots,
            successRate = successRate,
            useCount = useCount
        )
    }
}

/**
 * Action Memory - Recorded action sequences.
 */
class ActionMemory {
    private val sequences = mutableListOf<ActionSequence>()

    val sequenceCount: Int get() = sequences.size

    fun recordSequence(actions: List<AgentAction>, results: List<ActionResult>) {
        sequences.add(
            ActionSequence(
                id = UUID.randomUUID().toString(),
                actions = actions,
                results = results,
                timestamp = System.currentTimeMillis()
            )
        )

        // Keep only recent sequences
        if (sequences.size > 1000) {
            sequences.removeAt(0)
        }
    }

    fun findSimilarSequence(actions: List<AgentAction>): ActionSequence? {
        // Find a sequence with similar action types
        return sequences.find { seq ->
            seq.actions.size == actions.size &&
            seq.actions.zip(actions).all { (a, b) -> a::class == b::class }
        }
    }
}

data class ActionSequence(
    val id: String,
    val actions: List<AgentAction>,
    val results: List<ActionResult>,
    val timestamp: Long
)

data class MemoryStats(
    val templateCount: Int,
    val actionSequenceCount: Int,
    val totalExecutions: Int,
    val averageSuccessRate: Float
)

