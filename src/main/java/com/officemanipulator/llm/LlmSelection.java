package com.officemanipulator.llm;

/**
 * The provider/key/model one LLM call runs with. {@code requestKey} is true when the API key
 * came from the caller's own request (not .env/Settings): such calls are billed to the caller's
 * key, so they skip the server-side budget reservation.
 */
public record LlmSelection(LlmProvider provider, String apiKey, String model, boolean requestKey) {

    /** Never print the key — this record ends up in logs and exception messages. */
    @Override
    public String toString() {
        return "LlmSelection[provider=" + provider.id() + ", model=" + model + ", requestKey=" + requestKey + "]";
    }
}
