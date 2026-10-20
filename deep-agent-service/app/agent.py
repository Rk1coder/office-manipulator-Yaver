from dataclasses import dataclass

from deepagents import create_deep_agent
from langchain_anthropic import ChatAnthropic

from app.config import ANTHROPIC_API_KEY, CLAUDE_MODEL
from app.prompts import WORD_DOCUMENT_RULES
from app.tools import make_create_word_document_tool


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


def run_docx_job(job_id: str, request: str, source_text: str) -> DocxRunResult:
    model = ChatAnthropic(model=CLAUDE_MODEL, api_key=ANTHROPIC_API_KEY)
    tool = make_create_word_document_tool(job_id)
    agent = create_deep_agent(tools=[tool], system_prompt=WORD_DOCUMENT_RULES, model=model)

    user_message = _build_user_message(request, source_text)
    result = agent.invoke({"messages": [{"role": "user", "content": user_message}]})

    messages = result.get("messages", [])
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
