package com.officemanipulator.claude;

import com.officemanipulator.claude.dto.OrchestratorDesignResult;
import com.officemanipulator.claude.dto.OrchestratorDocumentEditResult;
import com.officemanipulator.claude.dto.OrchestratorPlanResult;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.domain.Job;

/**
 * Abstraction over the agent backend that drives document-manipulation jobs.
 * {@link ClaudeOrchestrator} is the production (legacy) implementation, driving
 * Claude directly via the tool-use loop. Future implementations (e.g. a Deep
 * Agents-based service) can be swapped in via {@code app.agent.provider}
 * without touching callers. See {@link DeepAgentOrchestrator}.
 */
public interface AgentOrchestrator {

    OrchestratorResult run(Job job) throws Exception;

    OrchestratorResult runEdit(Job job, String documentContext) throws Exception;

    String runFragmentEdit(Job job, String fragmentJson, String instruction);

    OrchestratorDesignResult runDesignDirection(Job job);

    OrchestratorDocumentEditResult runDocumentEdit(Job job, String currentText, String instruction);

    OrchestratorPlanResult runPlan(Job job);
}
