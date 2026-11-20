package io.temporal.client.nexus;

import static io.temporal.payload.storage.ExternalStorageTestUtils.encodingReferences;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.payload.storage.EnvelopeCodec;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.Collections;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** A standalone Nexus operation with references encoded by the payload codec. */
@CloudTestExclusionNote("Cloud CI does not provision the Nexus permissions required by this test.")
@Category(RequiresCloudProvisioning.class)
public class NexusEncodedReferencesTest {

  private final EnvelopeCodec codec = new EnvelopeCodec();
  private final TestStorageDriver driver = TestStorageDriver.create();
  private final DataConverter converter =
      new CodecDataConverter(
          DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(codec));
  private final ExternalStorage storage =
      encodingReferences(ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0))
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(converter)
                  .setExternalStorage(storage)
                  .build())
          .build();

  @Before
  public void requireStandaloneNexus() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.isUseExternalService());
  }

  @Test
  public void encodedReferencesRoundTripThroughAStandaloneOperation() {
    NexusClient client =
        NexusClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            NexusClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setDataConverter(converter)
                .setExternalStorage(storage)
                .build());

    String result =
        client
            .newNexusServiceClient(
                TestNexusServices.TestNexusService1.class,
                testWorkflowRule.getNexusEndpoint().getSpec().getName())
            .execute(
                TestNexusServices.TestNexusService1::operation,
                StartNexusOperationOptions.newBuilder().setId(UUID.randomUUID().toString()).build(),
                "hi");

    // Every reference was encoded, so the result only round-trips if each read decoded them.
    assertEquals("echo:hi", result);
    assertTrue("payloads should have been stored", driver.storedCount() > 0);
    assertEquals(
        "every reference should have been encoded",
        driver.storedCount(),
        codec.referenceEncodes.get());
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }
}
