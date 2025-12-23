package com.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.core.ScrollDirection
import com.agent.core.UiSelector
import kotlinx.coroutines.delay

/**
 * Accessibility Service for UI automation.
 *
 * This service enables the agent to:
 * - Find UI elements on screen
 * - Perform clicks and text input
 * - Scroll and navigate
 * - Monitor app state changes
 *
 * IMPORTANT: This service requires explicit user permission
 * in Settings > Accessibility.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.d("AgentAccessibility", "Service connected!")
        
        // Configure the service for better access
        val info = serviceInfo
        info.flags = info.flags or 
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        serviceInfo = info
        
        android.util.Log.d("AgentAccessibility", "Service configured with flags: ${info.flags}")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        android.util.Log.d("AgentAccessibility", "Service destroyed!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Monitor UI events for learning
        event?.let { processEvent(it) }
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    private fun processEvent(event: AccessibilityEvent) {
        // Could be used for learning user patterns
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // App changed
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // User clicked something
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Text input
            }
        }
    }

    // =========================================================================
    // Node Finding
    // =========================================================================

    /**
     * Find a node matching the selector.
     */
    fun findNode(selector: UiSelector): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root == null) {
            android.util.Log.e("AgentAccessibility", "rootInActiveWindow is NULL - cannot find elements!")
            android.util.Log.e("AgentAccessibility", "Make sure the accessibility service is enabled and connected")
            return null
        }
        
        android.util.Log.d("AgentAccessibility", "Root window: ${root.packageName}, childCount: ${root.childCount}")
        return findNodeRecursive(root, selector)
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        selector: UiSelector
    ): AccessibilityNodeInfo? {
        // Check if this node matches
        if (matchesSelector(node, selector)) {
            return node
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeRecursive(child, selector)
            if (result != null) return result
        }

        return null
    }

    private fun matchesSelector(node: AccessibilityNodeInfo, selector: UiSelector): Boolean {
        // Match by text (exact)
        selector.text?.let { expected ->
            val actual = node.text?.toString() ?: return false
            if (actual != expected) return false
        }

        // Match by text contains
        selector.textContains?.let { substring ->
            val actual = node.text?.toString() ?: return false
            if (!actual.contains(substring, ignoreCase = true)) return false
        }

        // Match by content description (exact or contains)
        selector.contentDescription?.let { expected ->
            val actual = node.contentDescription?.toString() ?: return false
            if (!actual.equals(expected, ignoreCase = true) && 
                !actual.contains(expected, ignoreCase = true)) return false
        }

        // Match by content description contains
        selector.contentDescriptionContains?.let { substring ->
            val actual = node.contentDescription?.toString() ?: return false
            if (!actual.contains(substring, ignoreCase = true)) return false
        }

        // Match by resource ID (exact)
        selector.resourceId?.let { expected ->
            val actual = node.viewIdResourceName ?: return false
            if (!actual.endsWith(expected) && !actual.contains(expected)) return false
        }

        // Match by resource ID contains
        selector.resourceIdContains?.let { substring ->
            val actual = node.viewIdResourceName ?: return false
            if (!actual.contains(substring, ignoreCase = true)) return false
        }

        // Match by class name
        selector.className?.let { expected ->
            val actual = node.className?.toString() ?: return false
            if (actual != expected) return false
        }

        // If we got here, all specified criteria matched
        // Return true only if at least one selector was specified
        return selector.text != null ||
                selector.textContains != null ||
                selector.contentDescription != null ||
                selector.contentDescriptionContains != null ||
                selector.resourceId != null ||
                selector.resourceIdContains != null ||
                selector.className != null
    }

    /**
     * Find all nodes matching the selector.
     */
    fun findAllNodes(selector: UiSelector): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesRecursive(root, selector, results)
        return results
    }

    private fun findAllNodesRecursive(
        node: AccessibilityNodeInfo,
        selector: UiSelector,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (matchesSelector(node, selector)) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllNodesRecursive(child, selector, results)
        }
    }

    // =========================================================================
    // Actions
    // =========================================================================

    /**
     * Perform a click on a node.
     */
    fun performClick(node: AccessibilityNodeInfo): Boolean {
        // Try clicking the node directly
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Try clicking a clickable parent
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }

        // Fall back to gesture click
        return performGestureClick(node)
    }

    private fun performGestureClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()

        return performClick(x, y)
    }

    /**
     * Perform a click at specific coordinates.
     */
    fun performClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Input text into a focused field.
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Perform a scroll.
     */
    fun performScroll(direction: ScrollDirection, amount: Float = 0.5f): Boolean {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.UP -> listOf(
                screenWidth / 2,
                screenHeight * 0.7f,
                screenWidth / 2,
                screenHeight * 0.3f
            )
            ScrollDirection.DOWN -> listOf(
                screenWidth / 2,
                screenHeight * 0.3f,
                screenWidth / 2,
                screenHeight * 0.7f
            )
            ScrollDirection.LEFT -> listOf(
                screenWidth * 0.7f,
                screenHeight / 2,
                screenWidth * 0.3f,
                screenHeight / 2
            )
            ScrollDirection.RIGHT -> listOf(
                screenWidth * 0.3f,
                screenHeight / 2,
                screenWidth * 0.7f,
                screenHeight / 2
            )
        }

        return performSwipe(startX, startY, endX, endY, 300)
    }

    private fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Press the back button.
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Go to home screen.
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Open recent apps.
     */
    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Open notifications.
     */
    fun performNotifications(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /**
     * Get the current app package name.
     */
    fun getCurrentPackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * Get visible text content on screen.
     */
    fun getScreenText(): String {
        val root = rootInActiveWindow
        if (root == null) {
            android.util.Log.w("AgentAccessibility", "getScreenText: rootInActiveWindow is NULL")
            return "[No screen access - is accessibility enabled?]"
        }
        val textBuilder = StringBuilder()
        collectText(root, textBuilder)
        return textBuilder.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let {
            builder.append(it).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, builder)
        }
    }

    /**
     * Debug: Print the UI tree.
     */
    fun dumpTree(): String {
        val root = rootInActiveWindow ?: return "No active window"
        val builder = StringBuilder()
        dumpNode(root, builder, 0)
        return builder.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append(node.className)
        node.text?.let { builder.append(" text='$it'") }
        node.contentDescription?.let { builder.append(" desc='$it'") }
        node.viewIdResourceName?.let { builder.append(" id='$it'") }
        if (node.isClickable) builder.append(" [clickable]")
        builder.append("\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, builder, depth + 1)
        }
    }
}

