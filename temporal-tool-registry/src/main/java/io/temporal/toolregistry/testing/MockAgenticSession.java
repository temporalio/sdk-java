package io.temporal.toolregistry.testing;

import io.temporal.toolregistry.AgenticSession;
import io.temporal.toolregistry.Provider;
import io.temporal.toolregistry.ToolRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A no-op {@link AgenticSession} substitute for tests that need an activity to accept a session but
 * don't want to run an actual tool loop.
 *
 * <p>Instead of calling an LLM, {@link #runToolLoop} records the prompt and optionally returns
 * pre-canned results.
 *
 * <p>Example:
 *
 * <pre>{@code
 * MockAgenticSession mock = new MockAgenticSession();
 * mock.getResults().add(Map.of("description", "pre-seeded result"));
 * }</pre>
 */
public class MockAgenticSession extends AgenticSession {

  private String capturedPrompt;
  private final List<Map<String, Object>> mutableResults = new ArrayList<>();

  /**
   * Records the prompt and returns immediately without calling an LLM.
   *
   * <p>Results can be pre-seeded via {@link #getMutableResults()}.
   */
  @Override
  public void runToolLoop(Provider provider, ToolRegistry registry, String prompt) {
    this.capturedPrompt = prompt;
  }

  /** Returns the prompt that was passed to {@link #runToolLoop}, or {@code null} if not called. */
  public String getCapturedPrompt() {
    return capturedPrompt;
  }

  /**
   * Returns the mutable results list. Add entries here before running the session to simulate
   * pre-existing results.
   */
  public List<Map<String, Object>> getMutableResults() {
    return mutableResults;
  }

  /**
   * Returns the results list (mutable results + any added via {@link AgenticSession#addResult}).
   *
   * <p>Note: the returned list is a merged snapshot.
   */
  @Override
  public List<Map<String, Object>> getResults() {
    List<Map<String, Object>> merged = new ArrayList<>(mutableResults);
    merged.addAll(super.getResults());
    return Collections.unmodifiableList(merged);
  }
}
