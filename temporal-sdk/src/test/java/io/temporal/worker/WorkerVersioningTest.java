package io.temporal.worker;

import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_PINNED;
import static io.temporal.api.workflow.v1.VersioningOverride.PinnedOverrideBehavior.PINNED_OVERRIDE_BEHAVIOR_PINNED;
import static org.junit.Assume.assumeTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.InitialVersioningBehavior;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.VersioningOverride;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.EncodedValues;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkerVersioningTest {
  // This worker isn't actually used
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build())
          .setDoNotStart(true)
          .build();

  public static class QueueLoop {
    WorkflowQueue<String> sigQueue = Workflow.newWorkflowQueue(1);

    public void queueLoop() {
      while (true) {
        String res = sigQueue.take();
        if (res.equals("done")) {
          break;
        }
      }
    }
  }

  public static class TestWorkerVersioningAutoUpgradeV1 extends QueueLoop
      implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.AUTO_UPGRADE)
    public String execute() {
      queueLoop();
      return "version-v1";
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return "v1";
    }
  }

  public static class TestWorkerVersioningPinnedV2 extends QueueLoop
      implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public String execute() {
      queueLoop();
      return "version-v2";
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return "v2";
    }
  }

  public static class TestWorkerVersioningAutoUpgradeV3 extends QueueLoop
      implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.AUTO_UPGRADE)
    public String execute() {
      queueLoop();
      return "version-v3";
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return "v3";
    }
  }

  public static class DynamicWorkflowImpl implements DynamicWorkflow {
    @Override
    public Object execute(EncodedValues args) {
      return "dynamic";
    }

    @Override
    public VersioningBehavior getVersioningBehavior() {
      return VersioningBehavior.PINNED;
    }
  }

  @Test
  public void testBasicWorkerVersioning() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    // Start the 1.0 worker
    Worker w1 = testWorkflowRule.newWorkerWithBuildID("1.0");
    w1.registerWorkflowImplementationTypes(TestWorkerVersioningAutoUpgradeV1.class);
    w1.start();

    // Start the 2.0 worker
    Worker w2 = testWorkflowRule.newWorkerWithBuildID("2.0");
    w2.registerWorkflowImplementationTypes(TestWorkerVersioningPinnedV2.class);
    w2.start();

    // Start the 3.0 worker
    Worker w3 = testWorkflowRule.newWorkerWithBuildID("3.0");
    w3.registerWorkflowImplementationTypes(TestWorkerVersioningAutoUpgradeV3.class);
    w3.start();

    WorkerDeploymentVersion v1 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "1.0");
    DescribeWorkerDeploymentResponse describeResp1 = waitUntilWorkerDeploymentVisible(v1);

    setCurrentVersion(v1, describeResp1.getConflictToken());

    // Start workflow 1 which will use the 1.0 worker on auto-upgrade
    TestWorkflows.QueryableWorkflow wf1 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "basic-versioning-v1");
    WorkflowExecution we1 = WorkflowClient.start(wf1::execute);
    // Make sure it's running
    Assert.assertEquals("v1", wf1.getState());

    // Set current version to 2.0
    WorkerDeploymentVersion v2 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "2.0");
    DescribeWorkerDeploymentResponse describeResp2 = waitUntilWorkerDeploymentVisible(v2);
    setCurrentVersion(v2, describeResp2.getConflictToken());

    TestWorkflows.QueryableWorkflow wf2 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "basic-versioning-v2");
    WorkflowExecution we2 = WorkflowClient.start(wf2::execute);
    Assert.assertEquals("v2", wf2.getState());

    WorkerDeploymentVersion v3 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "3.0");
    DescribeWorkerDeploymentResponse describeResp3 = waitUntilWorkerDeploymentVisible(v3);

    // Set current version to 3.0
    setCurrentVersion(v3, describeResp3.getConflictToken());

    TestWorkflows.QueryableWorkflow wf3 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "basic-versioning-v3");
    WorkflowExecution we3 = WorkflowClient.start(wf3::execute);
    Assert.assertEquals("v3", wf3.getState());

    // Signal all workflows to finish
    wf1.mySignal("done");
    wf2.mySignal("done");
    wf3.mySignal("done");

    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    // Wait for all workflows to finish
    String res = workflowClient.newUntypedWorkflowStub(we1.getWorkflowId()).getResult(String.class);
    // Should have been auto-upgraded to 3
    Assert.assertEquals("version-v3", res);
    String res2 =
        workflowClient.newUntypedWorkflowStub(we2.getWorkflowId()).getResult(String.class);
    // Should've stayed pinned to 2
    Assert.assertEquals("version-v2", res2);
    String res3 =
        workflowClient.newUntypedWorkflowStub(we3.getWorkflowId()).getResult(String.class);
    // Started and finished on 3
    Assert.assertEquals("version-v3", res3);
  }

  @Test
  public void testRampWorkerVersioning() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    // Start the 1.0 worker
    Worker w1 = testWorkflowRule.newWorkerWithBuildID("1.0");
    w1.registerWorkflowImplementationTypes(TestWorkerVersioningAutoUpgradeV1.class);
    w1.start();

    // Start the 2.0 worker
    Worker w2 = testWorkflowRule.newWorkerWithBuildID("2.0");
    w2.registerWorkflowImplementationTypes(TestWorkerVersioningPinnedV2.class);
    w2.start();

    WorkerDeploymentVersion v1 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "1.0");
    waitUntilWorkerDeploymentVisible(v1);
    WorkerDeploymentVersion v2 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "2.0");
    DescribeWorkerDeploymentResponse describeResp1 = waitUntilWorkerDeploymentVisible(v2);

    // Set cur ver to 1 & ramp 100% to 2
    SetWorkerDeploymentCurrentVersionResponse setCurR =
        setCurrentVersion(v1, describeResp1.getConflictToken());
    SetWorkerDeploymentRampingVersionResponse rampResp =
        setRampingVersion(v2, 100, setCurR.getConflictToken());
    // Run workflows and verify they've both started & run on v2
    for (int i = 0; i < 3; i++) {
      String res = runWorkflow("versioning-ramp-100");
      Assert.assertEquals("version-v2", res);
    }
    // Set ramp to 0, and see them start on v1
    SetWorkerDeploymentRampingVersionResponse rampResp2 =
        setRampingVersion(v2, 0, rampResp.getConflictToken());
    for (int i = 0; i < 3; i++) {
      String res = runWorkflow("versioning-ramp-0");
      Assert.assertEquals("version-v1", res);
    }
    // Set to 50% and see we eventually will have one run on v1 and one on v2
    setRampingVersion(v2, 50, rampResp2.getConflictToken());
    HashSet<String> seenRanOn = new HashSet<>();
    Eventually.assertEventually(
        Duration.ofSeconds(30),
        () -> {
          String res = runWorkflow("versioning-ramp-50");
          seenRanOn.add(res);
          Assert.assertTrue(seenRanOn.contains("version-v1"));
          Assert.assertTrue(seenRanOn.contains("version-v2"));
        });
  }

  @Test
  public void testDynamicWorkflow() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    Worker w1 = testWorkflowRule.newWorkerWithBuildID("1.0");
    w1.registerWorkflowImplementationTypes(DynamicWorkflowImpl.class);
    w1.start();

    WorkerDeploymentVersion v1 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "1.0");
    DescribeWorkerDeploymentResponse describeResp1 = waitUntilWorkerDeploymentVisible(v1);
    setCurrentVersion(v1, describeResp1.getConflictToken());

    WorkflowStub wf =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("dynamic-workflow-versioning");
    WorkflowExecution we = wf.start();
    wf.getResult(String.class);

    WorkflowExecutionHistory hist = testWorkflowRule.getExecutionHistory(we.getWorkflowId());
    Assert.assertTrue(
        hist.getHistory().getEventsList().stream()
            .anyMatch(
                e ->
                    e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED
                        && e.getWorkflowTaskCompletedEventAttributes().getVersioningBehavior()
                            == VERSIONING_BEHAVIOR_PINNED));
  }

  public static class TestWorkerVersioningMissingAnnotation extends QueueLoop
      implements TestWorkflows.QueryableWorkflow {
    @Override
    public String execute() {
      queueLoop();
      return "no-annotation";
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return "no-annotation";
    }
  }

  @Test
  public void testWorkflowsMustHaveVersioningBehaviorWhenFeatureTurnedOn() {
    Worker w1 = testWorkflowRule.newWorkerWithBuildID("1.0");
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                w1.registerWorkflowImplementationTypes(
                    TestWorkerVersioningMissingAnnotation.class));
    Assert.assertEquals(
        "Workflow method execute in implementation class "
            + "io.temporal.worker.WorkerVersioningTest$TestWorkerVersioningMissingAnnotation must "
            + "have a VersioningBehavior set, or a default must be set on worker deployment "
            + "options, since this worker is using worker versioning",
        e.getMessage());
  }

  @Test
  public void testWorkflowsCanUseDefaultVersioningBehaviorWhenSpecified() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    Worker defaultVersionWorker =
        testWorkflowRule.newWorker(
            (opts) ->
                opts.setDeploymentOptions(
                    WorkerDeploymentOptions.newBuilder()
                        .setVersion(
                            new WorkerDeploymentVersion(
                                testWorkflowRule.getDeploymentName(), "1.0"))
                        .setUseVersioning(true)
                        .setDefaultVersioningBehavior(VersioningBehavior.PINNED)
                        .build()));
    // Registration should work fine
    defaultVersionWorker.registerWorkflowImplementationTypes(
        TestWorkerVersioningMissingAnnotation.class);
    defaultVersionWorker.start();

    WorkerDeploymentVersion v1 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "1.0");
    DescribeWorkerDeploymentResponse describeResp1 = waitUntilWorkerDeploymentVisible(v1);
    setCurrentVersion(v1, describeResp1.getConflictToken());

    TestWorkflows.QueryableWorkflow wf1 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "default-versioning-behavior");
    WorkflowExecution we1 = WorkflowClient.start(wf1::execute);
    wf1.mySignal("done");
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    workflowClient.newUntypedWorkflowStub(we1.getWorkflowId()).getResult(String.class);

    WorkflowExecutionHistory hist = testWorkflowRule.getExecutionHistory(we1.getWorkflowId());
    Assert.assertTrue(
        hist.getHistory().getEventsList().stream()
            .anyMatch(
                e ->
                    e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED
                        && e.getWorkflowTaskCompletedEventAttributes().getVersioningBehavior()
                            == VERSIONING_BEHAVIOR_PINNED));
  }

  @WorkflowInterface
  public interface DontAllowBehaviorAnnotationOnInterface {
    @WorkflowMethod
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    void execute();
  }

  public static class DontAllowBehaviorAnnotationOnInterfaceImpl
      implements DontAllowBehaviorAnnotationOnInterface {
    @Override
    public void execute() {}
  }

  @Test
  public void testAnnotationNotAllowedOnInterface() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(
                        DontAllowBehaviorAnnotationOnInterfaceImpl.class));
    Assert.assertEquals(
        "@WorkflowVersioningBehavior annotation is not allowed on interface methods, only on "
            + "implementation methods: public abstract void io.temporal.worker.WorkerVersioningTest"
            + "$DontAllowBehaviorAnnotationOnInterface.execute()",
        e.getMessage());
  }

  @Test
  public void testWorkerWithDeploymentOptionsVersioningOffCanRunWorkflows() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    Worker w1 =
        testWorkflowRule.newWorker(
            (opts) ->
                opts.setDeploymentOptions(
                    WorkerDeploymentOptions.newBuilder()
                        .setVersion(
                            new WorkerDeploymentVersion(
                                testWorkflowRule.getDeploymentName(), "my-custom-build-id-1.0"))
                        .setUseVersioning(false)
                        .build()));
    w1.registerWorkflowImplementationTypes(TestWorkerVersioningMissingAnnotation.class);
    w1.start();

    TestWorkflows.QueryableWorkflow wf =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "versioning-off-build-id");
    WorkflowExecution we = WorkflowClient.start(wf::execute);
    wf.mySignal("done");
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(we.getWorkflowId())
            .getResult(String.class);
    Assert.assertEquals("no-annotation", result);

    WorkflowExecutionHistory hist = testWorkflowRule.getExecutionHistory(we.getWorkflowId());
    // The Java SDK sends deployment_options on WFT completion, and the server records the
    // deployment name in the worker_deployment_name field of the history event.
    Assert.assertTrue(
        "Expected deployment name to appear in workflow history",
        hist.getHistory().getEventsList().stream()
            .anyMatch(
                e ->
                    e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED
                        && !e.getWorkflowTaskCompletedEventAttributes()
                            .getWorkerDeploymentName()
                            .isEmpty()));
  }

  @Test
  public void testRejectsVersioningBehaviorWhenVersioningOff() {
    IllegalStateException e =
        Assert.assertThrows(
            IllegalStateException.class,
            () ->
                WorkerDeploymentOptions.newBuilder()
                    .setVersion(
                        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "1.0"))
                    .setUseVersioning(false)
                    .setDefaultVersioningBehavior(VersioningBehavior.AUTO_UPGRADE)
                    .build());
    Assert.assertTrue(e.getMessage().contains("defaultVersioningBehavior must be UNSPECIFIED"));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testWorkflowsCanUseVersioningOverride() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    Worker w1 = testWorkflowRule.newWorkerWithBuildID("1.0");
    WorkerDeploymentVersion v1 = w1.getWorkerOptions().getDeploymentOptions().getVersion();
    w1.registerWorkflowImplementationTypes(TestWorkerVersioningAutoUpgradeV1.class);
    w1.start();

    DescribeWorkerDeploymentResponse describeResp1 = waitUntilWorkerDeploymentVisible(v1);
    setCurrentVersion(v1, describeResp1.getConflictToken());

    String workflowId = "versioning-override-" + UUID.randomUUID();
    TestWorkflows.QueryableWorkflow wf =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestWorkflows.QueryableWorkflow.class,
                SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                    .toBuilder()
                    .setWorkflowId(workflowId)
                    .setVersioningOverride(new VersioningOverride.PinnedVersioningOverride(v1))
                    .build());
    WorkflowExecution we = WorkflowClient.start(wf::execute);
    wf.mySignal("done");
    testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(we.getWorkflowId())
        .getResult(String.class);

    WorkflowExecutionHistory hist = testWorkflowRule.getExecutionHistory(we.getWorkflowId());
    Assert.assertTrue(
        hist.getHistory().getEventsList().stream()
            .anyMatch(
                e ->
                    e.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
                        && e.getWorkflowExecutionStartedEventAttributes()
                                .getVersioningOverride()
                                .getPinned()
                                .getBehavior()
                            == PINNED_OVERRIDE_BEHAVIOR_PINNED));
  }

  public static class TestWorkerVersioningCanV1 implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public String execute() {
      while (!Workflow.getInfo().isTargetWorkerDeploymentVersionChanged()) {
        Workflow.sleep(java.time.Duration.ofMillis(10));
      }
      ContinueAsNewOptions options =
          ContinueAsNewOptions.newBuilder()
              .setInitialVersioningBehavior(InitialVersioningBehavior.AUTO_UPGRADE)
              .build();
      TestWorkflows.QueryableWorkflow next =
          Workflow.newContinueAsNewStub(TestWorkflows.QueryableWorkflow.class, options);
      next.execute();
      throw new RuntimeException("unreachable");
    }

    @Override
    public void mySignal(String arg) {}

    @Override
    public String getState() {
      return "v1-can";
    }
  }

  public static class TestWorkerVersioningCanV2 implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public String execute() {
      return "v2.0";
    }

    @Override
    public void mySignal(String arg) {}

    @Override
    public String getState() {
      return "v2-can";
    }
  }

  @Test
  public void testContinueAsNewWithVersionUpgrade() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    WorkerDeploymentVersion v1 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "1.0");
    WorkerDeploymentVersion v2 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "2.0");

    Worker w1 = testWorkflowRule.newWorkerWithBuildID("1.0");
    w1.registerWorkflowImplementationTypes(TestWorkerVersioningCanV1.class);
    w1.start();

    Worker w2 = testWorkflowRule.newWorkerWithBuildID("2.0");
    w2.registerWorkflowImplementationTypes(TestWorkerVersioningCanV2.class);
    w2.start();

    // Set v1 as current
    DescribeWorkerDeploymentResponse d1 = waitUntilWorkerDeploymentVisible(v1);
    setCurrentVersion(v1, d1.getConflictToken());
    waitForRoutingConfigPropagation(v1);

    // Start workflow on v1
    TestWorkflows.QueryableWorkflow wf =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "can-version-upgrade");
    WorkflowExecution we = WorkflowClient.start(wf::execute);

    // Verify workflow is running on v1
    Assert.assertEquals("v1-can", wf.getState());
    waitForWorkflowRunningOnVersion(we.getWorkflowId(), "1.0");

    // Set v2 as current — triggers targetWorkerDeploymentVersionChanged
    DescribeWorkerDeploymentResponse d2 = waitUntilWorkerDeploymentVisible(v2);
    setCurrentVersion(v2, d2.getConflictToken());
    waitForRoutingConfigPropagation(v2);

    // V1 workflow should detect version change, CAN with AUTO_UPGRADE, v2 returns "v2.0"
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(we.getWorkflowId())
            .getResult(String.class);
    Assert.assertEquals("v2.0", result);
  }

  @SuppressWarnings("deprecation")
  private DescribeWorkerDeploymentResponse waitUntilWorkerDeploymentVisible(
      WorkerDeploymentVersion v) {
    DescribeWorkerDeploymentRequest req =
        DescribeWorkerDeploymentRequest.newBuilder()
            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
            .setDeploymentName(v.getDeploymentName())
            .build();
    return Eventually.assertEventually(
        Duration.ofSeconds(15),
        () -> {
          DescribeWorkerDeploymentResponse resp =
              testWorkflowRule
                  .getWorkflowClient()
                  .getWorkflowServiceStubs()
                  .blockingStub()
                  .describeWorkerDeployment(req);
          Assert.assertTrue(
              resp.getWorkerDeploymentInfo().getVersionSummariesList().stream()
                  .anyMatch(vs -> vs.getVersion().equals(v.toCanonicalString())));
          return resp;
        });
  }

  private String runWorkflow(String idPrefix) {
    TestWorkflows.QueryableWorkflow wf =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, idPrefix);
    WorkflowExecution we = WorkflowClient.start(wf::execute);
    wf.mySignal("done");
    return testWorkflowRule
        .getWorkflowClient()
        .newUntypedWorkflowStub(we.getWorkflowId())
        .getResult(String.class);
  }

  @SuppressWarnings("deprecation")
  private SetWorkerDeploymentCurrentVersionResponse setCurrentVersion(
      WorkerDeploymentVersion v, ByteString conflictToken) {
    return testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .setWorkerDeploymentCurrentVersion(
            SetWorkerDeploymentCurrentVersionRequest.newBuilder()
                .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                .setDeploymentName(testWorkflowRule.getDeploymentName())
                .setVersion(v.toCanonicalString())
                .setConflictToken(conflictToken)
                .build());
  }

  @SuppressWarnings("deprecation")
  private SetWorkerDeploymentRampingVersionResponse setRampingVersion(
      WorkerDeploymentVersion v, float percent, ByteString conflictToken) {
    return testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .setWorkerDeploymentRampingVersion(
            SetWorkerDeploymentRampingVersionRequest.newBuilder()
                .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                .setDeploymentName(testWorkflowRule.getDeploymentName())
                .setVersion(v.toCanonicalString())
                .setConflictToken(conflictToken)
                .setPercentage(percent)
                .build());
  }

  @SuppressWarnings("deprecation")
  private void waitForRoutingConfigPropagation(WorkerDeploymentVersion v) {
    Eventually.assertEventually(
        Duration.ofSeconds(15),
        () -> {
          DescribeWorkerDeploymentResponse resp =
              testWorkflowRule
                  .getWorkflowClient()
                  .getWorkflowServiceStubs()
                  .blockingStub()
                  .describeWorkerDeployment(
                      DescribeWorkerDeploymentRequest.newBuilder()
                          .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                          .setDeploymentName(v.getDeploymentName())
                          .build());
          Assert.assertEquals(
              v.getBuildId(),
              resp.getWorkerDeploymentInfo()
                  .getRoutingConfig()
                  .getCurrentDeploymentVersion()
                  .getBuildId());
          // Check routing config update is not in progress
          int state = resp.getWorkerDeploymentInfo().getRoutingConfigUpdateStateValue();
          Assert.assertNotEquals(
              io.temporal.api.enums.v1.RoutingConfigUpdateState
                  .ROUTING_CONFIG_UPDATE_STATE_IN_PROGRESS_VALUE,
              state);
        });
  }

  private void waitForWorkflowRunningOnVersion(String workflowId, String expectedBuildId) {
    Eventually.assertEventually(
        Duration.ofSeconds(15),
        () -> {
          io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse resp =
              testWorkflowRule
                  .getWorkflowClient()
                  .getWorkflowServiceStubs()
                  .blockingStub()
                  .describeWorkflowExecution(
                      io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest
                          .newBuilder()
                          .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                          .setExecution(
                              io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                  .setWorkflowId(workflowId)
                                  .build())
                          .build());
          Assert.assertEquals(
              io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING,
              resp.getWorkflowExecutionInfo().getStatus());
          Assert.assertEquals(
              expectedBuildId,
              resp.getWorkflowExecutionInfo()
                  .getVersioningInfo()
                  .getDeploymentVersion()
                  .getBuildId());
        });
  }
}
