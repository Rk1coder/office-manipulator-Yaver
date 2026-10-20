import httpx
import pytest

from app import tools


class _FakeResponse:
    def __init__(self, status_code, json_body=None, text=""):
        self.status_code = status_code
        self._json_body = json_body or {}
        self.text = text

    def json(self):
        return self._json_body


def test_create_word_document_success(monkeypatch):
    def fake_post(url, json, headers, timeout):
        assert url.endswith("/internal/agent-tools/create-word")
        assert json["script"] == "const x = 1;"
        assert headers["X-Internal-Token"] == tools.INTERNAL_TOKEN
        return _FakeResponse(200, {"success": True, "message": "Word document created successfully."})

    monkeypatch.setattr(httpx, "post", fake_post)
    tool = tools.make_create_word_document_tool("job-1")
    assert tool("const x = 1;") == "Word document created successfully."


def test_create_word_document_tool_error_is_retryable(monkeypatch):
    def fake_post(url, json, headers, timeout):
        return _FakeResponse(200, {"success": False, "message": "File is empty"})

    monkeypatch.setattr(httpx, "post", fake_post)
    tool = tools.make_create_word_document_tool("job-1")
    result = tool("broken script")
    assert result.startswith("Error:")
    assert "File is empty" in result


def test_create_word_document_transport_failure(monkeypatch):
    def fake_post(url, json, headers, timeout):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "post", fake_post)
    tool = tools.make_create_word_document_tool("job-1")
    result = tool("const x = 1;")
    assert result.startswith("Error:")
