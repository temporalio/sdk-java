package io.temporal.toolregistry.testing;

import io.temporal.toolregistry.Provider;
import io.temporal.toolregistry.ToolDefinition;
import io.temporal.toolregistry.TurnResult;
import java.util.List;
import java.util.Map;

/**
 * A {@link Provider} that throws a {@link RuntimeException} after a fixed number of turns.
 *
 * <p>Use this to simulate a crash mid-loop for checkpoint recovery tests:
 *
 * <pre>{@code
 * // Crash after 2 turns; each "turn" delegates to an inner provider.
 * Provider inner = new MockProvider(...);
 * Provider crasher = new CrashAfterTurns(2, inner);
 * }</pre>
 */
public class CrashAfterTurns implements Provider {

  private final int n;
  private final Provider delegate;
  private int count = 0;

  /**
   * Creates a provider that throws after {@code n} successful turns.
   *
   * @param n number of turns to allow before crashing
   * @param delegate the underlying provider to delegate to
   */
  public CrashAfterTurns(int n, Provider delegate) {
    this.n = n;
    this.delegate = delegate;
  }

  /**
   * Creates a provider that throws after {@code n} successful turns, with no delegate (returns a
   * done result immediately until the crash).
   *
   * @param n number of turns to allow before crashing
   */
  public CrashAfterTurns(int n) {
    this(n, null);
  }

  @Override
  public TurnResult runTurn(List<Map<String, Object>> messages, List<ToolDefinition> tools)
      throws Exception {
    count++;
    if (count > n) {
      throw new RuntimeException("CrashAfterTurns: crashed after " + n + " turn(s)");
    }
    if (delegate != null) {
      return delegate.runTurn(messages, tools);
    }
    // No delegate: return done immediately.
    return new TurnResult(new java.util.ArrayList<>(), true);
  }
}
