import os

# Defaults for a /run request that carries no llm* fields (the Java side always sends them).
LLM_PROVIDER = os.environ.get("LLM_PROVIDER", "anthropic")
LLM_API_KEYS = {
    "anthropic": os.environ.get("ANTHROPIC_API_KEY", ""),
    "gemini": os.environ.get("GEMINI_API_KEY", ""),
    "openai": os.environ.get("OPENAI_API_KEY", ""),
}
LLM_MODELS = {
    "anthropic": os.environ.get("ANTHROPIC_MODEL") or os.environ.get("CLAUDE_MODEL", "claude-sonnet-4-6"),
    "gemini": os.environ.get("GEMINI_MODEL", ""),
    "openai": os.environ.get("OPENAI_MODEL", ""),
}
JAVA_APP_BASE_URL = os.environ.get("JAVA_APP_BASE_URL", "http://localhost:8080")
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "")
PORT = int(os.environ.get("PORT", "8000"))
