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

package com.uber.cadence.internal.common;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/**
 * Do not reference directly by the application level code. Use {@link
 * com.uber.cadence.workflow.Workflow#wrap(Exception)} inside a workflow code and {@link
 * com.uber.cadence.activity.Activity#wrap(Exception)} inside an activity code instead.
 */
public final class CheckedExceptionWrapper extends RuntimeException {

  private static final Field causeField;

  static {
    try {
      causeField = Throwable.class.getDeclaredField("cause");
      causeField.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException("unexpected", e);
    }
  }

  /**
   * Returns CheckedExceptionWrapper if e is checked exception. If there is a need to return a
   * checked exception from an activity or workflow implementation throw a wrapped exception it
   * using this method. The library code will unwrap it automatically when propagating exception to
   * the caller.
   *
   * <pre>
   * try {
   *     return someCall();
   * } catch (Exception e) {
   *     throw CheckedExceptionWrapper.wrap(e);
   * }
   * </pre>
   */
  public static RuntimeException wrap(Throwable e) {
    // Errors are expected to propagate without any handling.
    if (e instanceof Error) {
      throw (Error) e;
    }
    if (e instanceof InvocationTargetException) {
      return wrap(e.getCause());
    }
    if (e instanceof RuntimeException) {
      return (RuntimeException) e;
    }
    return new CheckedExceptionWrapper((Exception) e);
  }

  /**
   * Removes CheckedException wrapper from the whole chain of Exceptions. Assumes that wrapper
   * always has a cause which cannot be a wrapper.
   */
  public static Exception unwrap(Throwable e) {
    Throwable head = e;
    if (head instanceof CheckedExceptionWrapper) {
      head = head.getCause();
    }
    Throwable tail = head;
    Throwable current = tail.getCause();
    while (current != null) {
      if (current instanceof CheckedExceptionWrapper) {
        current = current.getCause();
        setThrowableCause(tail, current);
      }
      tail = current;
      current = tail.getCause();
    }
    if (head instanceof Error) {
      // Error should be propagated without any handling.
      throw (Error) head;
    }
    return (Exception) head;
  }

  /**
   * Throwable.initCause throws IllegalStateException if cause is already set. This method uses
   * reflection to set it directly.
   */
  private static void setThrowableCause(Throwable throwable, Throwable cause) {
    try {
      causeField.set(throwable, cause);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("unexpected", e);
    }
  }

  private CheckedExceptionWrapper(Exception e) {
    super(e);
  }
}
