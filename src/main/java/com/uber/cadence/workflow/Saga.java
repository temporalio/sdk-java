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

package com.uber.cadence.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * This class implements the logic to execute <a
 * href="https://en.wikipedia.org/wiki/Compensating_transaction">compensation operations</a> that is
 * often required in Saga applications. The following is a skeleton to show of how it is supposed to
 * be used in workflow code:
 *
 * <pre><code>
 * Saga saga = new Saga(options);
 * try {
 *   String r = activity.foo();
 *   saga.addCompensation(activity::cleanupFoo, arg2, r);
 *   Promise<String> r2 = Async.function(activity::bar);
 *   r2.thenApply(r->saga.addCompensation(activity.cleanupBar(r));
 *   ...
 *   useR2(r2.get());
 * } catch (Exception e) {
 *    saga.compensate();
 *    // Other error handling if needed.
 * }
 * </code></pre>
 */
public final class Saga {
  private final Options options;
  private final List<Functions.Func<Promise>> compensationOps = new ArrayList<>();

  public static final class Options {
    private final boolean parallelCompensation;
    private final boolean continueWithError;

    private Options(boolean parallelCompensation, boolean continueWithError) {
      this.parallelCompensation = parallelCompensation;
      this.continueWithError = continueWithError;
    }

    public static final class Builder {
      private boolean parallelCompensation = true;
      private boolean continueWithError = true;

      /**
       * This decides if the compensation operations are run in parallel. If parallelCompensation is
       * false, then the compensation operations will be run the reverse order as they are added.
       *
       * @param parallelCompensation
       * @return option builder
       */
      public Builder setParallelCompensation(boolean parallelCompensation) {
        this.parallelCompensation = parallelCompensation;
        return this;
      }

      /**
       * continueWithError gives user the option to bail out of compensation operations if exception
       * is thrown while running them. This is useful only when parallelCompensation is false. If
       * parallel compensation is set to true, then all the compensation operations will be fired no
       * matter what and caller will receive exceptions back if there's any.
       *
       * @param continueWithError whether to proceed with the next compensation operation if the
       *     previous throws exception. This only applies to sequential compensation.
       * @return option builder
       */
      public Builder setContinueWithError(boolean continueWithError) {
        this.continueWithError = continueWithError;
        return this;
      }

      public Options build() {
        return new Options(parallelCompensation, continueWithError);
      }
    }
  }

  public static class CompensationException extends RuntimeException {
    public CompensationException(Throwable cause) {
      super("Exception from saga compensate", cause);
    }
  }

  public Saga(Options options) {
    this.options = options;
  }

  public void compensate() {
    if (options.parallelCompensation) {
      List<Promise> results = new ArrayList<>();
      for (Functions.Func<Promise> f : compensationOps) {
        results.add(f.apply());
      }

      CompensationException sagaException = null;
      for (Promise p : results) {
        try {
          p.get();
        } catch (Exception e) {
          if (sagaException == null) {
            sagaException = new CompensationException(e);
          } else {
            sagaException.addSuppressed(e);
          }
        }
      }

      if (sagaException != null) {
        throw sagaException;
      }
    } else {
      for (int i = compensationOps.size() - 1; i >= 0; i--) {
        Functions.Func<Promise> f = compensationOps.get(i);
        try {
          Promise result = f.apply();
          result.get();
        } catch (Exception e) {
          if (!options.continueWithError) {
            throw e;
          }
        }
      }
    }
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   */
  public void addCompensation(Functions.Proc operation) {
    compensationOps.add(() -> Async.procedure(operation));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   */
  public <A1> void addCompensation(Functions.Proc1<A1> operation, A1 arg1) {
    compensationOps.add(() -> Async.procedure(operation, arg1));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   */
  public <A1, A2> void addCompensation(Functions.Proc2<A1, A2> operation, A1 arg1, A2 arg2) {
    compensationOps.add(() -> Async.procedure(operation, arg1, arg2));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   */
  public <A1, A2, A3> void addCompensation(
      Functions.Proc3<A1, A2, A3> operation, A1 arg1, A2 arg2, A3 arg3) {
    compensationOps.add(() -> Async.procedure(operation, arg1, arg2, arg3));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   * @param arg4 fourth operation function parameter
   */
  public <A1, A2, A3, A4> void addCompensation(
      Functions.Proc4<A1, A2, A3, A4> operation, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    compensationOps.add(() -> Async.procedure(operation, arg1, arg2, arg3, arg4));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   * @param arg4 fourth operation function parameter
   * @param arg5 fifth operation function parameter
   */
  public <A1, A2, A3, A4, A5> void addCompensation(
      Functions.Proc5<A1, A2, A3, A4, A5> operation, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    compensationOps.add(() -> Async.procedure(operation, arg1, arg2, arg3, arg4, arg5));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   * @param arg4 fourth operation function parameter
   * @param arg5 fifth operation function parameter
   * @param arg6 sixth operation function parameter
   */
  public <A1, A2, A3, A4, A5, A6> void addCompensation(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> operation,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    compensationOps.add(() -> Async.procedure(operation, arg1, arg2, arg3, arg4, arg5, arg6));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   */
  public void addCompensation(Functions.Func<?> operation) {
    compensationOps.add(() -> Async.function(operation));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   */
  public <A1> void addCompensation(Functions.Func1<A1, ?> operation, A1 arg1) {
    compensationOps.add(() -> Async.function(operation, arg1));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   */
  public <A1, A2> void addCompensation(Functions.Func2<A1, A2, ?> operation, A1 arg1, A2 arg2) {
    compensationOps.add(() -> Async.function(operation, arg1, arg2));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   */
  public <A1, A2, A3> void addCompensation(
      Functions.Func3<A1, A2, A3, ?> operation, A1 arg1, A2 arg2, A3 arg3) {
    compensationOps.add(() -> Async.function(operation, arg1, arg2, arg3));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   * @param arg4 fourth operation function parameter
   */
  public <A1, A2, A3, A4> void addCompensation(
      Functions.Func4<A1, A2, A3, A4, ?> operation, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    compensationOps.add(() -> Async.function(operation, arg1, arg2, arg3, arg4));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   * @param arg4 fourth operation function parameter
   * @param arg5 fifth operation function parameter
   */
  public <A1, A2, A3, A4, A5> void addCompensation(
      Functions.Func5<A1, A2, A3, A4, A5, ?> operation,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    compensationOps.add(() -> Async.function(operation, arg1, arg2, arg3, arg4, arg5));
  }

  /**
   * Add compensation operation for saga.
   *
   * @param operation to be executed during compensation.
   * @param arg1 first operation function parameter
   * @param arg2 second operation function parameter
   * @param arg3 third operation function parameter
   * @param arg4 fourth operation function parameter
   * @param arg5 fifth operation function parameter
   * @param arg6 sixth operation function parameter
   */
  public <A1, A2, A3, A4, A5, A6> void addCompensation(
      Functions.Func6<A1, A2, A3, A4, A5, A6, ?> operation,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    compensationOps.add(() -> Async.function(operation, arg1, arg2, arg3, arg4, arg5, arg6));
  }
}
