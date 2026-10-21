package io.temporal.workflowstreams;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.workflowstreams.internal.PayloadWire;
import io.temporal.workflowstreams.internal.SubscriptionDriver;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

public class SubscriptionDriverTest {
  @Test
  public void testEveryTerminalStatusDrainsPinnedPages() throws Exception {
    for (WorkflowExecutionStatus status :
        new WorkflowExecutionStatus[] {
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
          WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT
        }) {
      Fixture fixture = new Fixture(status);
      fixture.run();
      Assert.assertEquals(2, fixture.items.size());
      Assert.assertEquals(3, fixture.items.get(0).getOffset());
      Assert.assertEquals(4, fixture.items.get(1).getOffset());
      Assert.assertEquals(1, fixture.completed);
      Assert.assertEquals(java.util.Arrays.asList("closed-run", "closed-run"), fixture.queryRuns);
      Assert.assertEquals(java.util.Arrays.asList(3L, 4L), fixture.queryOffsets);
    }
  }

  @Test
  public void testAcceptedPollDescribesItsOwnRun() throws Exception {
    Fixture fixture = new Fixture(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
    fixture.acceptPoll = true;
    fixture.run();
    Assert.assertEquals("accepted-run", fixture.describedRun);
    Assert.assertEquals(java.util.Arrays.asList("closed-run", "closed-run"), fixture.queryRuns);
  }

  @Test
  public void testTerminalDescriptionRequiresRunId() throws Exception {
    Fixture fixture = new Fixture(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
    fixture.terminalRunId = "";
    try {
      fixture.run();
      Assert.fail("an unpinned description must preserve the poll failure");
    } catch (ExecutionException e) {
      Assert.assertEquals("closed", e.getCause().getMessage());
    }
    Assert.assertTrue(fixture.queryRuns.isEmpty());
    Assert.assertEquals(0, fixture.completed);
  }

  @Test
  public void testQueryAndDescribeFailuresPropagate() throws Exception {
    for (boolean queryFails : new boolean[] {true, false}) {
      Fixture fixture = new Fixture(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
      fixture.queryError = queryFails ? new IllegalStateException("query failed") : null;
      fixture.describeError = queryFails ? null : new IllegalStateException("describe failed");
      try {
        fixture.run();
        Assert.fail("failure must reach the subscriber");
      } catch (ExecutionException e) {
        Assert.assertSame(queryFails ? fixture.queryError : fixture.describeError, e.getCause());
      }
      Assert.assertEquals(0, fixture.completed);
    }
  }

  private static class Fixture {
    final WorkflowExecutionStatus status;
    final List<WorkflowStreamItem> items = new ArrayList<>();
    final List<String> queryRuns = new ArrayList<>();
    final List<Long> queryOffsets = new ArrayList<>();
    int completed;
    boolean acceptPoll;
    String describedRun;
    String terminalRunId = "closed-run";
    RuntimeException queryError;
    RuntimeException describeError;

    Fixture(WorkflowExecutionStatus status) {
      this.status = status;
    }

    void run() throws Exception {
      WorkflowClient client =
          (WorkflowClient)
              Proxy.newProxyInstance(
                  WorkflowClient.class.getClassLoader(),
                  new Class<?>[] {WorkflowClient.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("newUntypedWorkflowStub")) {
                      String runId =
                          args[0] instanceof WorkflowTargetOptions
                              ? ((WorkflowTargetOptions) args[0]).getRunId()
                              : "";
                      return stub(runId);
                    }
                    throw new UnsupportedOperationException(method.getName());
                  });
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
      try {
        SubscriptionDriver driver =
            new SubscriptionDriver(
                client,
                "workflow",
                SubscribeOptions.newBuilder().setFromOffset(3).build(),
                executor,
                new WorkflowStreamListener() {
                  @Override
                  public java.util.concurrent.CompletionStage<Void> onNext(
                      WorkflowStreamItem item) {
                    items.add(item);
                    return null;
                  }

                  @Override
                  public void onCompleted() {
                    completed++;
                  }

                  @Override
                  public void onError(Throwable error) {}
                },
                ignored -> {});
        driver.start();
        driver.getDoneFuture().get(5, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }

    WorkflowStub stub(String runId) {
      return (WorkflowStub)
          Proxy.newProxyInstance(
              WorkflowStub.class.getClassLoader(),
              new Class<?>[] {WorkflowStub.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "startUpdate":
                    if (!acceptPoll) {
                      throw new IllegalStateException("closed");
                    }
                    return Proxy.newProxyInstance(
                        WorkflowUpdateHandle.class.getClassLoader(),
                        new Class<?>[] {WorkflowUpdateHandle.class},
                        (handle, handleMethod, handleArgs) -> {
                          if (handleMethod.getName().equals("getExecution")) {
                            return WorkflowExecution.newBuilder().setRunId("accepted-run").build();
                          }
                          CompletableFuture<PollResult> result = new CompletableFuture<>();
                          result.completeExceptionally(
                              new IllegalStateException("closed during poll"));
                          return result;
                        });
                  case "describe":
                    describedRun = runId;
                    if (describeError != null) {
                      throw describeError;
                    }
                    return new WorkflowExecutionDescription(
                        DescribeWorkflowExecutionResponse.newBuilder()
                            .setWorkflowExecutionInfo(
                                WorkflowExecutionInfo.newBuilder()
                                    .setExecution(
                                        WorkflowExecution.newBuilder()
                                            .setWorkflowId("workflow")
                                            .setRunId(terminalRunId))
                                    .setStatus(status))
                            .build(),
                        DefaultDataConverter.STANDARD_INSTANCE);
                  case "query":
                    if (queryError != null) {
                      throw queryError;
                    }
                    Assert.assertEquals(WorkflowStreamConstants.POLL_QUERY_NAME, args[0]);
                    PollInput input = (PollInput) ((Object[]) args[2])[0];
                    queryRuns.add(runId);
                    queryOffsets.add(input.fromOffset);
                    String data =
                        PayloadWire.encode(
                            DefaultDataConverter.STANDARD_INSTANCE.toPayload("tail").get());
                    return new PollResult(
                        Collections.singletonList(new WireItem("events", data, input.fromOffset)),
                        input.fromOffset + 1,
                        input.fromOffset == 3);
                  default:
                    throw new UnsupportedOperationException(method.getName());
                }
              });
    }
  }
}
