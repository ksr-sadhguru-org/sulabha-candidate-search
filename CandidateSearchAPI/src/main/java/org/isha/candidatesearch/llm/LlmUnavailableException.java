package org.isha.candidatesearch.llm;

/** An LLM call a request depends on failed (bad key, endpoint down, unreadable reply) - shown to the user as-is. */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) {
        super(message);
    }
}
