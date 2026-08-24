package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.TemporalNexusClient;
import io.temporal.nexus.TemporalOperation;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.nexus.TemporalOperationResult;
import io.temporal.nexus.TemporalOperationStartContext;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/** End-to-end coverage of the {@link TemporalOperation} sugar. */
public class TemporalOperationAnnotationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SugarCaller.class, MixedCaller.class, SugarTargetWorkflowImpl.class)
          .setNexusServiceImplementation(
              new SugarServiceImpl(), new SyncSugarServiceImpl(), new MixedServiceImpl())
          .build();

  @Test
  public void workflowRunSugar_endToEnd() {
    TestWorkflows.TestWorkflow1 stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    Assert.assertEquals("workflow:wf-input", stub.execute("wf-input"));
  }

  @Test
  public void syncSugar_endToEnd() {
    SyncCallerWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SyncCallerWorkflow.class);
    Assert.assertEquals("sync:hi", stub.execute("hi"));
  }

  @Test
  public void mixedClass_bothOperationsReachable() {
    MixedCallerWorkflow stub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(MixedCallerWorkflow.class);
    Assert.assertEquals("workflow:mixed-input|legacy:legacy-input", stub.execute("mixed-input"));
  }

  // ----- Caller workflows -----

  @WorkflowInterface
  public interface SyncCallerWorkflow {
    @WorkflowMethod
    String execute(String arg);
  }

  @WorkflowInterface
  public interface MixedCallerWorkflow {
    @WorkflowMethod
    String execute(String arg);
  }

  @WorkflowInterface
  public interface SugarTargetWorkflow {
    @WorkflowMethod
    String run(String arg);
  }

  public static class SugarTargetWorkflowImpl implements SugarTargetWorkflow {
    @Override
    public String run(String arg) {
      return "workflow:" + arg;
    }
  }

  /** Drives the workflow-run sugar test via {@link TestWorkflows.TestWorkflow1}. */
  public static class SugarCaller implements TestWorkflows.TestWorkflow1, SyncCallerWorkflow {
    @Override
    public String execute(String input) {
      NexusServiceOptions options = defaultServiceOptions();
      // SyncCallerWorkflow re-uses the same impl method via the SyncCallerWorkflow interface;
      // distinguish by sentinel input so each test exercises one path.
      if ("hi".equals(input)) {
        return Workflow.newNexusServiceStub(SyncSugarService.class, options).greet(input);
      }
      return Workflow.newNexusServiceStub(SugarService.class, options).start(input);
    }
  }

  public static class MixedCaller implements MixedCallerWorkflow {
    @Override
    public String execute(String input) {
      MixedService stub = Workflow.newNexusServiceStub(MixedService.class, defaultServiceOptions());
      return stub.workflowOp(input) + "|" + stub.legacyOp("legacy-input");
    }
  }

  private static NexusServiceOptions defaultServiceOptions() {
    return NexusServiceOptions.newBuilder()
        .setOperationOptions(
            NexusOperationOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                .build())
        .build();
  }

  // ----- Nexus services -----

  @Service
  public interface SugarService {
    @Operation
    String start(String input);
  }

  @Service
  public interface SyncSugarService {
    @Operation
    String greet(String input);
  }

  @Service
  public interface MixedService {
    @Operation
    String workflowOp(String input);

    @Operation
    String legacyOp(String input);
  }

  @ServiceImpl(service = SugarService.class)
  public static class SugarServiceImpl {
    @TemporalOperation
    public TemporalOperationResult<String> start(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return client.startWorkflow(
          SugarTargetWorkflow.class,
          SugarTargetWorkflow::run,
          input,
          WorkflowOptions.newBuilder().setWorkflowId("sugar-" + ctx.getRequestId()).build());
    }
  }

  @ServiceImpl(service = SyncSugarService.class)
  public static class SyncSugarServiceImpl {
    @TemporalOperation
    public TemporalOperationResult<String> greet(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return TemporalOperationResult.sync("sync:" + input);
    }
  }

  @ServiceImpl(service = MixedService.class)
  public static class MixedServiceImpl {
    @TemporalOperation
    public TemporalOperationResult<String> workflowOp(
        TemporalOperationStartContext ctx, TemporalNexusClient client, String input) {
      return client.startWorkflow(
          SugarTargetWorkflow.class,
          SugarTargetWorkflow::run,
          input,
          WorkflowOptions.newBuilder().setWorkflowId("mixed-" + ctx.getRequestId()).build());
    }

    @OperationImpl
    public OperationHandler<String, String> legacyOp() {
      return new TemporalOperationHandler<String, String>(
          (ctx, client, input) -> TemporalOperationResult.sync("legacy:" + input)) {};
    }
  }
}
