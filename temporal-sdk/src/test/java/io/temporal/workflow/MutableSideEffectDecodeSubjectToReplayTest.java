package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("Cloud envconfig replaces the custom data converter.")
@Category(NeedsCloudAdaptation.class)
public class MutableSideEffectDecodeSubjectToReplayTest {
  private static final AtomicReference<Boolean> subjectToReplayWhileDecoding =
      new AtomicReference<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      DefaultDataConverter.newDefaultInstance()
                          .withPayloadConverterOverrides(new StoredValueRecordingConverter()))
                  .build())
          .build();

  /** The stored value is decoded only when an existing marker is updated live. */
  @Test
  public void storedValueIsDecodedOutsideReplay() {
    testWorkflowRule.newWorkflowStubTimeoutOptions(NoArgsWorkflow.class).execute();
    assertEquals(Boolean.FALSE, subjectToReplayWhileDecoding.get());
  }

  public static class TestWorkflowImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      Workflow.mutableSideEffect("id", Integer.class, (stored, current) -> true, () -> 1);
      Workflow.mutableSideEffect("id", Integer.class, (stored, current) -> true, () -> 2);
    }
  }

  private static class StoredValueRecordingConverter extends JacksonJsonPayloadConverter {
    @Override
    public <T> T fromData(Payload content, Class<T> valueClass, Type valueType) {
      if (valueClass == Integer.class) {
        subjectToReplayWhileDecoding.set(WorkflowUnsafe.isSubjectToReplay());
      }
      return super.fromData(content, valueClass, valueType);
    }
  }
}
