package org.isha.resumesearch.llm;

/** The LLM call a request depends on failed (bad/missing API key, endpoint down, unparseable reply) -
 *  surfaced to the client as a clear error rather than a NullPointerException further downstream. */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) {
        super(message);
    }
}
