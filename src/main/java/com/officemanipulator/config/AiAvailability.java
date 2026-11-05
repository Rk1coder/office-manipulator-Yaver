package com.officemanipulator.config;

import com.officemanipulator.llm.LlmSelection;
import org.springframework.stereotype.Component;

/** Whether the default LLM key is configured and verified (env default or a Settings-page override);
 * false in air-gapped deployments without one. Checked live so the Settings page's "save" takes
 * effect immediately, without an app restart. A request carrying its own validated override is
 * available regardless — see {@link #enabled(LlmSelection)}. */
@Component
public class AiAvailability {
    private final LlmClientProvider provider;

    public AiAvailability(LlmClientProvider provider) {
        this.provider = provider;
    }

    public boolean enabled() {
        return provider.keyVerified();
    }

    public boolean enabled(LlmSelection override) {
        return provider.available(override);
    }
}
