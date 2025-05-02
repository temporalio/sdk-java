package io.temporal.workflow.shared;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;

/** Common set of Nexus Service interfaces for use in tests. */
public class TestNexusServices {
  @Service
  public interface TestNexusService1 {
    @Operation
    String operation(String input);
  }

  @Service
  public interface TestNexusService2 {
    @Operation
    Integer operation(Integer input);
  }

  @Service
  public interface TestNexusServiceVoid {
    @Operation
    Void operation();
  }

  @Service
  public interface TestNexusServiceVoidInput {
    @Operation
    String operation();
  }

  @Service
  public interface TestNexusServiceVoidReturn {
    @Operation
    Void operation(String input);
  }
}
