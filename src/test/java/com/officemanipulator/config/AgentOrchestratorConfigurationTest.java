package com.officemanipulator.config;

import com.officemanipulator.claude.AgentOrchestrator;
import com.officemanipulator.claude.ClaudeOrchestrator;
import com.officemanipulator.claude.DeepAgentOrchestrator;
import com.officemanipulator.service.ApiBillingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

/** Verifies app.agent.provider picks the right AgentOrchestrator bean, without booting the full app context. */
class AgentOrchestratorConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("app.claude.max-tokens=4096", "app.claude.max-tool-turns=10")
            .withUserConfiguration(StubOrchestratorBeans.class, AgentOrchestratorConfiguration.class);

    @Test
    void defaultsToLegacyWhenProviderUnset() {
        contextRunner.run(context ->
                assertInstanceOf(ClaudeOrchestrator.class, context.getBean(AgentOrchestrator.class)));
    }

    @Test
    void legacyProviderSelectsClaudeOrchestrator() {
        contextRunner.withPropertyValues("app.agent.provider=legacy").run(context ->
                assertInstanceOf(ClaudeOrchestrator.class, context.getBean(AgentOrchestrator.class)));
    }

    @Test
    void deepAgentsProviderSelectsDeepAgentOrchestrator() {
        contextRunner.withPropertyValues("app.agent.provider=deep-agents").run(context ->
                assertInstanceOf(DeepAgentOrchestrator.class, context.getBean(AgentOrchestrator.class)));
    }

    @Configuration
    static class StubOrchestratorBeans {
        @Bean
        ClaudeOrchestrator claudeOrchestrator() {
            return mock(ClaudeOrchestrator.class);
        }

        @Bean
        DeepAgentOrchestrator deepAgentOrchestrator() {
            return mock(DeepAgentOrchestrator.class);
        }

        @Bean
        ApiBillingService apiBillingService() {
            return mock(ApiBillingService.class);
        }

        @Bean
        ModelPricing modelPricing() {
            return new ModelPricing("claude-sonnet-5", "", "");
        }
    }
}
