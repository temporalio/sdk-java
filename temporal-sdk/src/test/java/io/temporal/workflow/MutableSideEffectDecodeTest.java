package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.lang.reflect.Type;
import java.util.Objects;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("Cloud envconfig replaces the custom data converter.")
@Category(NeedsCloudAdaptation.class)
public class MutableSideEffectDecodeTest {
  private static final String STREAM_NAME = "io.temporal.test";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      DefaultDataConverter.newDefaultInstance()
                          .withPayloadConverterOverrides(new GuardedDrawingConverter()))
                  .build())
          .build();

  /**
   * A converter that may draw from a random stream must be prevented from drawing by isReadOnly
   * during mutable side effect conversions. Below shows why:
   *
   * <p>Live:
   *
   * <ul>
   *   <li>{@code () -> 1}: New value, toPayloads is run within the callback.
   *   <li>{@code () -> 2}: New value, fromPayloads is run within the callback, toPayloads is run
   *       within the callback.
   * </ul>
   *
   * <p>Replay:
   *
   * <ul>
   *   <li>{@code () -> 1}: fromPayloads is run outside the callback.
   *   <li>{@code () -> 2}: fromPayloads is run outside the callback.
   * </ul>
   *
   * <p>No conversion runs both live and on replay, so a draw in any of them would advance the
   * stream on only one side, and later draws would differ between live execution and replay.
   */
  @Test
  public void isReadOnlyPreventsConverterDraw() {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);

    long live = workflow.run();

    // The query replays the completed Workflow from the start.
    assertEquals(live, workflow.draw());
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    long run();

    @QueryMethod
    long draw();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private long draw;

    @Override
    public long run() {
      Workflow.mutableSideEffect(
          "id", Integer.class, (stored, current) -> !Objects.equals(stored, current), () -> 1);
      Workflow.mutableSideEffect(
          "id", Integer.class, (stored, current) -> !Objects.equals(stored, current), () -> 2);
      draw = Workflow.getRandomStream(STREAM_NAME).nextLong();
      return draw;
    }

    @Override
    public long draw() {
      return draw;
    }
  }

  private static class GuardedDrawingConverter extends JacksonJsonPayloadConverter {
    @Override
    public <T> T fromData(Payload content, Class<T> valueClass, Type valueType) {
      if (WorkflowUnsafe.isWorkflowThread() && !WorkflowUnsafe.isReadOnly()) {
        Workflow.getRandomStream(STREAM_NAME).nextLong();
      }
      return super.fromData(content, valueClass, valueType);
    }
  }
}
