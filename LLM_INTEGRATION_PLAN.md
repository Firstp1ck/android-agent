# LLM Integration Plan: Extensive Tool Calling Architecture

## Overview

This document outlines the plan for integrating a powerful local LLM with extensive tool calling capabilities into the Android AI Agent. The goal is to create a privacy-first, on-device AI assistant that can understand natural language and execute complex multi-step tasks.

---

## 1. LLM Backend Options

### 1.1 Primary: Local Inference (Privacy-First)

| Option | Model Size | RAM Required | Pros | Cons |
|--------|-----------|--------------|------|------|
| **llama.cpp** | 2-4GB (Q4) | 4-6GB | Fast, proven, GGUF support | JNI complexity |
| **MLC LLM** | 2-4GB | 4-6GB | Android-optimized, Vulkan GPU | Less mature |
| **MediaPipe LLM** | 1-2GB | 2-4GB | Google-supported, easy integration | Limited models |
| **ONNX Runtime** | 2-4GB | 4-6GB | Cross-platform | Less optimized for LLMs |

**Recommended Models for Tool Calling:**
- **Llama 3.2 3B Instruct** - Best balance of size/capability
- **Qwen 2.5 3B Instruct** - Excellent at structured output
- **Phi-3 Mini 3.8B** - Microsoft's efficient model
- **Mistral 7B Instruct** (Q4) - If device has 8GB+ RAM

### 1.2 Secondary: API Fallback (Optional)

For complex queries when local model struggles:
- OpenAI GPT-4o-mini (low cost, fast)
- Anthropic Claude 3 Haiku
- Local Ollama server (home network)
- Self-hosted vLLM

---

## 2. Tool Calling Architecture

### 2.1 Tool Definition Schema

```kotlin
data class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val requiresConfirmation: Boolean,
    val safetyLevel: SafetyLevel,
    val execute: suspend (Map<String, Any>) -> ToolResult
)

data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean,
    val enum: List<String>? = null
)

sealed class ToolResult {
    data class Success(val data: Any, val message: String) : ToolResult()
    data class Error(val error: String, val recoverable: Boolean) : ToolResult()
    data class NeedsInput(val prompt: String, val options: List<String>?) : ToolResult()
}
```

### 2.2 Core Tools to Implement

#### System Tools
| Tool | Description | Safety Level |
|------|-------------|--------------|
| `open_app` | Launch any installed application | SAFE |
| `search_apps` | Find apps matching query | SAFE |
| `get_installed_apps` | List all installed apps | SAFE |
| `open_settings` | Open specific settings page | SAFE |
| `get_device_info` | Get device/battery/storage info | SAFE |

#### Communication Tools
| Tool | Description | Safety Level |
|------|-------------|--------------|
| `send_sms` | Send SMS to contact | CRITICAL |
| `make_call` | Initiate phone call | CRITICAL |
| `send_email` | Compose and send email | CRITICAL |
| `get_contacts` | Search/list contacts | NORMAL |
| `get_recent_calls` | Get call history | NORMAL |

#### Calendar & Reminders
| Tool | Description | Safety Level |
|------|-------------|--------------|
| `create_event` | Add calendar event | NORMAL |
| `get_events` | Query calendar events | SAFE |
| `set_reminder` | Create reminder | SAFE |
| `set_alarm` | Set alarm | SAFE |

#### Web & Search
| Tool | Description | Safety Level |
|------|-------------|--------------|
| `web_search` | Search the web | SAFE |
| `open_url` | Open URL in browser | SAFE |
| `get_weather` | Get weather info | SAFE |
| `search_playstore` | Search Play Store | SAFE |

#### Files & Media
| Tool | Description | Safety Level |
|------|-------------|--------------|
| `take_photo` | Capture photo | NORMAL |
| `record_audio` | Record voice memo | NORMAL |
| `open_file` | Open file with app | SAFE |
| `search_files` | Find files | SAFE |

#### UI Automation (Accessibility)
| Tool | Description | Safety Level |
|------|-------------|--------------|
| `click_element` | Click UI element | NORMAL |
| `input_text` | Enter text in field | NORMAL |
| `scroll` | Scroll screen | SAFE |
| `get_screen_content` | Read screen text | SAFE |
| `wait_for_element` | Wait for UI state | SAFE |

### 2.3 Tool Call Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        User Query                                │
│                   "Send mom a birthday message"                  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                     LLM Processing                               │
│  1. Understand intent                                            │
│  2. Identify required tools                                      │
│  3. Generate tool call sequence                                  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Tool Call Decision                             │
│  {                                                               │
│    "tool": "get_contacts",                                       │
│    "parameters": { "query": "mom" }                              │
│  }                                                               │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Tool Execution                                 │
│  Result: { "name": "Mom", "phone": "+1234567890" }               │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│              LLM Processes Result & Continues                    │
│  {                                                               │
│    "tool": "send_sms",                                           │
│    "parameters": {                                               │
│      "recipient": "+1234567890",                                 │
│      "message": "Happy Birthday Mom! 🎂"                         │
│    }                                                             │
│  }                                                               │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│               User Confirmation (CRITICAL action)                │
│  "Send 'Happy Birthday Mom! 🎂' to Mom (+1234567890)?"           │
│  [Approve] [Edit] [Reject]                                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. LLM Prompt Engineering

### 3.1 System Prompt Template

```
You are an AI assistant running on an Android device. You help users accomplish tasks by calling tools.

## Available Tools
{tool_definitions_json}

## Guidelines
1. ALWAYS use tools to accomplish tasks - don't just describe what to do
2. Call tools one at a time and wait for results before proceeding
3. For CRITICAL actions (send_sms, make_call, send_email), the user will confirm
4. If a tool fails, try alternative approaches
5. Ask for clarification if the request is ambiguous
6. Be concise in responses

## Response Format
To call a tool, respond with JSON:
{"tool": "tool_name", "parameters": {...}}

To respond to the user without calling a tool:
{"response": "your message"}

## Current Context
- Device: {device_info}
- Time: {current_time}
- Date: {current_date}
- Recent apps: {recent_apps}
```

### 3.2 Few-Shot Examples

```
User: What time is it?
Assistant: {"response": "It's 3:45 PM."}

User: Open Chrome
Assistant: {"tool": "open_app", "parameters": {"app_name": "Chrome"}}
[Tool Result: Success - Chrome opened]
Assistant: {"response": "I've opened Chrome for you."}

User: Text John that I'll be late
Assistant: {"tool": "get_contacts", "parameters": {"query": "John"}}
[Tool Result: {"name": "John Smith", "phone": "+1555123456"}]
Assistant: {"tool": "send_sms", "parameters": {"recipient": "+1555123456", "message": "I'll be late"}}
[Waiting for user confirmation...]
```

---

## 4. Implementation Phases

### Phase 1: Foundation (Week 1-2)
- [ ] Set up llama.cpp JNI bindings
- [ ] Implement model loading/unloading
- [ ] Basic text generation
- [ ] Simple prompt/response cycle
- [ ] Benchmark performance on target devices

### Phase 2: Tool Calling (Week 3-4)
- [ ] Define tool schema and registry
- [ ] Implement JSON parsing for tool calls
- [ ] Create tool execution engine
- [ ] Add result injection into context
- [ ] Handle multi-turn tool conversations

### Phase 3: Core Tools (Week 5-6)
- [ ] Implement system tools (open_app, settings)
- [ ] Implement communication tools (SMS, call)
- [ ] Implement calendar tools
- [ ] Implement web/search tools
- [ ] Add permission handling

### Phase 4: Context & Memory (Week 7-8)
- [ ] Conversation history management
- [ ] User preference learning
- [ ] Frequently used actions cache
- [ ] Contact/app name resolution
- [ ] Context window optimization

### Phase 5: Advanced Features (Week 9-10)
- [ ] Multi-step task planning
- [ ] Error recovery and retries
- [ ] Parallel tool execution
- [ ] Streaming responses
- [ ] API fallback integration

### Phase 6: Polish & Optimization (Week 11-12)
- [ ] Model quantization tuning
- [ ] Latency optimization
- [ ] Battery usage optimization
- [ ] UI/UX improvements
- [ ] Comprehensive testing

---

## 5. Technical Implementation Details

### 5.1 llama.cpp Integration

```kotlin
// Native interface
class LlamaCpp {
    external fun loadModel(modelPath: String, contextSize: Int, gpuLayers: Int): Long
    external fun generate(
        modelPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stopSequences: Array<String>
    ): String
    external fun unloadModel(modelPtr: Long)
    
    companion object {
        init {
            System.loadLibrary("llama-android")
        }
    }
}
```

### 5.2 Tool Registry

```kotlin
object ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }
    
    fun getToolDefinitions(): String {
        return tools.values.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.parameters.map { param ->
                    mapOf(
                        "name" to param.name,
                        "type" to param.type.name,
                        "description" to param.description,
                        "required" to param.required
                    )
                }
            )
        }.let { Json.encodeToString(it) }
    }
    
    suspend fun execute(toolName: String, params: Map<String, Any>): ToolResult {
        val tool = tools[toolName] ?: return ToolResult.Error("Unknown tool: $toolName", false)
        return tool.execute(params)
    }
}
```

### 5.3 Conversation Manager

```kotlin
class ConversationManager(
    private val llm: LlmEngine,
    private val toolRegistry: ToolRegistry,
    private val maxContextTokens: Int = 4096
) {
    private val history = mutableListOf<Message>()
    
    suspend fun processUserMessage(userMessage: String): Flow<AgentResponse> = flow {
        history.add(Message.User(userMessage))
        
        var continueLoop = true
        while (continueLoop) {
            val prompt = buildPrompt()
            val response = llm.generate(prompt)
            
            when (val parsed = parseResponse(response)) {
                is ParsedResponse.ToolCall -> {
                    history.add(Message.Assistant(response))
                    
                    // Check if needs confirmation
                    val tool = toolRegistry.getTool(parsed.toolName)
                    if (tool?.requiresConfirmation == true) {
                        emit(AgentResponse.NeedsConfirmation(parsed))
                        return@flow // Wait for user confirmation
                    }
                    
                    // Execute tool
                    emit(AgentResponse.Executing(parsed.toolName))
                    val result = toolRegistry.execute(parsed.toolName, parsed.parameters)
                    history.add(Message.ToolResult(parsed.toolName, result))
                    
                    emit(AgentResponse.ToolCompleted(parsed.toolName, result))
                }
                is ParsedResponse.TextResponse -> {
                    history.add(Message.Assistant(response))
                    emit(AgentResponse.Text(parsed.text))
                    continueLoop = false
                }
                is ParsedResponse.Error -> {
                    emit(AgentResponse.Error(parsed.message))
                    continueLoop = false
                }
            }
        }
    }
    
    private fun buildPrompt(): String {
        val systemPrompt = buildSystemPrompt()
        val historyText = history.takeLast(20).joinToString("\n") { it.format() }
        return "$systemPrompt\n\n$historyText\nAssistant:"
    }
}
```

---

## 6. Model Selection Strategy

### 6.1 Automatic Model Selection

```kotlin
enum class DeviceCapability {
    LOW,      // <4GB RAM, no GPU
    MEDIUM,   // 4-6GB RAM, basic GPU
    HIGH,     // 6-8GB RAM, good GPU
    FLAGSHIP  // 8GB+ RAM, flagship GPU
}

fun selectModel(capability: DeviceCapability): ModelConfig {
    return when (capability) {
        DeviceCapability.LOW -> ModelConfig(
            name = "phi-2",
            quantization = "Q4_0",
            contextSize = 2048,
            gpuLayers = 0
        )
        DeviceCapability.MEDIUM -> ModelConfig(
            name = "llama-3.2-3b-instruct",
            quantization = "Q4_K_M",
            contextSize = 4096,
            gpuLayers = 10
        )
        DeviceCapability.HIGH -> ModelConfig(
            name = "llama-3.2-3b-instruct",
            quantization = "Q5_K_M",
            contextSize = 8192,
            gpuLayers = 20
        )
        DeviceCapability.FLAGSHIP -> ModelConfig(
            name = "mistral-7b-instruct",
            quantization = "Q4_K_M",
            contextSize = 8192,
            gpuLayers = 32
        )
    }
}
```

### 6.2 Model Download & Management

```kotlin
class ModelManager(private val context: Context) {
    private val modelsDir = File(context.filesDir, "models")
    
    suspend fun downloadModel(config: ModelConfig, onProgress: (Float) -> Unit) {
        val url = getModelUrl(config)
        val targetFile = File(modelsDir, "${config.name}-${config.quantization}.gguf")
        
        // Download with progress
        httpClient.downloadFile(url, targetFile) { bytesRead, totalBytes ->
            onProgress(bytesRead.toFloat() / totalBytes)
        }
    }
    
    fun getAvailableModels(): List<ModelConfig> {
        return modelsDir.listFiles()
            ?.filter { it.extension == "gguf" }
            ?.map { parseModelConfig(it.name) }
            ?: emptyList()
    }
}
```

---

## 7. Safety & Privacy

### 7.1 Action Confirmation Matrix

| Action Type | Confirmation Required | User Override |
|-------------|----------------------|---------------|
| Read data (contacts, calendar) | No | Can enable |
| Open apps | No | Can enable |
| Web search | No | Can enable |
| Create event/reminder | Optional | Yes |
| Send SMS/Email | **Always** | No |
| Make call | **Always** | No |
| Delete data | **Always** | No |
| Financial actions | **Always** | No |

### 7.2 Privacy Controls

```kotlin
data class PrivacySettings(
    val allowContactAccess: Boolean = true,
    val allowCalendarAccess: Boolean = true,
    val allowSmsAccess: Boolean = false,  // Off by default
    val allowCallLogs: Boolean = false,   // Off by default
    val allowLocationAccess: Boolean = false,
    val sendAnalytics: Boolean = false,
    val useApiWhenLocalFails: Boolean = false
)
```

---

## 8. Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| First token latency | <500ms | After model loaded |
| Tokens per second | >15 tps | On mid-range device |
| Model load time | <10s | Cold start |
| Memory usage | <4GB | During inference |
| Battery impact | <5%/hour | Active use |

---

## 9. Testing Strategy

### 9.1 Test Categories

1. **Unit Tests**: Tool implementations, JSON parsing, prompt building
2. **Integration Tests**: LLM + Tool execution flow
3. **End-to-End Tests**: Full user scenarios
4. **Performance Tests**: Latency, throughput, memory
5. **Safety Tests**: Confirmation flows, permission handling

### 9.2 Test Scenarios

```kotlin
@Test
fun `send message flow requires confirmation`() = runTest {
    val agent = createTestAgent()
    
    val response = agent.process("Text mom that I'm on my way")
    
    assertIs<AgentResponse.NeedsConfirmation>(response)
    assertEquals("send_sms", response.toolCall.toolName)
}

@Test
fun `multi-step task completes successfully`() = runTest {
    val agent = createTestAgent()
    
    val responses = agent.process("Set a reminder to call John tomorrow at 3pm")
        .toList()
    
    assertTrue(responses.any { it is AgentResponse.ToolCompleted })
    assertTrue(responses.last() is AgentResponse.Text)
}
```

---

## 10. Future Enhancements

### 10.1 Short Term
- Voice input/output integration
- Widget for quick actions
- Notification-based interactions
- Shortcuts integration

### 10.2 Medium Term
- Multi-modal support (image understanding)
- Cross-app automation workflows
- Smart suggestions based on context
- Proactive notifications

### 10.3 Long Term
- On-device fine-tuning (LoRA)
- Personalized model adaptation
- Multi-agent collaboration
- AR/VR integration

---

## 11. Dependencies

### Required Libraries
```groovy
// llama.cpp Android bindings
implementation("com.example:llama-android:1.0.0")

// JSON parsing
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// HTTP client for model download
implementation("io.ktor:ktor-client-android:2.3.0")
```

### Native Libraries
- llama.cpp compiled for ARM64
- Optional: Vulkan compute shaders for GPU acceleration

---

## 12. Resources

- [llama.cpp](https://github.com/ggerganov/llama.cpp) - Local inference engine
- [MLC LLM](https://mlc.ai/mlc-llm/) - Mobile-optimized inference
- [Hugging Face Models](https://huggingface.co/models) - Model repository
- [Android ML Kit](https://developers.google.com/ml-kit) - Google's ML tools

---

*Last updated: December 2024*
*Version: 1.0*

