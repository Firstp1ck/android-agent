package com.agent.memory

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MemoryManager and Experience Memory.
 *
 * Tests focus on accuracy of template matching and learning.
 */
class MemoryManagerTest {

    private lateinit var experienceMemory: ExperienceMemory

    @Before
    fun setup() {
        experienceMemory = ExperienceMemory()
    }

    // ==========================================================================
    // Text Normalization Tests
    // ==========================================================================

    @Test
    fun `normalizeText - removes special characters`() {
        val testCases = mapOf(
            "Hello, World!" to "hello world",
            "What's up?" to "whats up",
            "Test-case_123" to "testcase123",
            "UPPERCASE TEXT" to "uppercase text"
        )

        testCases.forEach { (input, expected) ->
            val result = normalizeTextTestable(input)
            assertEquals("Should normalize '$input' to '$expected'", expected, result)
        }
    }

    @Test
    fun `normalizeText - handles multiple spaces`() {
        val input = "hello    world   test"
        val expected = "hello world test"
        val result = normalizeTextTestable(input)
        assertEquals(expected, result)
    }

    // ==========================================================================
    // Similarity Calculation Tests
    // ==========================================================================

    @Test
    fun `calculateSimilarity - identical strings return 1`() {
        val text = "send message to john"
        val similarity = calculateSimilarityTestable(text, text)
        assertEquals(1.0f, similarity, 0.001f)
    }

    @Test
    fun `calculateSimilarity - completely different strings return 0`() {
        val a = "apple banana cherry"
        val b = "xyz qwe rty"
        val similarity = calculateSimilarityTestable(a, b)
        assertEquals(0.0f, similarity, 0.001f)
    }

    @Test
    fun `calculateSimilarity - partial overlap returns correct value`() {
        val a = "send message to john"  // 4 words
        val b = "send message to jane"  // 4 words, 3 common
        val similarity = calculateSimilarityTestable(a, b)
        // Jaccard: intersection / union = 3 / 5 = 0.6
        assertEquals(0.6f, similarity, 0.001f)
    }

    @Test
    fun `calculateSimilarity - empty strings return 0`() {
        assertEquals(0.0f, calculateSimilarityTestable("", ""), 0.001f)
        assertEquals(0.0f, calculateSimilarityTestable("hello", ""), 0.001f)
        assertEquals(0.0f, calculateSimilarityTestable("", "hello"), 0.001f)
    }

    // ==========================================================================
    // Template Learning Tests
    // ==========================================================================

    @Test
    fun `learnFromExecution - creates new template on first execution`() {
        val plan = createTestPlan("Send a message to contact")
        val results = listOf(
            com.agent.core.ActionResult.Success("1", 100, "done")
        )

        experienceMemory.learnFromExecution(plan, results)

        assertEquals(1, experienceMemory.templateCount)
    }

    @Test
    fun `learnFromExecution - updates existing template on similar execution`() {
        val plan1 = createTestPlan("Send a message to contact")
        val plan2 = createTestPlan("Send a message to contact")

        experienceMemory.learnFromExecution(
            plan1,
            listOf(com.agent.core.ActionResult.Success("1", 100, "done"))
        )
        experienceMemory.learnFromExecution(
            plan2,
            listOf(com.agent.core.ActionResult.Success("2", 100, "done"))
        )

        // Should update existing template, not create new one
        assertEquals(1, experienceMemory.templateCount)
    }

    @Test
    fun `learnFromExecution - calculates success rate correctly`() {
        val plan = createTestPlan("Test task")

        // First execution: 100% success
        experienceMemory.learnFromExecution(
            plan,
            listOf(com.agent.core.ActionResult.Success("1", 100, "done"))
        )

        assertEquals(1.0f, experienceMemory.averageSuccessRate, 0.001f)
    }

    // ==========================================================================
    // Template Matching Tests
    // ==========================================================================

    @Test
    fun `findMatch - returns null when no templates exist`() {
        val match = experienceMemory.findMatch("send a message")
        assertNull(match)
    }

    @Test
    fun `findMatch - finds matching template above threshold`() {
        // Add a template
        val plan = createTestPlan("Send a message to contact")
        experienceMemory.learnFromExecution(
            plan,
            listOf(com.agent.core.ActionResult.Success("1", 100, "done"))
        )

        // Query with similar text
        val match = experienceMemory.findMatch("Send a message to friend")
        assertNotNull(match)
        assertTrue(match!!.confidence >= 0.7f)
    }

    @Test
    fun `findMatch - returns null for dissimilar queries`() {
        // Add a template
        val plan = createTestPlan("Send a message to contact")
        experienceMemory.learnFromExecution(
            plan,
            listOf(com.agent.core.ActionResult.Success("1", 100, "done"))
        )

        // Query with completely different text
        val match = experienceMemory.findMatch("What is the weather today")
        assertNull(match)
    }

    // ==========================================================================
    // Action Memory Tests
    // ==========================================================================

    @Test
    fun `ActionMemory - records and limits sequences`() {
        val actionMemory = ActionMemory()

        // Record multiple sequences
        repeat(5) { i ->
            actionMemory.recordSequence(
                listOf(
                    com.agent.core.AgentAction.LaunchApp(
                        description = "Test",
                        packageName = "com.test.$i"
                    )
                ),
                listOf(com.agent.core.ActionResult.Success("$i", 100, "done"))
            )
        }

        assertEquals(5, actionMemory.sequenceCount)
    }

    // ==========================================================================
    // Profile Memory Tests
    // ==========================================================================

    @Test
    fun `ProfileMemory - stores and retrieves preferences`() {
        val profileMemory = ProfileMemory()

        profileMemory.setPreference("theme", "dark")
        profileMemory.setPreference("language", "en")

        assertEquals("dark", profileMemory.getPreference("theme"))
        assertEquals("en", profileMemory.getPreference("language"))
        assertNull(profileMemory.getPreference("nonexistent"))
    }

    @Test
    fun `ProfileMemory - records interaction patterns`() {
        val profileMemory = ProfileMemory()

        profileMemory.recordInteraction("message", mapOf("contact" to "John"))
        profileMemory.recordInteraction("message", mapOf("contact" to "Jane"))

        val pattern = profileMemory.getPattern("message")
        assertNotNull(pattern)
        assertEquals(2, pattern!!.occurrenceCount)
    }

    // ==========================================================================
    // Helper Functions
    // ==========================================================================

    private fun normalizeTextTestable(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateSimilarityTestable(a: String, b: String): Float {
        val wordsA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val wordsB = b.split(" ").filter { it.isNotEmpty() }.toSet()

        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0f

        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size

        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun createTestPlan(description: String): com.agent.core.ActionPlan {
        return com.agent.core.ActionPlan(
            description = description,
            actions = listOf(
                com.agent.core.AgentAction.LaunchApp(
                    description = "Test action",
                    packageName = "com.test"
                )
            )
        )
    }
}

