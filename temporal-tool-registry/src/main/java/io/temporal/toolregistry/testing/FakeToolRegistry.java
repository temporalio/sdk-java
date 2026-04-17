package io.temporal.toolregistry.testing;

import io.temporal.toolregistry.ToolRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A {@link ToolRegistry} subclass that records every dispatch call.
 *
 * <p>Use this in tests to verify which tools were called and with what inputs:
 *
 * <pre>{@code
 * FakeToolRegistry fake = new FakeToolRegistry();
 * fake.register(myDef, input -> "ok");
 *
 * // ... run the loop ...
 *
 * assertEquals(1, fake.getCalls().size());
 * assertEquals("my_tool", fake.getCalls().get(0).getName());
 * }</pre>
 */
public class FakeToolRegistry extends ToolRegistry {

  private final List<DispatchCall> calls = new ArrayList<>();

  /** Dispatches to the registered handler and records the call. */
  @Override
  public String dispatch(String name, Map<String, Object> input) throws Exception {
    calls.add(new DispatchCall(name, input));
    return super.dispatch(name, input);
  }

  /** Returns all dispatch calls recorded so far, in order. */
  public List<DispatchCall> getCalls() {
    return Collections.unmodifiableList(calls);
  }

  /** Clears the recorded call history. */
  public void clearCalls() {
    calls.clear();
  }
}
