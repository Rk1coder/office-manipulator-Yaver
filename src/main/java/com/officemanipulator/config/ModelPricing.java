package com.officemanipulator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;

/** Standard Anthropic API rates verified 2026-10-15; explicit settings override this table. */
@Component
public class ModelPricing {
    private static final Map<String, String[]> KNOWN = Map.of(
            "claude-sonnet-4-6", new String[]{"3.00", "15.00"},
            "claude-sonnet-5", new String[]{"2.00", "10.00"});
    private final String model;
    private final BigDecimal input;
    private final BigDecimal output;
    public ModelPricing(@Value("${app.claude.model}") String model,
                        @Value("${app.claude.input-price-per-million:}") String inputOverride,
                        @Value("${app.claude.output-price-per-million:}") String outputOverride) {
        this.model = model;
        String[] prices = KNOWN.get(model);
        if ((inputOverride.isBlank() || outputOverride.isBlank()) && prices == null)
            throw new IllegalArgumentException("Configure input and output prices for model " + model);
        input = new BigDecimal(inputOverride.isBlank() ? prices[0] : inputOverride);
        output = new BigDecimal(outputOverride.isBlank() ? prices[1] : outputOverride);
        if (input.signum() <= 0 || output.signum() <= 0)
            throw new IllegalArgumentException("Model prices must be positive");
    }
    public String model() { return model; }
    public BigDecimal input() { return input; }
    public BigDecimal output() { return output; }

    /** Standard rate for a known model, or null — used when the Settings page overrides the
     * active model away from the one {@code app.claude.model} was configured for at startup. */
    public static BigDecimal[] priceFor(String model) {
        String[] p = KNOWN.get(model);
        return p == null ? null : new BigDecimal[]{new BigDecimal(p[0]), new BigDecimal(p[1])};
    }
}
