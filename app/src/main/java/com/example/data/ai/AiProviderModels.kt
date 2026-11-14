package com.example.data.ai

enum class AiProviderType(
    val displayName: String,
    val badge: String,
    val requiresApiKey: Boolean,
    val isFreeNoKey: Boolean,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val defaultEndpoint: String,
    val description: String
) {
    FIREBASE_GEMINI(
        displayName = "Gemini Built-in (Free)",
        badge = "NO KEY • OFFICIAL",
        requiresApiKey = false,
        isFreeNoKey = true,
        defaultModel = "gemini-3.8-flash",
        suggestedModels = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"),
        defaultEndpoint = "Firebase AI Logic",
        description = "Works automatically with zero API key via Firebase App Check."
    ),
    POLLINATIONS_FREE(
        displayName = "Web Free AI (Pollinations)",
        badge = "NO KEY • WEB API",
        requiresApiKey = false,
        isFreeNoKey = true,
        defaultModel = "openai",
        suggestedModels = listOf("openai", "deepseek", "mistral", "qwen-coder"),
        defaultEndpoint = "https://text.pollinations.ai/openai",
        description = "Public zero-key web LLM gateway supporting OpenAI, DeepSeek & Mistral routing."
    ),
    OPENAI(
        displayName = "OpenAI (ChatGPT)",
        badge = "API KEY",
        requiresApiKey = true,
        isFreeNoKey = false,
        defaultModel = "gpt-4o-mini",
        suggestedModels = listOf("gpt-4o-mini", "gpt-4o", "o3-mini"),
        defaultEndpoint = "https://api.openai.com/v1/chat/completions",
        description = "Official OpenAI Chat Completions API."
    ),
    ANTHROPIC_CLAUDE(
        displayName = "Anthropic Claude",
        badge = "API KEY",
        requiresApiKey = true,
        isFreeNoKey = false,
        defaultModel = "claude-sonnet-4-20250514",
        suggestedModels = listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-latest"),
        defaultEndpoint = "https://api.anthropic.com/v1/messages",
        description = "Official Anthropic Messages API."
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        badge = "API KEY",
        requiresApiKey = true,
        isFreeNoKey = false,
        defaultModel = "deepseek-chat",
        suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
        defaultEndpoint = "https://api.deepseek.com/chat/completions",
        description = "DeepSeek Chat and R1 Reasoner API."
    ),
    GROQ(
        displayName = "Groq LPU",
        badge = "API KEY • FAST",
        requiresApiKey = true,
        isFreeNoKey = false,
        defaultModel = "llama-3.3-70b-versatile",
        suggestedModels = listOf("llama-3.3-70b-versatile", "deepseek-r1-distill-llama-70b"),
        defaultEndpoint = "https://api.groq.com/openai/v1/chat/completions",
        description = "Ultra-low-latency Llama & DeepSeek inference via Groq."
    ),
    OPENROUTER(
        displayName = "OpenRouter (All Models)",
        badge = "API KEY • :FREE",
        requiresApiKey = true,
        isFreeNoKey = false,
        defaultModel = "deepseek/deepseek-r1:free",
        suggestedModels = listOf(
            "deepseek/deepseek-r1:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "openai/gpt-4o-mini",
            "anthropic/claude-3.5-sonnet"
        ),
        defaultEndpoint = "https://openrouter.ai/api/v1/chat/completions",
        description = "Single key gateway for free and paid community models."
    ),
    XAI_GROK(
        displayName = "xAI Grok",
        badge = "API KEY",
        requiresApiKey = true,
        isFreeNoKey = false,
        defaultModel = "grok-3-mini",
        suggestedModels = listOf("grok-3-mini", "grok-3"),
        defaultEndpoint = "https://api.x.ai/v1/chat/completions",
        description = "xAI Grok API."
    ),
    MISTRAL(
        displayName = "Mistral AI",
        badge = "API KEY",
        requiresApiKey = true,
        isFreeNoKey = false,
        defaultModel = "mistral-small-latest",
        suggestedModels = listOf("mistral-small-latest", "mistral-large-latest"),
        defaultEndpoint = "https://api.mistral.ai/v1/chat/completions",
        description = "Mistral La Plateforme API."
    ),
    CUSTOM_OPENAI(
        displayName = "Custom OpenAI / Local",
        badge = "CUSTOM URL",
        requiresApiKey = false,
        isFreeNoKey = false,
        defaultModel = "llama3.2",
        suggestedModels = listOf("llama3.2", "qwen2.5", "deepseek-r1"),
        defaultEndpoint = "http://127.0.0.1:11434/v1/chat/completions",
        description = "Connect Ollama, LM Studio, vLLM, or any OpenAI-compatible URL."
    )
}

data class WebAiPortal(
    val id: String,
    val name: String,
    val subtitle: String,
    val url: String
)

object WebAiPortals {
    val all: List<WebAiPortal> = listOf(
        WebAiPortal("chatgpt_web", "ChatGPT Web", "Free web chat • GPT-4o / o3", "https://chatgpt.com/"),
        WebAiPortal("gemini_web", "Gemini Web", "Free Google AI web workspace", "https://gemini.google.com/"),
        WebAiPortal("deepseek_web", "DeepSeek Web", "Free DeepSeek V3 & R1 web chat", "https://chat.deepseek.com/"),
        WebAiPortal("claude_web", "Claude Web", "Anthropic Claude web interface", "https://claude.ai/"),
        WebAiPortal("perplexity_web", "Perplexity Web", "Live web-grounded AI search", "https://www.perplexity.ai/"),
        WebAiPortal("grok_web", "Grok Web", "xAI Grok web workspace", "https://grok.com/"),
        WebAiPortal("huggingchat", "HuggingChat", "Open-source models • No key", "https://huggingface.co/chat/")
    )
}

data class AiHubSettings(
    val selectedProvider: AiProviderType = AiProviderType.FIREBASE_GEMINI,
    val modelByProvider: Map<AiProviderType, String> = emptyMap(),
    val apiKeysByProvider: Map<AiProviderType, String> = emptyMap(),
    val customBaseUrl: String = AiProviderType.CUSTOM_OPENAI.defaultEndpoint,
    val includeDeskContext: Boolean = true
) {
    fun activeModel(provider: AiProviderType = selectedProvider): String =
        modelByProvider[provider]?.takeIf { it.isNotBlank() } ?: provider.defaultModel

    fun apiKey(provider: AiProviderType = selectedProvider): String =
        apiKeysByProvider[provider].orEmpty()
}

enum class ChatRole { USER, ASSISTANT }

data class AiChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val providerLabel: String,
    val modelLabel: String,
    val timestampUtc: String,
    val isStreaming: Boolean = false,
    val isError: Boolean = false
)
