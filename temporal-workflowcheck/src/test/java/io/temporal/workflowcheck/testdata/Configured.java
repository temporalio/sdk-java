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

package io.temporal.workflowcheck.testdata;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface Configured {
  @WorkflowMethod
  void configured();

  class ConfiguredImpl implements Configured {
    @Override
    public void configured() {
      // INVALID: Configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$SomeCalls
      //   * accessedMember: configuredInvalidFull()V
      new SomeCalls().configuredInvalidFull();

      // INVALID: Configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$SomeCalls
      //   * accessedMember: configuredInvalidALlButDescriptor()V
      new SomeCalls().configuredInvalidALlButDescriptor();

      // INVALID: Configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$SomeCalls
      //   * accessedMember: configuredInvalidClassAndMethod()V
      new SomeCalls().configuredInvalidClassAndMethod();

      // INVALID: Configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$SomeCalls
      //   * accessedMember: configuredInvalidJustName()V
      new SomeCalls().configuredInvalidJustName();

      // INVALID: Calls configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$SomeCalls
      //   * accessedMember: callsConfiguredInvalid()V
      //   * accessedCauseClass: io/temporal/workflowcheck/testdata/Configured$SomeCalls
      //   * accessedCauseMethod: configuredInvalidJustName()V
      new SomeCalls().callsConfiguredInvalid();

      // This overload is ok
      new SomeCalls().configuredInvalidOverload("");

      // INVALID: Configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$SomeCalls
      //   * accessedMember: configuredInvalidOverload(I)V
      new SomeCalls().configuredInvalidOverload(0);

      // spotless:off
      // INVALID: Configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$SomeInterface$SomeInterfaceImpl
      //   * accessedMember: configuredInvalidIface()V
      new SomeInterface.SomeInterfaceImpl().configuredInvalidIface();
      // spotless:on

      // INVALID: Configured invalid
      //   * class: io/temporal/workflowcheck/testdata/Configured$ConfiguredImpl
      //   * method: configured()V
      //   * accessedClass: io/temporal/workflowcheck/testdata/Configured$ConfiguredInvalidClass
      //   * accessedMember: someMethod()V
      ConfiguredInvalidClass.someMethod();
    }
  }

  class SomeCalls {
    void configuredInvalidFull() {}

    void configuredInvalidALlButDescriptor() {}

    void configuredInvalidClassAndMethod() {}

    void configuredInvalidJustName() {}

    void callsConfiguredInvalid() {
      configuredInvalidJustName();
    }

    void configuredInvalidOverload(String param) {}

    void configuredInvalidOverload(int param) {}
  }

  interface SomeInterface {
    void configuredInvalidIface();

    class SomeInterfaceImpl implements SomeInterface {
      @Override
      public void configuredInvalidIface() {}
    }
  }

  class ConfiguredInvalidClass {
    static void someMethod() {}
  }
}
