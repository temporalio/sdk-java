package io.temporal.internal.client.external;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.StartActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.StartActivityExecutionResponse;
import io.temporal.internal.payload.storage.ExternalStorageMessageConverter;
import io.temporal.payload.storage.ExternalStorageOptions;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class ExternalStorageGenericWorkflowClientTest {

  @Test
  public void standaloneActivityStartIncludesKnownTargetInfo() {
    GenericWorkflowClient next = mock(GenericWorkflowClient.class);
    when(next.startActivity(any())).thenReturn(StartActivityExecutionResponse.getDefaultInstance());
    CapturingDriver driver = new CapturingDriver();
    ExternalStorageGenericWorkflowClient client =
        new ExternalStorageGenericWorkflowClient(
            next,
            ExternalStorageMessageConverter.create(
                ExternalStorageOptions.newBuilder()
                    .setDriver(driver)
                    .setPayloadSizeThreshold(0)
                    .build(),
                1),
            "test-namespace");
    StartActivityExecutionRequest request =
        StartActivityExecutionRequest.newBuilder()
            .setActivityId("activity-id")
            .setActivityType(ActivityType.newBuilder().setName("activity-type"))
            .setInput(Payloads.newBuilder().addPayloads(Payload.getDefaultInstance()))
            .build();

    client.startActivity(request);

    assertEquals(
        Collections.singletonList(
            new StorageDriverActivityInfo("test-namespace", "activity-id", null, "activity-type")),
        driver.targets);
  }

  private static final class CapturingDriver implements StorageDriver {
    private final List<StorageDriverTargetInfo> targets = new ArrayList<>();

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test";
    }

    @Override
    public CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      targets.add(context.getTarget());
      return CompletableFuture.completedFuture(
          Collections.singletonList(new StorageDriverClaim(Collections.emptyMap())));
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      return CompletableFuture.completedFuture(Collections.emptyList());
    }
  }
}
