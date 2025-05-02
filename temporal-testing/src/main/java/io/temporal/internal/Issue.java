package io.temporal.internal;

/**
 * Test annotated with {@link Issue} covers a specific problem or edge case and is usually crafted
 * carefully to hit the right conditions to reproduce the problem. Please specify a link to a ticket
 * that describes what this test intends to cover.
 */
public @interface Issue {
  /**
   * @return Link to a ticket that describes what this test is intended to cover.
   */
  String value();
}
