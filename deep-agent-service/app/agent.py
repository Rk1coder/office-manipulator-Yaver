import logging
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from dataclasses import dataclass

from deepagents import HarnessProfile, create_deep_agent, register_harness_profile
from deepagents.backends import StateBackend
from langchain_anthropic import ChatAnthropic
from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_openai import ChatOpenAI
from langgraph.errors import GraphRecursionError

from app.config import LLM_API_KEYS, LLM_MODELS, LLM_PROVIDER
from app.prompts import WORD_DOCUMENT_RULES
from app.tools import make_create_word_document_tool

# create_deep_agent always wires in ls/read_file/write_file/edit_file/delete/glob/grep
# (FilesystemMiddleware) and execute/task, even though this agent only needs
# create_word_document. Excluding them explicitly here -- instead of relying on the
# implicit default StateBackend (in-memory, not the host disk) to make them harmless --
# is the deliberate, documented security boundary this migration's plan calls for
# ("agent'a sınırsız dosya sistemi veya shell erişimi verme"). Registered once at import
# time for every whitelisted provider, keyed by the provider name deepagents resolves from a
# pre-built BaseChatModel instance (its ls_provider; see register_harness_profile docs) --
# a provider missing here would silently get the default filesystem/shell tools back.
EXCLUDED_TOOLS = frozenset({"ls", "read_file", "write_file", "edit_file", "delete", "glob", "grep", "execute", "task"})

# Closed whitelist: provider id -> (deepagents profile key, model factory). Endpoints are each
# library's fixed default; a request can never supply a URL. Adding a provider = a new entry here.
MODEL_FACTORIES = {
    "anthropic": ("anthropic", lambda model, key: ChatAnthropic(model=model, api_key=key)),
    "gemini": ("google_genai", lambda model, key: ChatGoogleGenerativeAI(model=model, google_api_key=key)),
    "openai": ("openai", lambda model, key: ChatOpenAI(model=model, api_key=key)),
}
for _profile_key, _ in MODEL_FACTORIES.values():
    register_harness_profile(_profile_key, HarnessProfile(excluded_tools=EXCLUDED_TOOLS))


def build_model(provider: str, api_key: str, model: str):
    if provider not in MODEL_FACTORIES:
        raise ValueError(f"Unsupported LLM provider {provider!r}; allowed: {', '.join(MODEL_FACTORIES)}")
    if not api_key or not model:
        raise ValueError(f"No API key/model configured for LLM provider {provider!r}")
    return MODEL_FACTORIES[provider][1](model, api_key)

# Caps the agent's total tool-call/LLM turns per run. The default (LangGraph's own,
# ~25) is far more than a single-tool DOCX agent should ever need; a low explicit cap
# bounds cost and turnaround on a script the agent can't get right.
RECURSION_LIMIT = 8

# Wall-clock cap on the whole agent.invoke() call, kept below the Java-side RestClient
# read timeout (7 minutes) so this service reports its own timeout error back to Java
# cleanly instead of Java's HTTP client timing out the connection first. Raised from an
# initial 240s guess after a real benchmark run (evaluation/results/) showed a legitimate
# multi-section document being cut off here while still making progress -- the legacy
# agent has no equivalent cap and took up to 341s on its own slowest case in the same
# run, so 240s was an unfairly tight, unvalidated number for Deep Agents specifically.
INVOKE_TIMEOUT_SECONDS = 360

_executor = ThreadPoolExecutor(max_workers=4)
log = logging.getLogger("deep-agent-service.agent")


@dataclass
class DocxRunResult:
    success: bool
    message: str
    input_tokens: int
    output_tokens: int


def _build_user_message(request: str, source_text: str) -> str:
    message = request
    if source_text:
        message += "\n\n--- SOURCE MATERIAL ---\n" + source_text
    return message


def _extract_usage_and_last_tool_message(messages):
    input_tokens = 0
    output_tokens = 0
    last_tool_message = None
    for msg in messages:
        usage = getattr(msg, "usage_metadata", None) or (
            msg.get("usage_metadata") if isinstance(msg, dict) else None
        )
        if usage:
            input_tokens += usage.get("input_tokens", 0) or 0
            output_tokens += usage.get("output_tokens", 0) or 0
        role = getattr(msg, "type", None) or (msg.get("role") if isinstance(msg, dict) else None)
        if role == "tool":
            content = getattr(msg, "content", None) or (msg.get("content") if isinstance(msg, dict) else None)
            if content:
                last_tool_message = content
    return input_tokens, output_tokens, last_tool_message


def run_docx_job(job_id: str, request: str, source_text: str,
                 provider: str = "", api_key: str = "", model_name: str = "") -> DocxRunResult:
    provider = provider or LLM_PROVIDER
    model = build_model(provider, api_key or LLM_API_KEYS.get(provider, ""), model_name or LLM_MODELS.get(provider, ""))
    tool = make_create_word_document_tool(job_id)
    agent = create_deep_agent(
        tools=[tool],
        system_prompt=WORD_DOCUMENT_RULES,
        model=model,
        backend=StateBackend(),
    )

    user_message = _build_user_message(request, source_text)
    started = time.monotonic()
    future = _executor.submit(
        agent.invoke,
        {"messages": [{"role": "user", "content": user_message}]},
        config={"recursion_limit": RECURSION_LIMIT},
    )
    try:
        result = future.result(timeout=INVOKE_TIMEOUT_SECONDS)
    except FutureTimeoutError:
        log.warning("job %s timed out after %.1fs (limit %ss) -- can't tell from here whether it "
                    "was stuck or just slow; the thread keeps running detached in the background.",
                    job_id, time.monotonic() - started, INVOKE_TIMEOUT_SECONDS)
        return DocxRunResult(
            success=False,
            message=f"Deep agent run exceeded the {INVOKE_TIMEOUT_SECONDS}s timeout.",
            input_tokens=0,
            output_tokens=0,
        )
    except GraphRecursionError:
        log.warning("job %s hit the %s-turn recursion limit after %.1fs.",
                    job_id, RECURSION_LIMIT, time.monotonic() - started)
        return DocxRunResult(
            success=False,
            message=f"Deep agent exceeded the maximum of {RECURSION_LIMIT} turns without a successful result.",
            input_tokens=0,
            output_tokens=0,
        )

    elapsed = time.monotonic() - started
    messages = result.get("messages", [])
    input_tokens, output_tokens, last_tool_message = _extract_usage_and_last_tool_message(messages)
    log.info("job %s finished in %.1fs across %d messages (input_tokens=%d, output_tokens=%d).",
              job_id, elapsed, len(messages), input_tokens, output_tokens)

    if last_tool_message is None:
        return DocxRunResult(
            success=False,
            message="Deep agent did not call create_word_document.",
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )

    success = not str(last_tool_message).startswith("Error:")
    return DocxRunResult(
        success=success,
        message=str(last_tool_message),
        input_tokens=input_tokens,
        output_tokens=output_tokens,
    )
