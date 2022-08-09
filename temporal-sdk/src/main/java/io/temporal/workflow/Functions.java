/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.workflow;

import java.io.Serializable;

public final class Functions {
  private Functions() {}

  public interface TemporalFunctionalInterfaceMarker {}

  @FunctionalInterface
  public interface Func<R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply();
  }

  @FunctionalInterface
  public interface Func1<T1, R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1);
  }

  @FunctionalInterface
  public interface Func2<T1, T2, R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2);
  }

  @FunctionalInterface
  public interface Func3<T1, T2, T3, R> extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3);
  }

  @FunctionalInterface
  public interface Func4<T1, T2, T3, T4, R>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4);
  }

  @FunctionalInterface
  public interface Func5<T1, T2, T3, T4, T5, R>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5);
  }

  @FunctionalInterface
  public interface Func6<T1, T2, T3, T4, T5, T6, R>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    R apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6);
  }

  @FunctionalInterface
  public interface Proc extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply();
  }

  @FunctionalInterface
  public interface Proc1<T1> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1);
  }

  @FunctionalInterface
  public interface Proc2<T1, T2> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2);
  }

  @FunctionalInterface
  public interface Proc3<T1, T2, T3> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3);
  }

  @FunctionalInterface
  public interface Proc4<T1, T2, T3, T4> extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4);
  }

  @FunctionalInterface
  public interface Proc5<T1, T2, T3, T4, T5>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5);
  }

  @FunctionalInterface
  public interface Proc6<T1, T2, T3, T4, T5, T6>
      extends TemporalFunctionalInterfaceMarker, Serializable {
    void apply(T1 t1, T2 t2, T3 t3, T4 t4, T5 t5, T6 t6);
  }
}
