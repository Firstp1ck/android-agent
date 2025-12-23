package com.agent.core

import android.content.Intent
import android.content.pm.PackageManager
import com.agent.AgentApplication
import com.agent.service.AgentAccessibilityService
import kotlinx.coroutines.delay
import kotlin.system.measureTimeMillis

/**
 * Executes agent actions via AccessibilityService.
 *
 * The ActionExecutor is responsible for:
 * 1. Translating AgentActions into accessibility operations
 * 2. Executing actions safely with proper timing
 * 3. Verifying action success
 * 4. Reporting results
 */
class ActionExecutor {

    private val accessibilityService: AgentAccessibilityService?
        get() = AgentAccessibilityService.instance

    /**
     * Cache of installed apps: maps app label (lowercase) to package name.
     * Lazily initialized on first use.
     */
    private var installedAppsCache: Map<String, InstalledApp>? = null
    private var lastCacheTime: Long = 0
    private val cacheValidityMs = 60_000L // Refresh cache every 60 seconds

    /**
     * Represents an installed app with its metadata.
     */
    data class InstalledApp(
        val packageName: String,
        val label: String,
        val labelLower: String
    )

    /**
     * Execute a single action.
     *
     * @param action The action to execute
     * @return Result of the execution
     */
    suspend fun execute(action: AgentAction): ActionResult {
        val durationMs = measureTimeMillis {
            // Check if accessibility service is available
            if (accessibilityService == null && requiresAccessibility(action)) {
                return ActionResult.Failure(
                    actionId = action.id,
                    durationMs = 0,
                    error = "Accessibility service not enabled",
                    recoverable = false
                )
            }

            return try {
                val success = when (action) {
                    is AgentAction.LaunchApp -> executeLaunchApp(action)
                    is AgentAction.Click -> executeClick(action)
                    is AgentAction.InputText -> executeInputText(action)
                    is AgentAction.Scroll -> executeScroll(action)
                    is AgentAction.Wait -> executeWait(action)
                    is AgentAction.Back -> executeBack()
                    is AgentAction.Home -> executeHome()
                }

                if (success) {
                    ActionResult.Success(
                        actionId = action.id,
                        durationMs = 0, // Will be updated below
                        message = "Action completed: ${action.description}"
                    )
                } else {
                    ActionResult.Failure(
                        actionId = action.id,
                        durationMs = 0,
                        error = "Action failed: ${action.description}",
                        recoverable = true
                    )
                }
            } catch (e: Exception) {
                ActionResult.Failure(
                    actionId = action.id,
                    durationMs = 0,
                    error = e.message ?: "Unknown error",
                    recoverable = false
                )
            }
        }

        // Update duration in result
        return when (val result = execute(action)) {
            is ActionResult.Success -> result.copy(durationMs = durationMs)
            is ActionResult.Failure -> result.copy(durationMs = durationMs)
        }
    }

    private fun requiresAccessibility(action: AgentAction): Boolean {
        return when (action) {
            is AgentAction.LaunchApp -> false // Can use intents
            else -> true
        }
    }

    private suspend fun executeLaunchApp(action: AgentAction.LaunchApp): Boolean {
        val context = AgentApplication.instance
        val packageManager = context.packageManager

        android.util.Log.d("ActionExecutor", "Launching app: ${action.packageName}")

        var intent: Intent? = null

        // Handle market:// URIs for Play Store actions
        if (action.packageName.startsWith("market://")) {
            android.util.Log.d("ActionExecutor", "Market URI detected: ${action.packageName}")
            intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(action.packageName)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Try to open in Play Store specifically
            intent.setPackage("com.android.vending")
        }

        // Handle http/https URLs
        if (intent == null && (action.packageName.startsWith("http://") || action.packageName.startsWith("https://"))) {
            android.util.Log.d("ActionExecutor", "URL detected: ${action.packageName}")
            intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(action.packageName)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // Special handling for app store requests - find best available store
        val isAppStoreRequest = intent == null && action.packageName.lowercase().let {
            it.contains("play store") || it.contains("playstore") || 
            it.contains("app store") || it.contains("store") ||
            it == "play" || it == "market"
        }
        
        if (isAppStoreRequest) {
            android.util.Log.d("ActionExecutor", "App store request detected, finding available store...")
            intent = findBestAppStore(packageManager)
        }

        // First, try direct package name if it looks like a full package name
        if (intent == null && action.packageName.contains(".") && !action.packageName.contains("://")) {
            intent = if (action.activityName != null) {
                Intent().apply {
                    setClassName(action.packageName, action.activityName)
                }
            } else {
                packageManager.getLaunchIntentForPackage(action.packageName)
            }
        }

        // If direct lookup failed, search installed apps by name
        if (intent == null) {
            android.util.Log.d("ActionExecutor", "Direct lookup failed, searching installed apps...")
            val matchedApp = findInstalledApp(action.packageName)
            if (matchedApp != null) {
                android.util.Log.d("ActionExecutor", "Found app: ${matchedApp.label} (${matchedApp.packageName})")
                intent = packageManager.getLaunchIntentForPackage(matchedApp.packageName)
            }
        }

        // Fallback for browsers - use ACTION_VIEW
        if (intent == null && (action.packageName.contains("chrome", ignoreCase = true) || 
                               action.packageName.contains("browser", ignoreCase = true))) {
            android.util.Log.d("ActionExecutor", "Browser not found, trying browser intent")
            intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                android.util.Log.d("ActionExecutor", "App launched successfully")
                delay(500) // Wait for app to launch
                true
            } catch (e: Exception) {
                android.util.Log.e("ActionExecutor", "Failed to launch app", e)
                false
            }
        } else {
            android.util.Log.e("ActionExecutor", "No app found matching: ${action.packageName}")
            logAvailableApps()
            false
        }
    }

    /**
     * Refreshes the cache of installed apps from the system.
     */
    private fun refreshInstalledAppsCache() {
        val context = AgentApplication.instance
        val packageManager = context.packageManager
        
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        val resolveInfoList = packageManager.queryIntentActivities(launcherIntent, 0)
        val apps = mutableMapOf<String, InstalledApp>()
        
        for (info in resolveInfoList) {
            val label = info.loadLabel(packageManager).toString()
            val packageName = info.activityInfo.packageName
            val app = InstalledApp(
                packageName = packageName,
                label = label,
                labelLower = label.lowercase()
            )
            
            // Index by lowercase label
            apps[app.labelLower] = app
            
            // Also index by label without spaces
            val labelNoSpaces = app.labelLower.replace(" ", "")
            if (labelNoSpaces != app.labelLower) {
                apps[labelNoSpaces] = app
            }
            
            // Also index by package name suffix (e.g., "vending" for com.android.vending)
            val packageSuffix = packageName.substringAfterLast(".")
            if (!apps.containsKey(packageSuffix)) {
                apps[packageSuffix] = app
            }
        }
        
        installedAppsCache = apps
        lastCacheTime = System.currentTimeMillis()
        android.util.Log.d("ActionExecutor", "Cached ${apps.size} installed apps")
    }

    /**
     * Gets the installed apps cache, refreshing if necessary.
     */
    private fun getInstalledApps(): Map<String, InstalledApp> {
        val now = System.currentTimeMillis()
        if (installedAppsCache == null || now - lastCacheTime > cacheValidityMs) {
            refreshInstalledAppsCache()
        }
        return installedAppsCache ?: emptyMap()
    }

    /**
     * Finds an installed app matching the given search term.
     * Uses fuzzy matching to handle variations in app names.
     */
    private fun findInstalledApp(searchTerm: String): InstalledApp? {
        val apps = getInstalledApps()
        val searchLower = searchTerm.lowercase().trim()
        val searchNoSpaces = searchLower.replace(" ", "")
        
        // 1. Exact match on label
        apps[searchLower]?.let { return it }
        
        // 2. Exact match without spaces (e.g., "playstore" matches "Play Store")
        apps[searchNoSpaces]?.let { return it }
        
        // 3. Check common aliases
        val aliases = mapOf(
            "playstore" to listOf("play store", "google play store"),
            "play store" to listOf("google play store"),
            "chrome" to listOf("google chrome"),
            "maps" to listOf("google maps"),
            "gmail" to listOf("google mail"),
            "youtube" to listOf("youtube music", "youtube"),
            "files" to listOf("files by google", "file manager", "my files"),
            "photos" to listOf("google photos"),
            "drive" to listOf("google drive"),
            "calendar" to listOf("google calendar")
        )
        
        aliases[searchLower]?.forEach { alias ->
            apps[alias]?.let { return it }
            apps[alias.replace(" ", "")]?.let { return it }
        }
        
        // 4. Contains match - find apps whose label contains the search term
        val containsMatches = apps.values.filter { 
            it.labelLower.contains(searchLower) || searchLower.contains(it.labelLower)
        }
        if (containsMatches.size == 1) {
            return containsMatches.first()
        }
        
        // 5. Package name contains search term
        val packageMatches = apps.values.filter { 
            it.packageName.contains(searchLower, ignoreCase = true)
        }
        if (packageMatches.size == 1) {
            return packageMatches.first()
        }
        
        // 6. Fuzzy match - find best match by similarity
        val bestMatch = apps.values
            .map { app -> app to calculateSimilarity(searchLower, app.labelLower) }
            .filter { it.second > 0.6 } // Minimum 60% similarity
            .maxByOrNull { it.second }
            ?.first
        
        return bestMatch
    }

    /**
     * Calculates similarity between two strings (0.0 to 1.0).
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        
        // Simple similarity: longest common subsequence ratio
        val lcs = longestCommonSubsequence(s1, s2)
        return (2.0 * lcs) / (s1.length + s2.length)
    }

    /**
     * Calculates the length of the longest common subsequence.
     */
    private fun longestCommonSubsequence(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        
        return dp[m][n]
    }

    /**
     * Finds the best available app store on the device.
     * Tries in order: Play Store, Aurora Store, F-Droid, Aptoide, etc.
     */
    private fun findBestAppStore(packageManager: PackageManager): Intent? {
        // List of app stores in order of preference
        val appStores = listOf(
            "com.android.vending",           // Google Play Store
            "com.aurora.store",              // Aurora Store (FOSS Play Store client)
            "org.fdroid.fdroid",             // F-Droid
            "com.aurora.adroid",             // Aurora Droid (F-Droid client)
            "cm.aptoide.pt",                 // Aptoide
            "com.amazon.venezia",            // Amazon Appstore
            "com.sec.android.app.samsungapps", // Samsung Galaxy Store
            "com.huawei.appmarket",          // Huawei AppGallery
            "ru.vk.store",                   // RuStore
            "com.xiaomi.market"              // Mi Store
        )
        
        for (store in appStores) {
            val intent = packageManager.getLaunchIntentForPackage(store)
            if (intent != null) {
                android.util.Log.d("ActionExecutor", "Found app store: $store")
                return intent
            }
        }
        
        // Fallback: search for any app with "store" or "market" in the name
        val apps = getInstalledApps()
        val storeApp = apps.values.find { 
            (it.labelLower.contains("store") || it.labelLower.contains("market")) &&
            !it.labelLower.contains("restore") // Exclude restore apps
        }
        
        if (storeApp != null) {
            android.util.Log.d("ActionExecutor", "Found store app by name: ${storeApp.label}")
            return packageManager.getLaunchIntentForPackage(storeApp.packageName)
        }
        
        android.util.Log.w("ActionExecutor", "No app store found on device!")
        return null
    }

    /**
     * Gets the name of the available app store (for display purposes).
     */
    fun getAvailableAppStoreName(): String {
        val context = AgentApplication.instance
        val packageManager = context.packageManager
        
        val storeNames = mapOf(
            "com.android.vending" to "Play Store",
            "com.aurora.store" to "Aurora Store",
            "org.fdroid.fdroid" to "F-Droid",
            "com.aurora.adroid" to "Aurora Droid",
            "cm.aptoide.pt" to "Aptoide",
            "com.amazon.venezia" to "Amazon Appstore",
            "com.sec.android.app.samsungapps" to "Galaxy Store",
            "com.huawei.appmarket" to "AppGallery"
        )
        
        for ((pkg, name) in storeNames) {
            if (packageManager.getLaunchIntentForPackage(pkg) != null) {
                return name
            }
        }
        
        return "App Store"
    }

    /**
     * Logs available apps for debugging.
     */
    private fun logAvailableApps() {
        val apps = getInstalledApps()
        android.util.Log.d("ActionExecutor", "Available apps (${apps.size} total):")
        apps.values.distinctBy { it.packageName }.take(20).forEach { app ->
            android.util.Log.d("ActionExecutor", "  - ${app.label} (${app.packageName})")
        }
    }

    /**
     * Gets list of all installed apps (for use by TaskPlanner/LLM).
     */
    fun getAvailableApps(): List<InstalledApp> {
        return getInstalledApps().values.distinctBy { it.packageName }
    }

    private suspend fun executeClick(action: AgentAction.Click): Boolean {
        android.util.Log.d("ActionExecutor", "Executing click: ${action.description}")
        
        val service = accessibilityService
        if (service == null) {
            android.util.Log.e("ActionExecutor", "Click failed: Accessibility service not available!")
            android.util.Log.e("ActionExecutor", "Please enable the Accessibility Service in Settings > Accessibility > AI Agent")
            return false
        }

        android.util.Log.d("ActionExecutor", "Looking for element: ${action.selector.toAccessibilityQuery()}")
        
        // Find the node matching the selector
        var node = service.findNode(action.selector)
        if (node == null) {
            android.util.Log.d("ActionExecutor", "Element not found, waiting 1s and retrying...")
            // Wait and retry
            delay(1000)
            node = service.findNode(action.selector)
            if (node == null) {
                android.util.Log.e("ActionExecutor", "Element not found after retry: ${action.selector.toAccessibilityQuery()}")
                // Log the UI tree for debugging
                val uiTree = service.dumpTree()
                android.util.Log.d("ActionExecutor", "UI Tree dump (first 2000 chars):")
                uiTree.take(2000).chunked(500).forEach { chunk ->
                    android.util.Log.d("ActionExecutor", chunk)
                }
                return false
            }
        }

        android.util.Log.d("ActionExecutor", "Element found, performing click")
        val result = service.performClick(node)
        android.util.Log.d("ActionExecutor", "Click result: $result")
        return result
    }

    private suspend fun executeInputText(action: AgentAction.InputText): Boolean {
        android.util.Log.d("ActionExecutor", "Executing input: ${action.description}")
        
        val service = accessibilityService
        if (service == null) {
            android.util.Log.e("ActionExecutor", "Input failed: Accessibility service not available!")
            return false
        }

        android.util.Log.d("ActionExecutor", "Looking for input field: ${action.selector.toAccessibilityQuery()}")
        
        val node = service.findNode(action.selector)
        if (node == null) {
            android.util.Log.e("ActionExecutor", "Input field not found: ${action.selector.toAccessibilityQuery()}")
            return false
        }

        android.util.Log.d("ActionExecutor", "Input field found, focusing...")
        // Focus the field first
        if (!service.performClick(node)) {
            android.util.Log.e("ActionExecutor", "Failed to focus input field")
            return false
        }
        delay(200)

        // Input text
        android.util.Log.d("ActionExecutor", "Entering text: ${action.text}")
        val result = service.inputText(node, action.text)
        android.util.Log.d("ActionExecutor", "Input result: $result")
        return result
    }

    private suspend fun executeScroll(action: AgentAction.Scroll): Boolean {
        android.util.Log.d("ActionExecutor", "Executing scroll: ${action.direction}")
        val service = accessibilityService ?: return false
        return service.performScroll(action.direction, action.amount)
    }

    private suspend fun executeWait(action: AgentAction.Wait): Boolean {
        return when (val condition = action.condition) {
            is WaitCondition.Delay -> {
                delay(condition.durationMs)
                true
            }
            is WaitCondition.ElementVisible -> {
                waitForElement(condition.selector, action.timeoutMs, visible = true)
            }
            is WaitCondition.ElementGone -> {
                waitForElement(condition.selector, action.timeoutMs, visible = false)
            }
        }
    }

    private suspend fun waitForElement(
        selector: UiSelector,
        timeoutMs: Long,
        visible: Boolean
    ): Boolean {
        val service = accessibilityService ?: return false
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val found = service.findNode(selector) != null
            if (found == visible) return true
            delay(200)
        }

        return false
    }

    private suspend fun executeBack(): Boolean {
        val service = accessibilityService ?: return false
        return service.performBack()
    }

    private suspend fun executeHome(): Boolean {
        val service = accessibilityService ?: return false
        return service.performHome()
    }
}

