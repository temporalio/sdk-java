package io.temporal.payload.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ContextAwareDataConverterTest {
  @ActivityInterface
  public interface Activities {
    @ActivityMethod(name = "HelloActivity")
    TracedValue hello(TracedValue input);
  }

  public static class ActivitiesImpl implements Activities {
    @Override
    public TracedValue hello(TracedValue input) {
      return new TracedValue("Hello " + input.getValue());
    }
  }

  @WorkflowInterface
  public interface HelloWorkflow {
    @WorkflowMethod
    TracedValue execute(TracedValue arg);
  }

  public static class HelloWorkflowImpl implements HelloWorkflow {
    private final Activities activities =
        Workflow.newActivityStub(
            Activities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public TracedValue execute(TracedValue arg) {
      return activities.hello(arg);
    }
  }

  private static final String TAG_WORKER = "worker";
  private static final String TAG_CLIENT = "client";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(HelloWorkflowImpl.class)
          .setActivityImplementations(new ActivitiesImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(new TracingDataConverter(GlobalDataConverter.get(), TAG_WORKER))
                  .build())
          .setActivityClientOptions(
              ActivityClientOptions.newBuilder()
                  .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                  .setDataConverter(new TracingDataConverter(GlobalDataConverter.get(), TAG_CLIENT))
                  .build())
          .build();

  @Test
  public void standaloneActivitySerializationContext() {
    String activityId = "act-" + UUID.randomUUID();

    TracedValue result =
        testWorkflowRule
            .getActivityClient()
            .execute(
                Activities.class,
                Activities::hello,
                StartActivityOptions.newBuilder()
                    .setId(activityId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build(),
                new TracedValue("world"));

    ActivitySerializationContext workerContext =
        new ActivitySerializationContext(
            SDKTestWorkflowRule.NAMESPACE,
            null,
            null,
            "HelloActivity",
            testWorkflowRule.getTaskQueue(),
            false);

    // Currently, client doesn't set activityType in serialization context
    ActivitySerializationContext clientContext =
        new ActivitySerializationContext(
            SDKTestWorkflowRule.NAMESPACE,
            null,
            null,
            null,
            testWorkflowRule.getTaskQueue(),
            false);

    Assert.assertEquals(
        result,
        new TracedValue("Hello world")
            .addTrace(
                TraceEntry.encode(workerContext, TAG_WORKER),
                TraceEntry.decode(clientContext, TAG_CLIENT)));
  }

  @Test
  public void workflowActivitySerializationContext() {
    WorkflowClient client = getWorkflowClient();

    HelloWorkflow workflow =
        client.newWorkflowStub(
            HelloWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowRunTimeout(Duration.ofSeconds(10))
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .build());

    TracedValue result = workflow.execute(new TracedValue("world"));
    WorkflowExecution execution = WorkflowStub.fromTyped(workflow).getExecution();
    Assert.assertNotNull(execution);
    List<HistoryEvent> history =
        client
            .fetchHistory(execution.getWorkflowId(), execution.getRunId())
            .getHistory()
            .getEventsList();
    List<HistoryEvent> scheduledEvents =
        history.stream()
            .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
            .collect(Collectors.toList());
    Assert.assertEquals(1, scheduledEvents.size());
    HistoryEvent scheduledEvent = scheduledEvents.get(0);
    List<HistoryEvent> completedEvents =
        history.stream()
            .filter(HistoryEvent::hasActivityTaskCompletedEventAttributes)
            .collect(Collectors.toList());
    Assert.assertEquals(1, completedEvents.size());
    HistoryEvent completedEvent = completedEvents.get(0);
    Assert.assertEquals(
        scheduledEvent.getEventId(),
        completedEvent.getActivityTaskCompletedEventAttributes().getScheduledEventId());

    WorkflowSerializationContext workflowContext =
        new WorkflowSerializationContext(SDKTestWorkflowRule.NAMESPACE, execution.getWorkflowId());

    ActivitySerializationContext activityContext =
        new ActivitySerializationContext(
            SDKTestWorkflowRule.NAMESPACE,
            execution.getWorkflowId(),
            "HelloWorkflow",
            "HelloActivity",
            testWorkflowRule.getTaskQueue(),
            false);

    Assert.assertEquals(
        result,
        new TracedValue("Hello world")
            .addTrace(
                TraceEntry.encode(activityContext, TAG_WORKER),
                TraceEntry.decode(activityContext, TAG_WORKER),
                TraceEntry.encode(workflowContext, TAG_WORKER),
                TraceEntry.decode(workflowContext, TAG_CLIENT)));

    Assert.assertEquals(
        GlobalDataConverter.get()
            .fromPayloads(
                0,
                Optional.of(scheduledEvent.getActivityTaskScheduledEventAttributes().getInput()),
                TracedValue.class,
                TracedValue.class),
        new TracedValue("world")
            .addTrace(
                TraceEntry.encode(workflowContext, TAG_CLIENT),
                TraceEntry.decode(workflowContext, TAG_WORKER),
                TraceEntry.encode(activityContext, TAG_WORKER)));

    Assert.assertEquals(
        GlobalDataConverter.get()
            .fromPayloads(
                0,
                Optional.of(completedEvent.getActivityTaskCompletedEventAttributes().getResult()),
                TracedValue.class,
                TracedValue.class),
        new TracedValue("Hello world").addTrace(TraceEntry.encode(activityContext, TAG_WORKER)));
  }

  private WorkflowClient getWorkflowClient() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkflowClientOptions options =
        client.getOptions().toBuilder()
            .setDataConverter(new TracingDataConverter(GlobalDataConverter.get(), TAG_CLIENT))
            .build();
    return WorkflowClient.newInstance(client.getWorkflowServiceStubs(), options);
  }

  private static class TracingDataConverter implements DataConverter {
    private final DataConverter dc;
    private final String tag;
    private final SerializationContext context;

    public TracingDataConverter(DataConverter dc, String tag) {
      this(dc, tag, null);
    }

    private TracingDataConverter(DataConverter dc, String tag, SerializationContext context) {
      this.dc = dc;
      this.tag = tag;
      this.context = context;
    }

    @Override
    public <T> Optional<Payload> toPayload(T value) throws DataConverterException {
      if (value instanceof TracedValue) {
        return dc.toPayload(((TracedValue) value).addTrace(TraceEntry.encode(context, tag)));
      } else {
        return dc.toPayload(value);
      }
    }

    @Override
    public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType)
        throws DataConverterException {
      if (valueClass == TracedValue.class) {
        return valueClass.cast(
            dc.fromPayload(payload, TracedValue.class, valueType)
                .addTrace(TraceEntry.decode(context, tag)));
      } else {
        return dc.fromPayload(payload, valueClass, valueType);
      }
    }

    @Override
    public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
      if (Arrays.stream(values).anyMatch(v -> v instanceof TracedValue)) {
        Payloads.Builder builder = Payloads.newBuilder();
        for (Object v : values) {
          builder.addPayloads(toPayload(v).get());
        }
        return Optional.of(builder.build());
      } else {
        return dc.toPayloads(values);
      }
    }

    @Override
    public <T> T fromPayloads(
        int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
        throws DataConverterException {
      if (valueType == TracedValue.class) {
        return valueType.cast(
            dc.fromPayloads(index, content, TracedValue.class, valueGenericType)
                .addTrace(TraceEntry.decode(context, tag)));
      } else {
        return dc.fromPayloads(index, content, valueType, valueGenericType);
      }
    }

    @Override
    public @NonNull DataConverter withContext(@NonNull SerializationContext context) {
      return new TracingDataConverter(dc, tag, context);
    }
  }

  public static class TracedValue {
    private final String value;
    private final ArrayList<TraceEntry> trace;

    public TracedValue(String value) {
      this.value = value;
      this.trace = new ArrayList<>();
    }

    @JsonCreator
    public TracedValue(
        @JsonProperty("value") String value, @JsonProperty("trace") List<TraceEntry> trace) {
      this.value = value;
      this.trace = new ArrayList<>(trace);
    }

    public String getValue() {
      return value;
    }

    public List<TraceEntry> getTrace() {
      return Collections.unmodifiableList(trace);
    }

    public TracedValue addTrace(TraceEntry... entries) {
      return new TracedValue(
          value,
          Stream.concat(trace.stream(), Arrays.stream(entries)).collect(Collectors.toList()));
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;
      TracedValue that = (TracedValue) o;
      return Objects.equals(value, that.value) && Objects.equals(trace, that.trace);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, trace);
    }

    @Override
    public String toString() {
      return "TracedValue{" + "value='" + value + '\'' + ", trace=" + trace + '}';
    }
  }

  public static class TraceEntry {
    private final @NonNull String tag;
    private final Operation operation;
    private final @Nullable String namespace;
    private final @Nullable String workflowId;
    private final @Nullable String activityType;
    private final @Nullable Boolean local;

    @JsonCreator
    public TraceEntry(
        @JsonProperty("tag") @NonNull String tag,
        @JsonProperty("operation") Operation operation,
        @JsonProperty("namespace") @Nullable String namespace,
        @JsonProperty("workflowId") @Nullable String workflowId,
        @JsonProperty("activityType") @Nullable String activityType,
        @JsonProperty("local") @Nullable Boolean local) {
      this.tag = tag;
      this.operation = operation;
      this.namespace = namespace;
      this.workflowId = workflowId;
      this.activityType = activityType;
      this.local = local;
    }

    public TraceEntry(
        @NonNull SerializationContext context, @NonNull String tag, Operation operation) {
      this.tag = tag;
      this.operation = operation;
      if (context instanceof WorkflowSerializationContext) {
        WorkflowSerializationContext c = (WorkflowSerializationContext) context;
        namespace = c.getNamespace();
        workflowId = c.getWorkflowId();
        activityType = null;
        local = null;
      } else if (context instanceof ActivitySerializationContext) {
        ActivitySerializationContext c = (ActivitySerializationContext) context;
        namespace = c.getNamespace();
        workflowId = c.getWorkflowId();
        activityType = c.getActivityType();
        local = c.isLocal();
      } else {
        throw new IllegalArgumentException(
            "Unknown context type: " + context.getClass().getCanonicalName());
      }
    }

    public static TraceEntry encode(@NonNull SerializationContext context, @NonNull String tag) {
      return new TraceEntry(context, tag, Operation.Encode);
    }

    public static TraceEntry decode(@NonNull SerializationContext context, @NonNull String tag) {
      return new TraceEntry(context, tag, Operation.Decode);
    }

    public @NonNull String getTag() {
      return tag;
    }

    public Operation getOperation() {
      return operation;
    }

    public @Nullable String getNamespace() {
      return namespace;
    }

    public @Nullable String getWorkflowId() {
      return workflowId;
    }

    public @Nullable String getActivityType() {
      return activityType;
    }

    public @Nullable Boolean isLocal() {
      return local;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) return false;
      TraceEntry that = (TraceEntry) o;
      return Objects.equals(tag, that.tag)
          && operation == that.operation
          && Objects.equals(namespace, that.namespace)
          && Objects.equals(workflowId, that.workflowId)
          && Objects.equals(activityType, that.activityType)
          && Objects.equals(local, that.local);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tag, operation, namespace, workflowId, activityType, local);
    }

    @Override
    public String toString() {
      return "TraceEntry{"
          + "tag='"
          + tag
          + '\''
          + ", operation="
          + operation
          + ", namespace='"
          + namespace
          + '\''
          + ", workflowId='"
          + workflowId
          + '\''
          + ", activityType='"
          + activityType
          + '\''
          + ", local="
          + local
          + '}';
    }

    public enum Operation {
      Encode,
      Decode
    }
  }
}
