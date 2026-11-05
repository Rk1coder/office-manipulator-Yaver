package com.officemanipulator.config;

import com.officemanipulator.llm.LlmProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Standard Anthropic API rates verified 2026-10-15; explicit settings override this table.
 * Other providers' models have no built-in rate: set LLM_INPUT/OUTPUT_PRICE_PER_MILLION for them. */
@Component
public class ModelPricing {
    private static final Map<String, String[]> KNOWN = Map.of(
            "claude-sonnet-4-6", new String[]{"3.00", "15.00"},
            "claude-sonnet-5", new String[]{"2.00", "10.00"});
    private final String model;
    private final BigDecimal input;
    private final BigDecimal output;

    /** Prices the .env default model of the configured {@code LLM_PROVIDER}. */
    @Autowired
    public ModelPricing(@Value("${app.llm.provider:anthropic}") String provider,
                        @Value("${app.llm.anthropic.model:}") String anthropicModel,
                        @Value("${app.llm.gemini.model:}") String geminiModel,
                        @Value("${app.llm.openai.model:}") String openaiModel,
                        @Value("${app.llm.input-price-per-million:}") String inputOverride,
                        @Value("${app.llm.output-price-per-million:}") String outputOverride) {
        this(switch (LlmProvider.parse(provider)) {
            case ANTHROPIC -> anthropicModel;
            case GEMINI -> geminiModel;
            case OPENAI -> openaiModel;
        }, inputOverride, outputOverride);
    }

    public ModelPricing(String model, String inputOverride, String outputOverride) {
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

    /** Whether {@link #cost} can price this model (configured default or a known rate). */
    public boolean knows(String candidate) { return candidate != null && (candidate.equals(model) || KNOWN.containsKey(candidate)); }

    /** USD cost of a call; zero for a model with no known rate (only reachable with a caller's own key). */
    public BigDecimal cost(String forModel, long inputTokens, long outputTokens) {
        BigDecimal[] rates = forModel != null && forModel.equals(model) ? new BigDecimal[]{input, output} : priceFor(forModel);
        if (rates == null) return BigDecimal.ZERO.setScale(6);
        return rates[0].multiply(BigDecimal.valueOf(inputTokens)).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)
                .add(rates[1].multiply(BigDecimal.valueOf(outputTokens)).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP));
    }

    /** Standard rate for a known model, or null — used when the Settings page overrides the
     * active model away from the one configured at startup. */
    public static BigDecimal[] priceFor(String model) {
        String[] p = model == null ? null : KNOWN.get(model);
        return p == null ? null : new BigDecimal[]{new BigDecimal(p[0]), new BigDecimal(p[1])};
    }
}
