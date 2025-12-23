# Android AI Agent

A privacy-first, self-learning Android AI agent that operates primarily on-device with optional API fallback for complex tasks.

## 🎯 Key Features

- **Privacy-First**: All processing on-device by default
- **User Consent**: Always asks permission before executing actions
- **Learning Memory**: Learns from your patterns to work faster
- **Daily Task Automation**: Reminders, messages, app control, and more

## 📱 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                          USER INTERFACE                          │
│                     (Jetpack Compose Chat UI)                    │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      AGENT ORCHESTRATOR                          │
│              (Coordinates all agent operations)                  │
└────────────────────────────┬────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
┌───────────────┐    ┌───────────────┐    ┌───────────────┐
│  TASK PLANNER │    │  LLM ENGINE   │    │MEMORY MANAGER │
│  (Parse+Plan) │    │ (Inference)   │    │  (MobiMem)    │
└───────────────┘    └───────────────┘    └───────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────────┐
│                     ACTION EXECUTOR                            │
│              (Via AccessibilityService)                        │
└───────────────────────────────────────────────────────────────┘
```

## 🚀 Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 34
- An Android device or emulator (min SDK 28)

### Building

```bash
# Clone the repository
git clone https://github.com/user/android-agent.git
cd android-agent

# Build debug APK
./gradlew assembleDebug

# Install on device
./gradlew installDebug
```

### Setup on Device

1. Install the app
2. Open **Settings > Accessibility > AI Agent**
3. Enable the accessibility service
4. Return to the app and start chatting!

## 💬 Example Commands

| Command | What Happens |
|---------|--------------|
| "Remind me to call mom at 3pm" | Sets a reminder in Clock app |
| "Send a message to John saying I'll be late" | Opens Messages, drafts text (asks consent before send) |
| "Open Spotify" | Launches Spotify app |
| "Search for best coffee shops nearby" | Opens browser and searches |
| "What time is it?" | Responds with current time (no action) |

## 🔒 Privacy & Security

- **100% On-Device**: Default mode processes everything locally
- **No Data Collection**: Nothing leaves your device unless you enable API
- **User Consent**: Critical actions always require explicit approval
- **Transparent Actions**: See exactly what the agent will do before it does it

## 📁 Project Structure

```
app/src/main/kotlin/com/agent/
├── AgentApplication.kt       # App initialization
├── core/
│   ├── AgentConfig.kt        # Configuration
│   ├── AgentOrchestrator.kt  # Main coordinator
│   ├── TaskPlanner.kt        # Intent parsing & planning
│   ├── ActionExecutor.kt     # Action execution
│   └── Types.kt              # Core data types
├── memory/
│   └── MemoryManager.kt      # MobiMem implementation
├── llm/
│   └── LlmEngine.kt          # LLM inference
├── service/
│   ├── AgentAccessibilityService.kt  # UI automation
│   ├── AgentService.kt       # Background service
│   └── BootReceiver.kt       # Boot handler
└── ui/
    ├── MainActivity.kt       # Main activity
    ├── AgentScreen.kt        # Compose UI
    ├── AgentViewModel.kt     # ViewModel
    └── theme/                # Material 3 theme
```

## 🧠 Memory System (MobiMem)

Three-layer memory for learning:

1. **Profile Memory**: User preferences and patterns
2. **Experience Memory**: Reusable task templates
3. **Action Memory**: Recorded UI action sequences

## 🎯 Safety Levels

| Level | Description | User Consent |
|-------|-------------|--------------|
| SAFE | Read-only operations | Optional |
| NORMAL | Reversible actions | Configurable |
| CRITICAL | Financial, delete, send | Always required |

## 🔧 Configuration

Edit `AgentConfig.kt` or use in-app settings:

```kotlin
AgentConfig(
    operatingMode = OperatingMode.LOCAL_ONLY,
    fallbackTimeoutMs = 2000L,
    safety = SafetyConfig(
        alwaysPreview = true,
        autoRollback = true
    )
)
```

## 🚧 MVP Limitations

This is an MVP with the following limitations:

- LLM uses rule-based fallback (full llama.cpp integration planned)
- Limited app support (major Google apps)
- No voice input yet
- No RAG integration yet

## 📋 Roadmap

- [ ] Integrate llama.cpp for on-device LLM
- [ ] Add voice input/output
- [ ] Implement MobileRAG for document/contact search
- [ ] Add LoRA fine-tuning for personalization
- [ ] Widget for quick access
- [ ] Notification handling

## 📄 License

MIT License - See [LICENSE](LICENSE) for details.
