package com.officemanipulator.config;

import com.officemanipulator.claude.AgentOrchestrator;
import com.officemanipulator.claude.ClaudeOrchestrator;
import com.officemanipulator.claude.DeepAgentOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects which {@link AgentOrchestrator} implementation is primary, based on
 * {@code app.agent.provider}. Both implementations remain Spring-managed beans
 * at all times; this only controls which one satisfies unqualified
 * {@code AgentOrchestrator} injection sites. Switch back to legacy via
 * {@code app.agent.provider: legacy} (the default) with no code changes.
 */
@Configuration
public class AgentOrchestratorConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.agent.provider", havingValue = "legacy", matchIfMissing = true)
    public AgentOrchestrator legacyAgentOrchestrator(ClaudeOrchestrator claudeOrchestrator) {
        return claudeOrchestrator;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.agent.provider", havingValue = "deep-agents")
    public AgentOrchestrator deepAgentOrchestratorPrimary(DeepAgentOrchestrator deepAgentOrchestrator) {
        return deepAgentOrchestrator;
    }
}
