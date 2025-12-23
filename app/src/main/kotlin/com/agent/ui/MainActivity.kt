package com.agent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.agent.ui.theme.AgentTheme

/**
 * Main activity hosting the agent UI.
 * 
 * Note: Edge-to-edge mode is disabled for better compatibility with Waydroid
 * and other Android emulators where keyboard handling can be problematic.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Note: enableEdgeToEdge() removed for Waydroid compatibility
        // The adjustResize in manifest handles keyboard resizing

        setContent {
            AgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AgentScreen()
                }
            }
        }
    }
}

