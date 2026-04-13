package io.temporal.toolregistry.testing;

import java.util.Map;

/** Records a single tool dispatch call made through {@link FakeToolRegistry}. */
public final class DispatchCall {

  private final String name;
  private final Map<String, Object> input;

  DispatchCall(String name, Map<String, Object> input) {
    this.name = name;
    this.input = input;
  }

  /** The name of the tool that was called. */
  public String getName() {
    return name;
  }

  /** The input map passed to the tool. */
  public Map<String, Object> getInput() {
    return input;
  }

  @Override
  public String toString() {
    return "DispatchCall{name=" + name + ", input=" + input + "}";
  }
}
