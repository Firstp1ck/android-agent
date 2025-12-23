package com.agent.core

import com.agent.llm.LlmEngine
import com.agent.memory.MemoryManager

/**
 * Plans actions based on user queries.
 *
 * The TaskPlanner is responsible for:
 * 1. Understanding user intent
 * 2. Breaking down tasks into executable actions
 * 3. Determining safety levels
 * 4. Adapting templates to specific queries
 *
 * @property llmEngine LLM for understanding and planning
 * @property memoryManager Memory for templates and patterns
 * @property config Agent configuration
 */
class TaskPlanner(
    private val llmEngine: LlmEngine,
    private val memoryManager: MemoryManager,
    private val config: AgentConfig
) {
    /**
     * Create an action plan for a query.
     *
     * @param query The user's query
     * @return ActionPlan if the query requires actions, null for informational queries
     */
    suspend fun createPlan(query: AgentQuery): ActionPlan? {
        // Parse intent from query
        val intent = parseIntent(query.text)

        return when (intent) {
            is TaskIntent.Actionable -> createActionablePlan(intent, query)
            is TaskIntent.Informational -> null // Will be handled by text response
            is TaskIntent.Unclear -> null
        }
    }

    /**
     * Generate a text-only response (no actions).
     */
    suspend fun generateTextResponse(query: AgentQuery): String {
        return llmEngine.generateResponse(
            prompt = buildPrompt(query),
            maxTokens = config.model.maxTokens
        )
    }

    /**
     * Adapt a template to a specific query.
     */
    fun adaptTemplate(template: TaskTemplate, query: AgentQuery): ActionPlan {
        // Extract parameters from query based on template slots
        val parameters = extractParameters(query.text, template.parameterSlots)

        // Apply parameters to template actions
        val adaptedActions = template.actions.map { action ->
            applyParameters(action, parameters)
        }

        return ActionPlan(
            description = "Execute ${template.pattern} with your parameters",
            actions = adaptedActions,
            safetyLevel = determineSafetyLevel(adaptedActions),
            requiresConsent = true
        )
    }

    private suspend fun parseIntent(text: String): TaskIntent {
        // First, try rule-based intent detection for common patterns
        val quickIntent = detectQuickIntent(text)
        if (quickIntent != null) return quickIntent

        // Fall back to LLM for complex queries
        val response = llmEngine.generateResponse(
            prompt = """Classify this user request into one of these categories:
                |1. ACTIONABLE - User wants to perform an action (set reminder, send message, open app, etc.)
                |2. INFORMATIONAL - User is asking a question or wants information
                |3. UNCLEAR - The request is ambiguous or unclear
                |
                |User request: "$text"
                |
                |Respond with just the category name and a brief description of the intent.
            """.trimMargin(),
            maxTokens = 50
        )

        return when {
            response.contains("ACTIONABLE", ignoreCase = true) -> {
                TaskIntent.Actionable(
                    action = extractActionType(text),
                    target = extractTarget(text),
                    parameters = mutableMapOf()
                )
            }
            response.contains("INFORMATIONAL", ignoreCase = true) -> TaskIntent.Informational
            else -> TaskIntent.Unclear
        }
    }

    private fun detectQuickIntent(text: String): TaskIntent? {
        val lowerText = text.lowercase().trim()

        // Install app patterns (check before open)
        if (lowerText.contains("install ") || lowerText.contains("download ") || 
            lowerText.contains("get the app") || lowerText.contains("get app")) {
            val appName = extractAppNameForInstall(text)
            return TaskIntent.Actionable(
                action = "install_app",
                target = appName,
                parameters = mutableMapOf()
            )
        }

        // Open app patterns (check first - most common)
        if (lowerText.contains("open ") || lowerText.startsWith("launch ") || lowerText.startsWith("start ")) {
            val appName = extractAppName(text)
            return TaskIntent.Actionable(
                action = "open_app",
                target = appName,
                parameters = mutableMapOf()
            )
        }

        // Calendar/Event patterns
        if (lowerText.contains("calendar") || lowerText.contains("event") || 
            lowerText.contains("appointment") || lowerText.contains("meeting") ||
            lowerText.contains("schedule")) {
            val eventDetails = extractCalendarEventDetails(text)
            return TaskIntent.Actionable(
                action = "create_calendar_event",
                target = "calendar",
                parameters = eventDetails.toMutableMap()
            )
        }

        // Reminder patterns
        if (lowerText.contains("remind") || lowerText.contains("reminder")) {
            return TaskIntent.Actionable(
                action = "set_reminder",
                target = "clock",
                parameters = mutableMapOf("message" to extractReminderMessage(text))
            )
        }

        // Message patterns
        if (lowerText.contains("send") && (lowerText.contains("message") || lowerText.contains("text") || lowerText.contains("sms"))) {
            return TaskIntent.Actionable(
                action = "send_message",
                target = extractContact(text),
                parameters = mutableMapOf("message" to extractMessageContent(text))
            )
        }

        // Call patterns
        if (lowerText.contains("call ")) {
            return TaskIntent.Actionable(
                action = "call",
                target = extractContact(text),
                parameters = mutableMapOf()
            )
        }

        // Search patterns
        if (lowerText.contains("search") || lowerText.contains("google") || lowerText.contains("look up")) {
            return TaskIntent.Actionable(
                action = "search",
                target = "browser",
                parameters = mutableMapOf("query" to extractSearchQuery(text))
            )
        }

        // Settings patterns
        if (lowerText.contains("settings") || lowerText.contains("wifi") || lowerText.contains("bluetooth")) {
            return TaskIntent.Actionable(
                action = "open_app",
                target = "settings",
                parameters = mutableMapOf()
            )
        }

        return null
    }

    private suspend fun createActionablePlan(
        intent: TaskIntent.Actionable,
        query: AgentQuery
    ): ActionPlan {
        val actions = when (intent.action) {
            "set_reminder" -> createReminderActions(intent)
            "send_message" -> createMessageActions(intent)
            "open_app" -> createOpenAppActions(intent)
            "install_app" -> createInstallAppActions(intent)
            "create_calendar_event" -> createCalendarEventActions(intent)
            "call" -> createCallActions(intent)
            "search" -> createSearchActions(intent)
            else -> createGenericActions(intent, query)
        }

        return ActionPlan(
            description = describeIntent(intent),
            actions = actions,
            safetyLevel = determineSafetyLevel(actions),
            requiresConsent = true,
            rollbackActions = createRollbackActions(actions)
        )
    }

    // Action creation methods
    private fun createReminderActions(intent: TaskIntent.Actionable): List<AgentAction> {
        return listOf(
            AgentAction.LaunchApp(
                description = "Open Clock app",
                packageName = "com.google.android.deskclock"
            ),
            AgentAction.Click(
                description = "Tap on Alarm tab",
                selector = UiSelector(text = "Alarm")
            ),
            AgentAction.Click(
                description = "Create new alarm",
                selector = UiSelector(contentDescription = "Add alarm")
            ),
            // TODO: Add time selection and label input
        )
    }

    private fun createMessageActions(intent: TaskIntent.Actionable): List<AgentAction> {
        val contact = intent.target
        val message = intent.parameters["message"] ?: ""

        return listOf(
            AgentAction.LaunchApp(
                description = "Open Messages app",
                packageName = "com.google.android.apps.messaging"
            ),
            AgentAction.Click(
                description = "Start new conversation",
                selector = UiSelector(contentDescription = "Start chat")
            ),
            AgentAction.InputText(
                description = "Enter recipient: $contact",
                selector = UiSelector(resourceId = "recipient"),
                text = contact
            ),
            AgentAction.InputText(
                description = "Type message",
                selector = UiSelector(resourceId = "compose"),
                text = message,
                safetyLevel = SafetyLevel.CRITICAL
            ),
            AgentAction.Click(
                description = "Send message",
                selector = UiSelector(contentDescription = "Send"),
                safetyLevel = SafetyLevel.CRITICAL
            )
        )
    }

    private fun createOpenAppActions(intent: TaskIntent.Actionable): List<AgentAction> {
        val appName = intent.target
        val packageName = resolvePackageName(appName)

        return listOf(
            AgentAction.LaunchApp(
                description = "Open $appName",
                packageName = packageName
            )
        )
    }

    /**
     * Creates actions to create a calendar event.
     */
    private fun createCalendarEventActions(intent: TaskIntent.Actionable): List<AgentAction> {
        val title = intent.parameters["title"] ?: "New Event"
        val date = intent.parameters["date"] ?: "today"
        val time = intent.parameters["time"] ?: ""
        
        return listOf(
            // Step 1: Open Calendar app
            AgentAction.LaunchApp(
                description = "Open Calendar",
                packageName = "calendar"
            ),
            // Step 2: Wait for calendar to load
            AgentAction.Wait(
                description = "Wait for Calendar to open",
                condition = WaitCondition.Delay(1000),
                timeoutMs = 3000
            ),
            // Step 3: Click to create new event
            AgentAction.Click(
                description = "Create new event",
                selector = UiSelector(
                    contentDescription = "Create",
                    contentDescriptionContains = "new"
                )
            ),
            // Step 4: Wait for event form
            AgentAction.Wait(
                description = "Wait for event form",
                condition = WaitCondition.Delay(500),
                timeoutMs = 2000
            ),
            // Step 5: Enter event title
            AgentAction.InputText(
                description = "Enter event title: $title",
                selector = UiSelector(
                    resourceIdContains = "title",
                    className = "android.widget.EditText"
                ),
                text = title
            ),
            // Step 6: Save the event
            AgentAction.Click(
                description = "Save event",
                selector = UiSelector(
                    text = "Save",
                    contentDescription = "Save"
                )
            )
        )
    }

    /**
     * Creates actions to install an app from an app store.
     * Uses market:// URI for direct search - no accessibility needed!
     * 
     * Note: Android doesn't allow automatic app installation from Play Store
     * for security reasons. The agent opens the app page/search and the user
     * must tap "Install" manually.
     * 
     * Future enhancement: Use LLM to find the exact package ID for direct page access.
     */
    private fun createInstallAppActions(intent: TaskIntent.Actionable): List<AgentAction> {
        val appName = intent.target.trim()

        // Use market:// URI to directly open Play Store search
        // Play Store's search handles typos and fuzzy matching well
        // Note: User must tap "Install" - automatic installation not allowed by Android
        return listOf(
            AgentAction.LaunchApp(
                description = "Open Play Store search for '$appName' (tap Install to download)",
                packageName = "market://search?q=${appName.replace(" ", "+")}"
            )
        )
    }

    private fun createCallActions(intent: TaskIntent.Actionable): List<AgentAction> {
        val contact = intent.target

        return listOf(
            AgentAction.LaunchApp(
                description = "Open Phone app",
                packageName = "com.google.android.dialer"
            ),
            AgentAction.InputText(
                description = "Search for $contact",
                selector = UiSelector(resourceId = "search"),
                text = contact
            ),
            AgentAction.Click(
                description = "Select contact",
                selector = UiSelector(textContains = contact)
            ),
            AgentAction.Click(
                description = "Start call",
                selector = UiSelector(contentDescription = "Call"),
                safetyLevel = SafetyLevel.CRITICAL
            )
        )
    }

    private fun createSearchActions(intent: TaskIntent.Actionable): List<AgentAction> {
        val query = intent.parameters["query"] ?: ""

        return listOf(
            AgentAction.LaunchApp(
                description = "Open Browser",
                packageName = "com.android.chrome"
            ),
            AgentAction.Click(
                description = "Tap search bar",
                selector = UiSelector(resourceId = "search_box")
            ),
            AgentAction.InputText(
                description = "Enter search query",
                selector = UiSelector(resourceId = "search_box"),
                text = query
            ),
            AgentAction.Click(
                description = "Search",
                selector = UiSelector(text = "Search")
            )
        )
    }

    private suspend fun createGenericActions(
        intent: TaskIntent.Actionable,
        query: AgentQuery
    ): List<AgentAction> {
        // For unknown actions, try to find a relevant app or go home
        val target = intent.target.lowercase()
        
        // Try to resolve as an app
        val packageName = resolvePackageName(target)
        if (packageName != "com.android.launcher3") {
            return listOf(
                AgentAction.LaunchApp(
                    description = "Open ${intent.target}",
                    packageName = packageName
                )
            )
        }
        
        // Fallback: show a helpful message by going to home
        return listOf(
            AgentAction.Home(
                description = "Go to home screen (couldn't determine specific action for: ${query.text})"
            )
        )
    }

    private fun createRollbackActions(actions: List<AgentAction>): List<AgentAction> {
        // Simple rollback: just go back to home
        return listOf(AgentAction.Home())
    }

    private fun determineSafetyLevel(actions: List<AgentAction>): SafetyLevel {
        return actions.maxOfOrNull { it.safetyLevel } ?: SafetyLevel.SAFE
    }

    private fun describeIntent(intent: TaskIntent.Actionable): String {
        return when (intent.action) {
            "set_reminder" -> "Set a reminder"
            "send_message" -> "Send a message to ${intent.target}"
            "open_app" -> "Open ${intent.target}"
            "install_app" -> "Find '${intent.target}' in Play Store (you'll tap Install)"
            "create_calendar_event" -> "Create calendar event: ${intent.parameters["title"] ?: "New Event"}"
            "call" -> "Call ${intent.target}"
            "search" -> "Search for: ${intent.parameters["query"]}"
            else -> "Perform ${intent.action}"
        }
    }

    // Helper extraction methods
    
    /**
     * Extracts calendar event details from text.
     * Parses title, date, and time from natural language input.
     */
    private fun extractCalendarEventDetails(text: String): Map<String, String> {
        val details = mutableMapOf<String, String>()
        
        // Extract title - text after colon or the main subject
        val colonPattern = Regex(":(.+?)(?:\\s+(?:at|on|for|tomorrow|today)|$)", RegexOption.IGNORE_CASE)
        val colonMatch = colonPattern.find(text)
        if (colonMatch != null) {
            details["title"] = colonMatch.groupValues[1].trim()
        } else {
            // Try to extract from "about" or "for" patterns
            val aboutPattern = Regex("(?:about|for|called|titled)\\s+(.+?)(?:\\s+(?:at|on|tomorrow|today)|$)", RegexOption.IGNORE_CASE)
            aboutPattern.find(text)?.let {
                details["title"] = it.groupValues[1].trim()
            }
        }
        
        // If still no title, use the input after common words
        if (!details.containsKey("title")) {
            val fallbackPattern = Regex("(?:calendar|event|appointment|meeting|schedule)\\s+(?:input\\s+)?(?:for\\s+)?(.+?)(?:\\s+(?:at|on|tomorrow|today)|$)", RegexOption.IGNORE_CASE)
            fallbackPattern.find(text)?.let {
                details["title"] = it.groupValues[1].trim()
            } ?: run {
                details["title"] = "New Event"
            }
        }
        
        // Extract date
        val lowerText = text.lowercase()
        when {
            lowerText.contains("tomorrow") -> details["date"] = "tomorrow"
            lowerText.contains("today") -> details["date"] = "today"
            lowerText.contains("next week") -> details["date"] = "next week"
            else -> {
                // Try to extract specific date
                val datePattern = Regex("(?:on\\s+)?(\\d{1,2}[./\\-]\\d{1,2}(?:[./\\-]\\d{2,4})?)", RegexOption.IGNORE_CASE)
                datePattern.find(text)?.let {
                    details["date"] = it.groupValues[1]
                }
            }
        }
        
        // Extract time
        val timePattern = Regex("(?:at\\s+)?(\\d{1,2})(?::\\d{2})?\\s*(?:o'?clock|am|pm|uhr)?", RegexOption.IGNORE_CASE)
        timePattern.find(text)?.let {
            details["time"] = it.groupValues[0].trim()
        }
        
        return details
    }
    private fun extractReminderMessage(text: String): String {
        val patterns = listOf(
            Regex("remind(?:er)?\\s+(?:me\\s+)?(?:to\\s+)?(.+?)(?:\\s+at|\\s+in|\\s+tomorrow|$)", RegexOption.IGNORE_CASE),
            Regex("(?:about|for)\\s+(.+?)$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.let { return it.trim() }
        }
        return text
    }

    private fun extractContact(text: String): String {
        val pattern = Regex("(?:to|call)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?)")
        return pattern.find(text)?.groupValues?.get(1) ?: "Unknown"
    }

    private fun extractMessageContent(text: String): String {
        val patterns = listOf(
            Regex("saying\\s+[\"']?(.+?)[\"']?$", RegexOption.IGNORE_CASE),
            Regex("message\\s+[\"'](.+?)[\"']", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.let { return it }
        }
        return ""
    }

    private fun extractAppName(text: String): String {
        val patterns = listOf(
            Regex("(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE),
            Regex("(?:open|launch|start)\\s+(.+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.trim()?.let { 
                if (it.isNotBlank()) return it
            }
        }
        // Fallback: take last word
        return text.split(" ").lastOrNull()?.trim() ?: "Unknown"
    }

    /**
     * Extracts app name from install/download requests.
     */
    private fun extractAppNameForInstall(text: String): String {
        val patterns = listOf(
            Regex("(?:install|download|get)\\s+(?:the\\s+)?(?:app\\s+)?(.+?)(?:\\s+app)?$", RegexOption.IGNORE_CASE),
            Regex("(?:install|download|get)\\s+(.+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.get(1)?.trim()?.let { 
                if (it.isNotBlank()) return it
            }
        }
        // Fallback: take last word
        return text.split(" ").lastOrNull()?.trim() ?: "Unknown"
    }

    private fun extractSearchQuery(text: String): String {
        val pattern = Regex("search\\s+(?:for\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.groupValues?.get(1)?.trim() ?: text
    }

    private fun extractActionType(text: String): String {
        // Simplified action type extraction
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("remind") -> "set_reminder"
            lowerText.contains("message") || lowerText.contains("text") -> "send_message"
            lowerText.contains("open") -> "open_app"
            lowerText.contains("call") -> "call"
            lowerText.contains("search") -> "search"
            else -> "unknown"
        }
    }

    private fun extractTarget(text: String): String {
        // Will be overridden by specific extraction methods
        return "unknown"
    }

    /**
     * Normalizes app name for ActionExecutor lookup.
     * The ActionExecutor will dynamically resolve this to the actual package name
     * by searching installed apps on the device.
     */
    private fun resolvePackageName(appName: String): String {
        // Just normalize the name - ActionExecutor will handle the actual resolution
        // by searching installed apps on the device
        return appName.lowercase().trim()
    }

    private fun extractParameters(text: String, slots: List<ParameterSlot>): Map<String, String> {
        val parameters = mutableMapOf<String, String>()
        for (slot in slots) {
            val value = when (slot.type) {
                ParameterType.TEXT -> extractGenericText(text, slot.name)
                ParameterType.TIME -> extractTime(text)
                ParameterType.DATE -> extractDate(text)
                ParameterType.CONTACT -> extractContact(text)
                ParameterType.APP -> extractAppName(text)
                ParameterType.NUMBER -> extractNumber(text)
            }
            if (value != null) {
                parameters[slot.name] = value
            }
        }
        return parameters
    }

    private fun extractGenericText(text: String, fieldName: String): String? = text
    private fun extractTime(text: String): String? {
        val pattern = Regex("(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)", RegexOption.IGNORE_CASE)
        return pattern.find(text)?.value
    }
    private fun extractDate(text: String): String? {
        if (text.contains("tomorrow", ignoreCase = true)) return "tomorrow"
        if (text.contains("today", ignoreCase = true)) return "today"
        return null
    }
    private fun extractNumber(text: String): String? {
        val pattern = Regex("\\d+")
        return pattern.find(text)?.value
    }

    private fun applyParameters(action: AgentAction, parameters: Map<String, String>): AgentAction {
        // Replace parameter placeholders in action
        return when (action) {
            is AgentAction.InputText -> action.copy(
                text = parameters.entries.fold(action.text) { text, (key, value) ->
                    text.replace("{$key}", value)
                }
            )
            else -> action
        }
    }

    private fun buildPrompt(query: AgentQuery): String {
        return """You are a helpful Android assistant. Respond concisely and accurately.
            |
            |Time of day: ${query.context.timeOfDay}
            |Current app: ${query.context.currentApp ?: "Home screen"}
            |
            |User: ${query.text}
            |
            |Assistant:""".trimMargin()
    }
}

/**
 * Parsed user intent.
 */
sealed class TaskIntent {
    data class Actionable(
        val action: String,
        val target: String,
        val parameters: MutableMap<String, String>
    ) : TaskIntent()

    data object Informational : TaskIntent()
    data object Unclear : TaskIntent()
}

