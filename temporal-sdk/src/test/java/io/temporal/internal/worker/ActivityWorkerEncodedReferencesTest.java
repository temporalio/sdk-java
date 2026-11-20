package io.temporal.internal.worker;

import static io.temporal.payload.storage.ExternalStorageTestUtils.encodingReferences;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.payload.storage.EnvelopeCodec;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.testing.CloudTestExclusion.NeedsCloudAdaptation;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * A standalone activity round trip with references encoded by the payload codec, through the
 * client, the activity worker and heartbeats. Needs an external server for standalone activities.
 */
@CloudTestExclusionNote(
    "Cloud envconfig replaces the custom external storage option used by this test.")
@Category(NeedsCloudAdaptation.class)
public class ActivityWorkerEncodedReferencesTest {

  private final EnvelopeCodec codec = new EnvelopeCodec();
  private final TestStorageDriver driver = TestStorageDriver.create();
  private final DataConverter converter =
      new CodecDataConverter(
          DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(codec));
  // Every payload is offloaded.
  private final ExternalStorage storage =
      encodingReferences(ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0))
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(new HeartbeatThenEchoImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(converter)
                  .setExternalStorage(storage)
                  .build())
          .build();

  @Test
  public void encodedReferencesRoundTripThroughAStandaloneActivity() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setDataConverter(converter)
                .setExternalStorage(storage)
                .build());

    String result =
        client.execute(
            HeartbeatThenEcho.class,
            HeartbeatThenEcho::run,
            StartActivityOptions.newBuilder()
                .setId("encoded-refs-" + UUID.randomUUID())
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setScheduleToCloseTimeout(Duration.ofMinutes(1))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(100))
                        .setMaximumAttempts(2)
                        .build())
                .build(),
            "hi");

    // Every reference was encoded, so the input, the heartbeat details read back on the retry and
    // the result only round-trip if each read decoded them.
    assertEquals("hi after progress", result);
    assertTrue("payloads should have been stored", driver.storedCount() > 0);
    assertEquals(
        "every reference should have been encoded",
        driver.storedCount(),
        codec.referenceEncodes.get());
  }

  @ActivityInterface
  public interface HeartbeatThenEcho {
    @ActivityMethod(name = "HeartbeatThenEcho")
    String run(String input);
  }

  /** Records a heartbeat and fails on the first attempt, then reads the heartbeat back. */
  public static class HeartbeatThenEchoImpl implements HeartbeatThenEcho {
    @Override
    public String run(String input) {
      ActivityExecutionContext context = Activity.getExecutionContext();
      if (context.getInfo().getAttempt() == 1) {
        context.heartbeat("progress");
        throw new IllegalStateException("first attempt fails");
      }
      return input + " after " + context.getHeartbeatDetails(String.class).get();
    }
  }
}
