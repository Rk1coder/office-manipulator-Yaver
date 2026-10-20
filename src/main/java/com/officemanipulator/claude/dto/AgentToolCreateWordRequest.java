package com.officemanipulator.claude.dto;

import java.util.UUID;

/** Request body for the internal /internal/agent-tools/create-word endpoint,
 * called by the Deep Agents Python service's create_word_document tool. */
public record AgentToolCreateWordRequest(UUID jobId, String script) {
}
