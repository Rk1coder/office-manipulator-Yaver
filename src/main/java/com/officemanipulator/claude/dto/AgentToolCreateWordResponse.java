package com.officemanipulator.claude.dto;

/** Response body for the internal /internal/agent-tools/create-word endpoint.
 * success=false with HTTP 200 signals a retryable tool error (bad script) back to the
 * Deep Agents tool-calling loop, distinct from a 401/404 transport failure. */
public record AgentToolCreateWordResponse(boolean success, String message) {
}
