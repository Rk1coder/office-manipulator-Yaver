package com.officemanipulator.claude.dto;

import java.math.BigDecimal;

/** Result of the Yaver in-editor document-edit call: rewritten text plus structured formatting
 * instructions, as JSON matching the submit_document_edit tool schema. See
 * ClaudeOrchestrator.runDocumentEdit. */
public record OrchestratorDocumentEditResult(
        String editJson,
        int inputTokens,
        int outputTokens,
        BigDecimal cost
) {}
