import logging

from fastapi import FastAPI
from pydantic import BaseModel, SecretStr

from app.agent import run_docx_job

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("deep-agent-service")

app = FastAPI(title="Deep Agent Service (PoC)")


class RunRequest(BaseModel):
    jobId: str
    request: str
    sourceText: str = ""
    # Whitelisted provider/key/model chosen by the Java side (.env default or the user's override).
    llmProvider: str = ""
    llmApiKey: SecretStr = SecretStr("")
    llmModel: str = ""


class RunResponse(BaseModel):
    success: bool
    message: str
    inputTokens: int = 0
    outputTokens: int = 0


@app.post("/run", response_model=RunResponse)
def run(req: RunRequest) -> RunResponse:
    log.info("Running DOCX job %s", req.jobId)
    try:
        result = run_docx_job(req.jobId, req.request, req.sourceText,
                              req.llmProvider, req.llmApiKey.get_secret_value(), req.llmModel)
    except Exception as e:  # noqa: BLE001 - PoC: surface any failure to the caller, don't crash the service
        log.exception("Deep agent run failed for job %s", req.jobId)
        return RunResponse(success=False, message=f"Deep agent run failed: {e}")

    return RunResponse(
        success=result.success,
        message=result.message,
        inputTokens=result.input_tokens,
        outputTokens=result.output_tokens,
    )
