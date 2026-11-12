package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import com.google.common.reflect.TypeToken;
import io.temporal.activity.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.Copyable;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class LocalActivityCopyableTest {
  @Parameterized.Parameters(name = "includeMarkerArguments={0}")
  public static Object[] markerSettings() {
    return new Object[] {false, true};
  }

  @Parameterized.Parameter public boolean includeMarkerArguments;

  private final CountingConverter converter = new CountingConverter();
  private final CopyActivitiesImpl activities = new CopyActivitiesImpl();
  private final WorkflowClientOptions clientOptions =
      WorkflowClientOptions.newBuilder()
          .setDataConverter(
              DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(converter))
          .build();

  @Rule
  public SDKTestWorkflowRule rule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(clientOptions)
          .setWorkflowTypes(CopyWorkflowImpl.class)
          .setActivityImplementations(
              activities, (DynamicActivity) LocalActivityCopyableTest::executeDynamic)
          .build();

  private static int executeDynamic(EncodedValues args) {
    assertEquals(1, args.getSize());
    if (Activity.getExecutionContext().getInfo().getActivityType().equals("DynamicGeneric")) {
      Box<?> converted = args.get(0, Box.class, new TypeToken<Box<Long>>() {}.getType());
      assertEquals(Long.class, converted.value.getClass());
      assertEquals(Long.valueOf(1), converted.value);
      return 1;
    }
    MutableValue input = args.get(0, MutableValue.class);
    int original = input.value;
    input.value = 100;
    MutableValue second = args.get(0, MutableValue.class, MutableValue.class);
    assertNotSame(input, second);
    assertEquals(original, second.value);
    second.value = 200;
    assertEquals(original, args.get(0, MutableValue.class).value);
    if (input.mode.equals("dynamicFallback")) {
      assertEquals(original, args.get(0, PlainValue.class).value);
      assertNull(args.get(1, MutableValue.class));
      assertEquals(0, (int) args.get(1, int.class));
      assertEquals(original, args.get(0, PlainValue.class, PlainValue.class).value);
      EncodedValues snapshot =
          new EncodedValues(args.toPayloads(), DefaultDataConverter.newDefaultInstance());
      assertEquals(original, snapshot.get(0, MutableValue.class).value);
      assertEquals(args.toPayloads(), snapshot.toPayloads());
      assertEquals(1, args.getSize());
    }
    return original;
  }

  @Test
  public void schedulingSnapshotAndActivityMutationAreIsolated() {
    assertEquals("1:2", run("snapshot"));
    assertEquals(Collections.singletonList(1), activities.inputs);
    assertConversions(includeMarkerArguments ? 1 : 0, 0);
  }

  @Test
  public void eachLocalRetryReceivesAFreshSnapshot() throws Exception {
    assertRetries("localRetry", false);
  }

  @Test
  public void eachTimerBackedRetryReceivesAFreshSnapshot() throws Exception {
    assertRetries("timerRetry", true);
  }

  private void assertRetries(String mode, boolean timerBacked) throws Exception {
    CopyWorkflow workflow = rule.newWorkflowStub(CopyWorkflow.class);
    assertEquals("1:1", workflow.execute(mode, includeMarkerArguments));
    assertEquals(Arrays.asList(1, 1, 1), activities.inputs);
    assertNotSame(activities.attemptInputs.get(0), activities.attemptInputs.get(1));
    assertNotSame(activities.attemptInputs.get(1), activities.attemptInputs.get(2));
    assertConversions(includeMarkerArguments ? 1 : 0, 0);
    assertEquals(
        timerBacked ? 2 : 0,
        rule.getHistoryEvents(
                WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId(),
                EventType.EVENT_TYPE_TIMER_STARTED)
            .size());
    replay(workflow);
    assertConversions(includeMarkerArguments ? 2 : 0, 0);
    assertEquals(3, activities.attemptInputs.size());
  }

  @Test
  public void outputMutationIsIsolatedAndReplayDeserializesTheSnapshot() throws Exception {
    CopyWorkflow workflow = rule.newWorkflowStub(CopyWorkflow.class);
    assertEquals("7:7:8", workflow.execute("output", includeMarkerArguments));
    assertConversions(1, 0);
    assertEquals(9, activities.retained.value);
    replay(workflow);
    assertConversions(1, 1);
    assertEquals(1, activities.outputCalls.get());
  }

  @Test
  public void mixedArgumentsUseFullSerialization() {
    assertEquals("1:1", run("mixed"));
    assertConversions(1, 1);
  }

  @Test
  public void regularActivitiesAndSideEffectsStillUseSerialization() {
    assertEquals("1:6", run("regular"));
    assertConversions(2, 2);
  }

  @Test
  public void dynamicInputAndIncompatibleUntypedOutputAreSupported() {
    assertEquals("1:1", run("dynamic"));
    assertConversions(includeMarkerArguments ? 1 : 0, 0);
    assertEquals("7", run("untypedOutput"));
    assertConversions(includeMarkerArguments ? 2 : 1, 0);
    assertEquals(1, converter.plainReads.get());
  }

  @Test
  public void dynamicFallbackUsesTheSchedulingSnapshotAndMissingArgumentDefaults() {
    assertEquals("1:1", run("dynamicFallback"));
    assertConversions(1, 0);
    assertEquals(2, converter.plainReads.get());
  }

  @Test
  public void dynamicGenericInputHonorsTheRequestedType() {
    assertEquals("1", run("dynamicGeneric"));
  }

  @Test
  public void pojoGenericInputHonorsTheDeclaredType() {
    assertEquals("1", run("pojoGeneric"));
  }

  private String run(String mode) {
    return rule.newWorkflowStub(CopyWorkflow.class).execute(mode, includeMarkerArguments);
  }

  private void assertConversions(int writes, int reads) {
    assertEquals("MutableValue serialization count", writes, converter.writes.get());
    assertEquals("MutableValue deserialization count", reads, converter.reads.get());
  }

  private void replay(CopyWorkflow workflow) throws Exception {
    try (TestWorkflowEnvironment replay =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(clientOptions).build())) {
      WorkflowReplayer.replayWorkflowExecution(
          rule.getWorkflowClient()
              .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId()),
          replay,
          CopyWorkflowImpl.class);
    }
  }

  public static class MutableValue implements Copyable<MutableValue> {
    public int value;
    public String mode;

    public MutableValue() {}

    MutableValue(int value, String mode) {
      this.value = value;
      this.mode = mode;
    }

    @Override
    public MutableValue copy() {
      return new MutableValue(value, mode);
    }
  }

  public static class PlainValue {
    public int value;
    public String mode;
  }

  public static class Box<T> implements Copyable<Box<T>> {
    public T value;

    public Box() {}

    Box(T value) {
      this.value = value;
    }

    @Override
    public Box<T> copy() {
      return new Box<>(value);
    }
  }

  @WorkflowInterface
  public interface CopyWorkflow {
    @WorkflowMethod
    String execute(String mode, boolean includeMarkerArguments);
  }

  public static class CopyWorkflowImpl implements CopyWorkflow {
    @Override
    public String execute(String mode, boolean includeMarkerArguments) {
      boolean timerRetry = mode.equals("timerRetry");
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(10))
              .setDoNotIncludeArgumentsIntoMarker(!includeMarkerArguments)
              .setLocalRetryThreshold(timerRetry ? Duration.ofMillis(1) : Duration.ofSeconds(5))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(
                          timerRetry ? Duration.ofSeconds(1) : Duration.ofMillis(10))
                      .setBackoffCoefficient(1)
                      .setMaximumAttempts(3)
                      .build())
              .build();
      CopyActivities local = Workflow.newLocalActivityStub(CopyActivities.class, options);
      MutableValue input = new MutableValue(1, mode);
      switch (mode) {
        case "snapshot":
          boolean[] started = {false};
          Promise<Integer> result =
              Async.function(
                  () -> {
                    started[0] = true;
                    return local.mutate(input);
                  });
          // The async thread yields only after scheduling the activity.
          Workflow.await(() -> started[0]);
          input.value = 2;
          local.release();
          return result.get() + ":" + input.value;
        case "localRetry":
        case "timerRetry":
          return local.mutate(input) + ":" + input.value;
        case "output":
          MutableValue output = local.output();
          int original = output.value;
          output.value = 8;
          return original + ":" + local.changeRetained() + ":" + output.value;
        case "mixed":
          return local.mixed(input, "ordinary") + ":" + input.value;
        case "regular":
          CopyActivities regular =
              Workflow.newActivityStub(
                  CopyActivities.class,
                  ActivityOptions.newBuilder()
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .build());
          int value = regular.mutate(input);
          MutableValue sideEffect =
              Workflow.sideEffect(MutableValue.class, () -> new MutableValue(6, mode));
          return value + ":" + sideEffect.value;
        case "dynamicGeneric":
          return Integer.toString(
              Workflow.newUntypedLocalActivityStub(options)
                  .execute("DynamicGeneric", Integer.class, new Box<>(1)));
        case "pojoGeneric":
          return Long.toString(
              Workflow.newUntypedLocalActivityStub(options)
                  .execute("ReadBox", Long.class, new Box<>(1)));
        case "dynamic":
        case "dynamicFallback":
          return Workflow.newUntypedLocalActivityStub(options)
                  .execute("DynamicCopy", Integer.class, input)
              + ":"
              + input.value;
        case "untypedOutput":
          PlainValue converted =
              Workflow.newUntypedLocalActivityStub(options).execute("CopyOutput", PlainValue.class);
          return Integer.toString(converted.value);
        default:
          throw new IllegalArgumentException(mode);
      }
    }
  }

  @ActivityInterface
  public interface CopyActivities {
    int mutate(MutableValue value);

    void release();

    @ActivityMethod(name = "CopyOutput")
    MutableValue output();

    int changeRetained();

    int mixed(MutableValue copyable, String ordinary);

    @ActivityMethod(name = "ReadBox")
    long readBox(Box<Long> value);
  }

  public static class CopyActivitiesImpl implements CopyActivities {
    private final CountDownLatch gate = new CountDownLatch(1);
    final List<Integer> inputs = Collections.synchronizedList(new ArrayList<>());
    final List<MutableValue> attemptInputs = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger outputCalls = new AtomicInteger();
    private volatile MutableValue retained;

    @Override
    public int mutate(MutableValue value) {
      if (value.mode.equals("snapshot")) {
        try {
          if (!gate.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Workflow did not release the activity.");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      int original = value.value;
      inputs.add(original);
      attemptInputs.add(value);
      value.value = 100;
      if (value.mode.endsWith("Retry")
          && Activity.getExecutionContext().getInfo().getAttempt() < 3) {
        throw ApplicationFailure.newFailure("Retry after mutating the input.", "retry");
      }
      return original;
    }

    @Override
    public void release() {
      gate.countDown();
    }

    @Override
    public MutableValue output() {
      outputCalls.incrementAndGet();
      retained = new MutableValue(7, "output");
      return retained;
    }

    @Override
    public int changeRetained() {
      int original = retained.value;
      retained.value = 9;
      return original;
    }

    @Override
    public int mixed(MutableValue copyable, String ordinary) {
      return mutate(copyable);
    }

    @Override
    public long readBox(Box<Long> value) {
      return value.value.longValue();
    }
  }

  private static class CountingConverter extends JacksonJsonPayloadConverter {
    final AtomicInteger writes = new AtomicInteger();
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger plainReads = new AtomicInteger();

    @Override
    public Optional<Payload> toData(Object value) {
      if (value instanceof MutableValue) {
        writes.incrementAndGet();
      }
      return super.toData(value);
    }

    @Override
    public <T> T fromData(Payload content, Class<T> valueClass, Type valueType) {
      if (valueClass == MutableValue.class) {
        reads.incrementAndGet();
      } else if (valueClass == PlainValue.class) {
        plainReads.incrementAndGet();
      }
      return super.fromData(content, valueClass, valueType);
    }
  }
}
