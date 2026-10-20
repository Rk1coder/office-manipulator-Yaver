import httpx

from app.config import INTERNAL_TOKEN, JAVA_APP_BASE_URL


def make_create_word_document_tool(job_id: str):
    """Builds the create_word_document tool bound to one job id, so the agent
    doesn't need to (and can't accidentally) pass the wrong job id itself."""

    def create_word_document(script: str) -> str:
        """Submit a complete docx-npm Node.js script to generate the Word document.
        The script must write its output using the OUTPUT_PATH environment variable
        convention described in your instructions. Returns a success message, or an
        error message describing what went wrong -- fix the script and call this tool
        again if it fails."""
        try:
            response = httpx.post(
                f"{JAVA_APP_BASE_URL}/internal/agent-tools/create-word",
                json={"jobId": job_id, "script": script},
                headers={"X-Internal-Token": INTERNAL_TOKEN},
                timeout=90.0,
            )
        except httpx.HTTPError as e:
            return f"Error: could not reach the document generation service: {e}"

        if response.status_code != 200:
            return f"Error: document generation service returned HTTP {response.status_code}: {response.text}"

        body = response.json()
        if not body.get("success"):
            return f"Error: {body.get('message', 'unknown error')}"
        return body.get("message", "Word document created successfully.")

    return create_word_document
