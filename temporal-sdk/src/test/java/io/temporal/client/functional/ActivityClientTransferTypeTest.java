package io.temporal.client.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.converter.TransferTypeTestModel;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class ActivityClientTransferTypeTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(new TransferActivityImpl())
          .build();

  @Test
  public void activityClientAndWorkerRoundTripTransferTypes() {
    assumeTrue(
        "server does not support standalone activities", SDKTestWorkflowRule.useExternalService);
    ActivityClient client =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .build());
    StartActivityOptions options =
        StartActivityOptions.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofMinutes(1))
            .build();

    TransferTypeTestModel result =
        client.execute(
            TransferActivity.class,
            TransferActivity::execute,
            options,
            new TransferTypeTestModel("input"));

    assertEquals(new TransferTypeTestModel("input-activity"), result);
    assertTrue(result.wasTransferred());
  }

  @ActivityInterface
  public interface TransferActivity {
    @ActivityMethod
    TransferTypeTestModel execute(TransferTypeTestModel input);
  }

  public static final class TransferActivityImpl implements TransferActivity {
    @Override
    public TransferTypeTestModel execute(TransferTypeTestModel input) {
      if (!input.wasTransferred()) {
        throw new IllegalStateException("Activity input did not use its transfer type converter");
      }
      return new TransferTypeTestModel(input.value() + "-activity");
    }
  }
}
