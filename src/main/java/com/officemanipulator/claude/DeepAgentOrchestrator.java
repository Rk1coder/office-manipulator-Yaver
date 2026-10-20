package com.officemanipulator.claude;

import com.officemanipulator.claude.dto.OrchestratorDesignResult;
import com.officemanipulator.claude.dto.OrchestratorDocumentEditResult;
import com.officemanipulator.claude.dto.OrchestratorPlanResult;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.domain.Job;
import org.springframework.stereotype.Service;

/**
 * Experimental stub for the Deep Agents migration (branch
 * experiment/deep-agents-migration). Not yet implemented — this class exists
 * solely to prove that {@code app.agent.provider: deep-agents} wiring and
 * routing work end-to-end via
 * {@link com.officemanipulator.config.AgentOrchestratorConfiguration}.
 * No HTTP calls, no real agent logic yet; that lands in a later phase of the
 * migration together with the Python Deep Agents service itself.
 */
@Service
public class DeepAgentOrchestrator implements AgentOrchestrator {

    private static final String NOT_IMPLEMENTED =
            "DeepAgentOrchestrator is a routing stub only - not yet implemented. "
                    + "Set app.agent.provider=legacy to use ClaudeOrchestrator.";

    @Override
    public OrchestratorResult run(Job job) throws Exception {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public OrchestratorResult runEdit(Job job, String documentContext) throws Exception {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public String runFragmentEdit(Job job, String fragmentJson, String instruction) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public OrchestratorDesignResult runDesignDirection(Job job) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public OrchestratorDocumentEditResult runDocumentEdit(Job job, String currentText, String instruction) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public OrchestratorPlanResult runPlan(Job job) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
