/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.internal.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit rule that records sequence of strings through {@link #add(String)} call and checks it
 * against expected value (set through {@link #setExpected(String[])} after unit test execution.
 */
public final class Tracer implements TestRule {

  private final List<String> trace = new ArrayList<>();
  private List<String> expected = new ArrayList<>();

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        trace.clear();
        expected.clear();
        base.evaluate();
        if (expected != null) {
          assertExpected();
        }
      }
    };
  }

  /** Record string value. */
  public void add(String value) {
    trace.add(value);
  }

  public void addExpected(String value) {
    expected.add(value);
  }

  /** Set list of expected values. */
  public void setExpected(String... expected) {
    this.expected = Arrays.asList(expected);
  }

  /**
   * Assert that expected matches the trace. This method is called implicitly at the end of a unit
   * test. It can be called directly to evaluate a progress in the middle of a test.
   */
  public void assertExpected() {
    Assert.assertEquals(expected, trace);
  }

  @Override
  public String toString() {
    return "expected " + String.join(",", expected) + " trace " + String.join(",", trace);
  }
}
