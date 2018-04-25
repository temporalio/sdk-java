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

import java.io.Serializable;

public class Functions {

  @FunctionalInterface
  public interface Func<R> extends Serializable {
    R apply();
  }

  @FunctionalInterface
  public interface Func1<T1, R> extends Serializable {
    R apply(T1 t1);
  }

  @FunctionalInterface
  public interface Func2<T1, T2, R> extends Serializable {
    R apply(T1 t1, T2 t2);
  }

  @FunctionalInterface
  public interface Func3<T1, T2, T3, R> extends Serializable {
    R apply(T1 t1, T2 t2, T3 t3);
  }

  @FunctionalInterface
  public interface Func4<T1, T2, T3, T4, R> extends Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4);
  }

  @FunctionalInterface
  public interface Func5<T1, T2, T3, T4, T5, R> extends Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5);
  }

  @FunctionalInterface
  public interface Func6<T1, T2, T3, T4, T5, T6, R> extends Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6);
  }

  @FunctionalInterface
  public interface Proc extends Serializable {
    void apply();
  }

  @FunctionalInterface
  public interface Proc1<T1> extends Serializable {
    void apply(T1 t1);
  }

  @FunctionalInterface
  public interface Proc2<T1, T2> extends Serializable {
    void apply(T1 t1, T2 t2);
  }

  @FunctionalInterface
  public interface Proc3<T1, T2, T3> extends Serializable {
    void apply(T1 t1, T2 t2, T3 t3);
  }

  @FunctionalInterface
  public interface Proc4<T1, T2, T3, T4> extends Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4);
  }

  @FunctionalInterface
  public interface Proc5<T1, T2, T3, T4, T5> extends Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5);
  }

  @FunctionalInterface
  public interface Proc6<T1, T2, T3, T4, T5, T6> extends Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6);
  }
}
