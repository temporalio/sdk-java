package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflow.v1.NewWorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.CreateScheduleRequest;
import io.temporal.api.workflowservice.v1.CreateScheduleResponse;
import io.temporal.api.workflowservice.v1.DescribeScheduleResponse;
import io.temporal.api.workflowservice.v1.StartActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.StartActivityExecutionResponse;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.StartActivityInput;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor.CreateScheduleInput;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor.DescribeScheduleInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.payload.storage.ExternalStorageNotConfiguredException;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class ActivityAndScheduleClientExternalStorageTest {

  private static final String NAMESPACE = "test-namespace";
  private static final DataConverter CONVERTER = DefaultDataConverter.newDefaultInstance();

  @Test
  public void optionsKeepExternalStorageThroughBuilderCopies() {
    ExternalStorage storage = storage(new InMemoryDriver());

    ActivityClientOptions activityOptions =
        ActivityClientOptions.newBuilder().setExternalStorage(storage).build();
    assertSame(storage, activityOptions.toBuilder().build().getExternalStorage());
    assertNull(ActivityClientOptions.getDefaultInstance().getExternalStorage());

    ScheduleClientOptions scheduleOptions =
        ScheduleClientOptions.newBuilder().setExternalStorage(storage).build();
    assertSame(storage, scheduleOptions.toBuilder().build().getExternalStorage());
    assertNull(ScheduleClientOptions.getDefaultInstance().getExternalStorage());
  }

  @Test
  public void activityStartOffloadsInputAndHeaderWithTheActivityAsTarget() {
    InMemoryDriver driver = new InMemoryDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.startActivity(any()))
        .thenReturn(StartActivityExecutionResponse.newBuilder().setRunId("run-1").build());
    ArgumentCaptor<StartActivityExecutionRequest> sent =
        ArgumentCaptor.forClass(StartActivityExecutionRequest.class);

    new RootActivityClientInvoker(
            rpc, NAMESPACE, "identity", CONVERTER, ExternalStorageRunner.create(storage(driver)))
        .startActivity(
            new StartActivityInput(
                "MyActivityType",
                Collections.singletonList("activity-input"),
                StartActivityOptions.newBuilder()
                    .setId("act-1")
                    .setTaskQueue("tq")
                    .setStartToCloseTimeout(java.time.Duration.ofSeconds(10))
                    .build(),
                headerWith("trace", "some-tracing-context")));

    verify(rpc).startActivity(sent.capture());
    assertTrue(driver.stored.contains(payload("activity-input")));
    assertNotEquals(payload("activity-input"), sent.getValue().getInput().getPayloads(0));
    assertTrue(driver.stored.contains(payload("some-tracing-context")));
    assertNotEquals(
        payload("some-tracing-context"), sent.getValue().getHeader().getFieldsOrThrow("trace"));

    assertEquals(
        new StorageDriverActivityInfo(NAMESPACE, "act-1", null, "MyActivityType"),
        driver.lastTarget);
  }

  @Test
  public void scheduleCreateOffloadsActionPayloadsWithTheScheduledWorkflowAsTarget() {
    InMemoryDriver driver = new InMemoryDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.createSchedule(any())).thenReturn(CreateScheduleResponse.getDefaultInstance());
    ArgumentCaptor<CreateScheduleRequest> sent =
        ArgumentCaptor.forClass(CreateScheduleRequest.class);

    scheduleInvoker(rpc, ExternalStorageRunner.create(storage(driver)))
        .createSchedule(
            new CreateScheduleInput(
                "sched-1",
                Schedule.newBuilder()
                    .setAction(
                        ScheduleActionStartWorkflow.newBuilder()
                            .setWorkflowType("MyWorkflowType")
                            .setArguments("workflow-input")
                            .setHeader(headerWith("trace", "some-tracing-context"))
                            .setOptions(
                                WorkflowOptions.newBuilder()
                                    .setWorkflowId("wf-1")
                                    .setTaskQueue("tq")
                                    .setMemo(Collections.singletonMap("k", "action-memo"))
                                    .build())
                            .build())
                    .setSpec(ScheduleSpec.newBuilder().build())
                    .build(),
                ScheduleOptions.newBuilder()
                    .setMemo(Collections.singletonMap("k", "schedule-memo"))
                    .build()));

    verify(rpc).createSchedule(sent.capture());
    NewWorkflowExecutionInfo action = sent.getValue().getSchedule().getAction().getStartWorkflow();
    assertNotEquals(payload("workflow-input"), action.getInput().getPayloads(0));
    assertNotEquals(payload("action-memo"), action.getMemo().getFieldsOrThrow("k"));
    assertNotEquals(payload("some-tracing-context"), action.getHeader().getFieldsOrThrow("trace"));
    assertNotEquals(payload("schedule-memo"), sent.getValue().getMemo().getFieldsOrThrow("k"));

    StorageDriverWorkflowInfo scheduledWorkflow =
        new StorageDriverWorkflowInfo(NAMESPACE, "wf-1", null, "MyWorkflowType");
    for (String value :
        new String[] {"workflow-input", "action-memo", "some-tracing-context", "schedule-memo"}) {
      assertEquals(value, scheduledWorkflow, driver.targetOf(payload(value)));
    }
  }

  @Test
  public void scheduleDescribeResolvesReferencesWhenStorageIsConfigured() {
    InMemoryDriver driver = new InMemoryDriver();
    ExternalStorageRunner runner = ExternalStorageRunner.create(storage(driver));
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.describeSchedule(any())).thenReturn(describeResponseWithReferences(runner));

    ScheduleDescription description =
        scheduleInvoker(rpc, runner)
            .describeSchedule(new DescribeScheduleInput("sched-1"))
            .getDescription();

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) description.getSchedule().getAction();
    assertEquals("workflow-input", action.getArguments().get(0, String.class));
    assertEquals("schedule-memo", description.getMemo("k", String.class));
  }

  @Test
  public void scheduleDescribeRejectsReferencesWhenStorageIsNotConfigured() {
    ExternalStorageRunner runner = ExternalStorageRunner.create(storage(new InMemoryDriver()));
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.describeSchedule(any())).thenReturn(describeResponseWithReferences(runner));

    ScheduleDescription description =
        scheduleInvoker(rpc, null)
            .describeSchedule(new DescribeScheduleInput("sched-1"))
            .getDescription();

    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) description.getSchedule().getAction();
    assertThrows(
        ExternalStorageNotConfiguredException.class,
        () -> action.getArguments().get(0, String.class));
    assertThrows(
        ExternalStorageNotConfiguredException.class, () -> description.getMemo("k", String.class));
  }

  private static DescribeScheduleResponse describeResponseWithReferences(
      ExternalStorageRunner runner) {
    Payloads.Builder stored =
        Payloads.newBuilder()
            .addPayloads(payload("workflow-input"))
            .addPayloads(payload("schedule-memo"));
    runner.store(stored, null, null, CancellationToken.none());
    return DescribeScheduleResponse.newBuilder()
        .setSchedule(
            io.temporal.api.schedule.v1.Schedule.newBuilder()
                .setAction(
                    io.temporal.api.schedule.v1.ScheduleAction.newBuilder()
                        .setStartWorkflow(
                            NewWorkflowExecutionInfo.newBuilder()
                                .setWorkflowId("wf-1")
                                .setWorkflowType(
                                    WorkflowType.newBuilder().setName("MyWorkflowType"))
                                .setInput(
                                    Payloads.newBuilder().addPayloads(stored.getPayloads(0))))))
        .setMemo(io.temporal.api.common.v1.Memo.newBuilder().putFields("k", stored.getPayloads(1)))
        .build();
  }

  private static RootScheduleClientInvoker scheduleInvoker(
      GenericWorkflowClient rpc, ExternalStorageRunner runner) {
    return new RootScheduleClientInvoker(
        rpc, NAMESPACE, "identity", Collections.emptyList(), CONVERTER, runner);
  }

  private static ExternalStorage storage(StorageDriver driver) {
    return ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build();
  }

  private static Header headerWith(String key, String value) {
    return new Header(Collections.singletonMap(key, payload(value)));
  }

  private static Payload payload(String value) {
    try {
      return CONVERTER.toPayload(value).get();
    } catch (DataConverterException e) {
      throw new AssertionError(e);
    }
  }

  private static final class InMemoryDriver implements StorageDriver {
    volatile StorageDriverTargetInfo lastTarget;
    private final Map<Payload, StorageDriverTargetInfo> targets = new HashMap<>();
    final List<Payload> stored = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Payload> objects = new HashMap<>();
    private int counter = 0;

    synchronized StorageDriverTargetInfo targetOf(Payload payload) {
      assertTrue("expected the payload to reach the driver", targets.containsKey(payload));
      return targets.get(payload);
    }

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      lastTarget = context.getTarget();
      for (Payload payload : payloads) {
        targets.put(payload, context.getTarget());
      }
      stored.addAll(payloads);
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = "k-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
