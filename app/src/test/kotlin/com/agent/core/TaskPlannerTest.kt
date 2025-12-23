package com.agent.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TaskPlanner intent detection.
 *
 * Tests focus on accuracy and precision of intent parsing.
 */
class TaskPlannerTest {

    // ==========================================================================
    // Reminder Pattern Tests
    // ==========================================================================

    @Test
    fun `detectQuickIntent - reminder with time returns actionable`() {
        val patterns = listOf(
            "Remind me to call mom at 3pm",
            "Set a reminder for meeting tomorrow",
            "reminder to buy groceries",
            "Remind me about the appointment"
        )

        patterns.forEach { text ->
            val intent = detectQuickIntentTestable(text)
            assertNotNull("Should detect reminder intent for: $text", intent)
            assertEquals("set_reminder", (intent as? TaskIntent.Actionable)?.action)
        }
    }

    @Test
    fun `detectQuickIntent - reminder extracts message correctly`() {
        val testCases = mapOf(
            "Remind me to call mom at 3pm" to "call mom",
            "Remind me about the meeting" to "the meeting",
            "Reminder to buy groceries" to "buy groceries"
        )

        testCases.forEach { (input, expectedMessage) ->
            val message = extractReminderMessageTestable(input)
            assertTrue(
                "Should extract '$expectedMessage' from '$input', got '$message'",
                message.contains(expectedMessage, ignoreCase = true)
            )
        }
    }

    // ==========================================================================
    // Message Pattern Tests
    // ==========================================================================

    @Test
    fun `detectQuickIntent - send message returns actionable`() {
        val patterns = listOf(
            "Send a message to John",
            "Text mom saying I'll be late",
            "Send SMS to Dad",
            "message Jane about dinner"
        )

        patterns.forEach { text ->
            val intent = detectQuickIntentTestable(text)
            assertNotNull("Should detect message intent for: $text", intent)
            assertEquals("send_message", (intent as? TaskIntent.Actionable)?.action)
        }
    }

    @Test
    fun `extractContact - extracts name correctly`() {
        val testCases = mapOf(
            "Send a message to John" to "John",
            "Call Mom" to "Mom",
            "Text Sarah Jane" to "Sarah"
        )

        testCases.forEach { (input, expectedContact) ->
            val contact = extractContactTestable(input)
            assertEquals(
                "Should extract '$expectedContact' from '$input'",
                expectedContact, contact
            )
        }
    }

    // ==========================================================================
    // Open App Pattern Tests
    // ==========================================================================

    @Test
    fun `detectQuickIntent - open app returns actionable`() {
        val patterns = listOf(
            "Open Spotify",
            "Open the camera app",
            "open settings",
            "Open Chrome"
        )

        patterns.forEach { text ->
            val intent = detectQuickIntentTestable(text)
            assertNotNull("Should detect open app intent for: $text", intent)
            assertEquals("open_app", (intent as? TaskIntent.Actionable)?.action)
        }
    }

    @Test
    fun `extractAppName - extracts app name correctly`() {
        val testCases = mapOf(
            "Open Spotify" to "Spotify",
            "Open the camera app" to "camera",
            "open Chrome" to "Chrome"
        )

        testCases.forEach { (input, expectedApp) ->
            val app = extractAppNameTestable(input)
            assertTrue(
                "Should extract '$expectedApp' from '$input', got '$app'",
                app.contains(expectedApp, ignoreCase = true)
            )
        }
    }

    // ==========================================================================
    // Package Resolution Tests
    // ==========================================================================

    @Test
    fun `resolvePackageName - known apps resolve correctly`() {
        val testCases = mapOf(
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "whatsapp" to "com.whatsapp",
            "messages" to "com.google.android.apps.messaging",
            "settings" to "com.android.settings"
        )

        testCases.forEach { (appName, expectedPackage) ->
            val packageName = resolvePackageNameTestable(appName)
            assertEquals(
                "Should resolve '$appName' to '$expectedPackage'",
                expectedPackage, packageName
            )
        }
    }

    @Test
    fun `resolvePackageName - unknown apps return launcher`() {
        val unknown = resolvePackageNameTestable("some_unknown_app_xyz")
        assertEquals("com.android.launcher3", unknown)
    }

    // ==========================================================================
    // Call Pattern Tests
    // ==========================================================================

    @Test
    fun `detectQuickIntent - call returns actionable`() {
        val patterns = listOf(
            "Call mom",
            "Call John Smith",
            "call the office"
        )

        patterns.forEach { text ->
            val intent = detectQuickIntentTestable(text)
            assertNotNull("Should detect call intent for: $text", intent)
            assertEquals("call", (intent as? TaskIntent.Actionable)?.action)
        }
    }

    // ==========================================================================
    // Search Pattern Tests
    // ==========================================================================

    @Test
    fun `detectQuickIntent - search returns actionable`() {
        val patterns = listOf(
            "Search for coffee shops",
            "Search weather in New York",
            "search how to cook pasta"
        )

        patterns.forEach { text ->
            val intent = detectQuickIntentTestable(text)
            assertNotNull("Should detect search intent for: $text", intent)
            assertEquals("search", (intent as? TaskIntent.Actionable)?.action)
        }
    }

    @Test
    fun `extractSearchQuery - extracts query correctly`() {
        val testCases = mapOf(
            "Search for coffee shops" to "coffee shops",
            "Search weather in New York" to "weather in New York"
        )

        testCases.forEach { (input, expectedQuery) ->
            val query = extractSearchQueryTestable(input)
            assertEquals(
                "Should extract '$expectedQuery' from '$input'",
                expectedQuery, query
            )
        }
    }

    // ==========================================================================
    // Safety Level Tests
    // ==========================================================================

    @Test
    fun `determineSafetyLevel - critical actions are marked critical`() {
        val criticalActions = listOf(
            AgentAction.Click(
                description = "Send",
                selector = UiSelector(text = "Send"),
                safetyLevel = SafetyLevel.CRITICAL
            )
        )

        val level = determineSafetyLevelTestable(criticalActions)
        assertEquals(SafetyLevel.CRITICAL, level)
    }

    @Test
    fun `determineSafetyLevel - safe actions are marked safe`() {
        val safeActions = listOf(
            AgentAction.LaunchApp(
                description = "Open app",
                packageName = "com.example",
                safetyLevel = SafetyLevel.SAFE
            )
        )

        val level = determineSafetyLevelTestable(safeActions)
        assertEquals(SafetyLevel.SAFE, level)
    }

    @Test
    fun `determineSafetyLevel - mixed uses highest level`() {
        val mixedActions = listOf(
            AgentAction.LaunchApp(
                description = "Open app",
                packageName = "com.example",
                safetyLevel = SafetyLevel.SAFE
            ),
            AgentAction.InputText(
                description = "Type message",
                selector = UiSelector(resourceId = "input"),
                text = "Hello",
                safetyLevel = SafetyLevel.NORMAL
            ),
            AgentAction.Click(
                description = "Delete",
                selector = UiSelector(text = "Delete"),
                safetyLevel = SafetyLevel.CRITICAL
            )
        )

        val level = determineSafetyLevelTestable(mixedActions)
        assertEquals(SafetyLevel.CRITICAL, level)
    }

    // ==========================================================================
    // Edge Cases
    // ==========================================================================

    @Test
    fun `detectQuickIntent - empty string returns null`() {
        val intent = detectQuickIntentTestable("")
        assertNull(intent)
    }

    @Test
    fun `detectQuickIntent - unrelated text returns null`() {
        val texts = listOf(
            "Hello there",
            "What's the weather?",
            "How are you?",
            "Thanks!"
        )

        texts.forEach { text ->
            val intent = detectQuickIntentTestable(text)
            assertNull("Should not detect actionable intent for: $text", intent)
        }
    }

    // ==========================================================================
    // Helper Functions (mimicking TaskPlanner private methods)
    // ==========================================================================

    private fun detectQuickIntentTestable(text: String): TaskIntent? {
        val lowerText = text.lowercase()

        if (lowerText.matches(Regex(".*remind(er)?\\s+(me|us)?.*"))) {
            return TaskIntent.Actionable(
                action = "set_reminder",
                target = "clock",
                parameters = mutableMapOf("message" to extractReminderMessageTestable(text))
            )
        }

        if (lowerText.matches(Regex(".*send\\s+(a\\s+)?(message|text|sms).*")) ||
            lowerText.matches(Regex(".*\\b(text|message)\\s+\\w+.*"))) {
            return TaskIntent.Actionable(
                action = "send_message",
                target = extractContactTestable(text),
                parameters = mutableMapOf()
            )
        }

        if (lowerText.matches(Regex(".*open\\s+.*"))) {
            return TaskIntent.Actionable(
                action = "open_app",
                target = extractAppNameTestable(text),
                parameters = mutableMapOf()
            )
        }

        if (lowerText.matches(Regex(".*call\\s+.*"))) {
            return TaskIntent.Actionable(
                action = "call",
                target = extractContactTestable(text),
                parameters = mutableMapOf()
            )
        }

        if (lowerText.matches(Regex(".*search\\s+(for\\s+)?.*"))) {
            return TaskIntent.Actionable(
                action = "search",
                target = "browser",
                parameters = mutableMapOf("query" to extractSearchQueryTestable(text))
            )
        }

        return null
    }

    private fun extractReminderMessageTestable(text: String): String {
        val patterns = listOf(
            Regex("remind(?:er)?\\s+(?:me\\s+)?(?:to\\s+)?(.+?)(?:\\s+at|\\s+in|\\s+tomorrow|$)", RegexOption.IGNORE_CASE),
            Regex("(?:about|for)\\s+(.+?)$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.let { return it.trim() }
        }
        return text
    }

    private fun extractContactTestable(text: String): String {
        val pattern = Regex("(?:to|call|text|message)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?)", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.groupValues?.get(1)?.trim() ?: "Unknown"
    }

    private fun extractAppNameTestable(text: String): String {
        val pattern = Regex("open\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.groupValues?.get(1)?.trim() ?: "Unknown"
    }

    private fun extractSearchQueryTestable(text: String): String {
        val pattern = Regex("search\\s+(?:for\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.groupValues?.get(1)?.trim() ?: text
    }

    private fun resolvePackageNameTestable(appName: String): String {
        val knownApps = mapOf(
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "whatsapp" to "com.whatsapp"
        )
        return knownApps[appName.lowercase()] ?: "com.android.launcher3"
    }

    private fun determineSafetyLevelTestable(actions: List<AgentAction>): SafetyLevel {
        return actions.maxOfOrNull { it.safetyLevel } ?: SafetyLevel.SAFE
    }
}

