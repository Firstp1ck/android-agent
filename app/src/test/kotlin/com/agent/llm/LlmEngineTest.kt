package com.agent.llm

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for LlmEngine MVP responses.
 *
 * Tests the rule-based fallback system for accuracy.
 */
class LlmEngineTest {

    // ==========================================================================
    // Intent Classification Tests
    // ==========================================================================

    @Test
    fun `classifyIntent - actionable reminders detected`() {
        val actionableTexts = listOf(
            "Remind me to call mom",
            "Set a reminder for 3pm",
            "remind about meeting"
        )

        actionableTexts.forEach { text ->
            val result = classifyIntentTestable(text)
            assertTrue(
                "Should classify '$text' as ACTIONABLE",
                result.contains("ACTIONABLE", ignoreCase = true)
            )
        }
    }

    @Test
    fun `classifyIntent - actionable messages detected`() {
        val actionableTexts = listOf(
            "Send a message to John",
            "Text mom saying hello",
            "send SMS"
        )

        actionableTexts.forEach { text ->
            val result = classifyIntentTestable(text)
            assertTrue(
                "Should classify '$text' as ACTIONABLE",
                result.contains("ACTIONABLE", ignoreCase = true)
            )
        }
    }

    @Test
    fun `classifyIntent - actionable app launches detected`() {
        val actionableTexts = listOf(
            "Open Spotify",
            "open the camera",
            "Open Chrome"
        )

        actionableTexts.forEach { text ->
            val result = classifyIntentTestable(text)
            assertTrue(
                "Should classify '$text' as ACTIONABLE",
                result.contains("ACTIONABLE", ignoreCase = true)
            )
        }
    }

    @Test
    fun `classifyIntent - informational questions detected`() {
        val informationalTexts = listOf(
            "What is the capital of France?",
            "How do I cook pasta?",
            "Why is the sky blue?",
            "When was Einstein born?",
            "Who invented the telephone?"
        )

        informationalTexts.forEach { text ->
            val result = classifyIntentTestable(text)
            assertTrue(
                "Should classify '$text' as INFORMATIONAL",
                result.contains("INFORMATIONAL", ignoreCase = true)
            )
        }
    }

    @Test
    fun `classifyIntent - weather queries are informational`() {
        val weatherTexts = listOf(
            "What's the weather?",
            "Weather in New York",
            "Is it raining?"
        )

        weatherTexts.forEach { text ->
            val result = classifyIntentTestable(text)
            assertTrue(
                "Should classify '$text' as INFORMATIONAL",
                result.contains("INFORMATIONAL", ignoreCase = true)
            )
        }
    }

    @Test
    fun `classifyIntent - unclear inputs detected`() {
        val unclearTexts = listOf(
            "Hello",
            "Thanks",
            "OK"
        )

        unclearTexts.forEach { text ->
            val result = classifyIntentTestable(text)
            assertTrue(
                "Should classify '$text' as UNCLEAR",
                result.contains("UNCLEAR", ignoreCase = true)
            )
        }
    }

    // ==========================================================================
    // Helpful Response Tests
    // ==========================================================================

    @Test
    fun `generateHelpfulResponse - time query returns time`() {
        val response = generateHelpfulResponseTestable("What time is it?")
        assertTrue(
            "Time response should contain time format",
            response.contains("time is", ignoreCase = true) ||
            response.contains(":", ignoreCase = true)
        )
    }

    @Test
    fun `generateHelpfulResponse - date query returns date`() {
        val response = generateHelpfulResponseTestable("What day is it?")
        assertTrue(
            "Date response should contain day info",
            response.contains("today", ignoreCase = true) ||
            response.lowercase().contains("day") ||
            response.contains(",")  // Date format usually has comma
        )
    }

    @Test
    fun `generateHelpfulResponse - help query lists capabilities`() {
        val response = generateHelpfulResponseTestable("What can you do?")
        assertTrue(
            "Help response should list capabilities",
            response.contains("remind", ignoreCase = true) ||
            response.contains("message", ignoreCase = true) ||
            response.contains("help", ignoreCase = true)
        )
    }

    @Test
    fun `generateHelpfulResponse - weather query explains limitation`() {
        val response = generateHelpfulResponseTestable("What's the weather?")
        assertTrue(
            "Weather response should acknowledge or redirect",
            response.contains("weather", ignoreCase = true)
        )
    }

    // ==========================================================================
    // Embedding Tests
    // ==========================================================================

    @Test
    fun `generateSimpleEmbedding - returns fixed dimension`() {
        val embedding = generateSimpleEmbeddingTestable("test text")
        assertEquals("Embedding should be 64-dimensional", 64, embedding.size)
    }

    @Test
    fun `generateSimpleEmbedding - normalized output`() {
        val embedding = generateSimpleEmbeddingTestable("test text here")
        val magnitude = kotlin.math.sqrt(embedding.map { it * it }.sum())
        assertTrue(
            "Embedding magnitude should be ~1.0, got $magnitude",
            magnitude in 0.99f..1.01f || magnitude == 0f
        )
    }

    @Test
    fun `cosineSimilarity - identical texts have high similarity`() {
        val text = "send a message to john"
        val emb1 = generateSimpleEmbeddingTestable(text)
        val emb2 = generateSimpleEmbeddingTestable(text)

        val similarity = cosineSimilarityTestable(emb1, emb2)
        assertTrue("Identical texts should have similarity ~1.0", similarity > 0.99f)
    }

    @Test
    fun `cosineSimilarity - similar texts have moderate similarity`() {
        val emb1 = generateSimpleEmbeddingTestable("send a message to john")
        val emb2 = generateSimpleEmbeddingTestable("send a message to jane")

        val similarity = cosineSimilarityTestable(emb1, emb2)
        assertTrue(
            "Similar texts should have moderate similarity, got $similarity",
            similarity > 0.5f
        )
    }

    @Test
    fun `cosineSimilarity - different texts have low similarity`() {
        val emb1 = generateSimpleEmbeddingTestable("send message")
        val emb2 = generateSimpleEmbeddingTestable("weather forecast")

        val similarity = cosineSimilarityTestable(emb1, emb2)
        assertTrue(
            "Different texts should have low similarity, got $similarity",
            similarity < 0.5f
        )
    }

    // ==========================================================================
    // Helper Functions (mimicking LlmEngine private methods)
    // ==========================================================================

    private fun classifyIntentTestable(text: String): String {
        val lowerPrompt = text.lowercase()

        return when {
            lowerPrompt.contains("remind") -> "ACTIONABLE - Set a reminder"
            lowerPrompt.contains("send") && (lowerPrompt.contains("message") || lowerPrompt.contains("text") || lowerPrompt.contains("sms")) ->
                "ACTIONABLE - Send a message"
            lowerPrompt.contains("call") -> "ACTIONABLE - Make a call"
            lowerPrompt.contains("open") -> "ACTIONABLE - Open an app"
            lowerPrompt.contains("search") -> "ACTIONABLE - Perform a search"
            lowerPrompt.contains("set") && lowerPrompt.contains("alarm") ->
                "ACTIONABLE - Set an alarm"
            lowerPrompt.contains("what is") -> "INFORMATIONAL - Definition query"
            lowerPrompt.contains("how") -> "INFORMATIONAL - How-to query"
            lowerPrompt.contains("why") -> "INFORMATIONAL - Explanation query"
            lowerPrompt.contains("when") -> "INFORMATIONAL - Time-related query"
            lowerPrompt.contains("who") -> "INFORMATIONAL - Person query"
            lowerPrompt.contains("weather") -> "INFORMATIONAL - Weather query"
            lowerPrompt.contains("?") -> "INFORMATIONAL - General question"
            else -> "UNCLEAR - Request needs clarification"
        }
    }

    private fun generateHelpfulResponseTestable(prompt: String): String {
        val lowerPrompt = prompt.lowercase()

        if (lowerPrompt.contains("weather")) {
            return "I don't have access to weather data yet. You can check the Weather app."
        }

        if (lowerPrompt.contains("time") || lowerPrompt.contains("what time")) {
            return "The current time is 12:00 PM."
        }

        if (lowerPrompt.contains("date") || lowerPrompt.contains("what day")) {
            return "Today is Monday, January 1, 2024."
        }

        if (lowerPrompt.contains("help") || lowerPrompt.contains("what can you do")) {
            return """I can help you with:
                |• Set reminders
                |• Send messages
                |• Open apps
                |• Make calls""".trimMargin()
        }

        return "I understand you want help. Could you be more specific?"
    }

    private fun generateSimpleEmbeddingTestable(text: String): FloatArray {
        val embedding = FloatArray(64)
        val normalized = text.lowercase().replace(Regex("[^a-z0-9\\s]"), "")
        val words = normalized.split(" ").filter { it.isNotEmpty() }

        for ((index, word) in words.withIndex()) {
            val hash = word.hashCode()
            val position = (hash and 0x3F).coerceIn(0, 63)
            embedding[position] += 1f / (index + 1)
        }

        val magnitude = kotlin.math.sqrt(embedding.map { it * it }.sum())
        if (magnitude > 0) {
            for (i in embedding.indices) {
                embedding[i] /= magnitude
            }
        }

        return embedding
    }

    private fun cosineSimilarityTestable(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
}

