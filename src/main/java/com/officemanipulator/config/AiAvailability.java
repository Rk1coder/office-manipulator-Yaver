package com.officemanipulator.config;

import org.springframework.stereotype.Component;

/** Whether a Claude API key is currently configured (env default or a Settings-page override);
 * false in air-gapped deployments without one. Checked live so the Settings page's "save" takes
 * effect immediately, without an app restart. */
@Component
public class AiAvailability {
    private final ClaudeClientProvider provider;

    public AiAvailability(ClaudeClientProvider provider) {
        this.provider = provider;
    }

    public boolean enabled() {
        return provider.keyVerified();
    }
}
