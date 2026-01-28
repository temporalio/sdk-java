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
