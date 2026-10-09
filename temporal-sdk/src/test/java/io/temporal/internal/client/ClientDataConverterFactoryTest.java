package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.TransferTypeTestModel;
import io.temporal.internal.payload.storage.ExternalStorageNotConfiguredException;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import org.junit.Test;

public class ClientDataConverterFactoryTest {
  @Test
  public void offloadsSerializedTransferTypeAndReconstructsModel() {
    TestStorageDriver driver = TestStorageDriver.create();
    ExternalStorageRunner runner =
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build());
    DataConverter converter =
        ClientDataConverterFactory.forClient(DefaultDataConverter.newDefaultInstance(), runner);

    Payload reference = converter.toPayload(new TransferTypeTestModel("value")).get();

    assertEquals(
        "temporal.api.sdk.v1.ExternalStorageReference",
        reference.getMetadataOrThrow("messageType").toStringUtf8());
    assertEquals(1, driver.storedPayloads().size());
    Payload stored = driver.storedPayloads().iterator().next();
    assertEquals("json/protobuf", stored.getMetadataOrThrow("encoding").toStringUtf8());
    assertEquals(
        "google.protobuf.StringValue", stored.getMetadataOrThrow("messageType").toStringUtf8());
    TransferTypeTestModel result =
        converter.fromPayload(reference, TransferTypeTestModel.class, TransferTypeTestModel.class);
    assertEquals(new TransferTypeTestModel("value"), result);
    assertTrue(result.wasTransferred());
  }

  @Test
  public void scopesWorkflowConverterWithoutChangingConversionOrder() {
    TestStorageDriver driver = TestStorageDriver.create();
    ExternalStorageRunner runner =
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build());
    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder()
            .setNamespace("test-namespace")
            .setDataConverter(DefaultDataConverter.newDefaultInstance())
            .build();
    DataConverter scoped =
        new ClientDataConverterFactory(options, runner)
            .forWorkflow("workflow-id", "run-id", "workflow-type");

    Payload reference = scoped.toPayload(new TransferTypeTestModel("value")).get();

    assertEquals(
        "google.protobuf.StringValue",
        driver.storedPayloads().iterator().next().getMetadataOrThrow("messageType").toStringUtf8());
    assertEquals(
        new StorageDriverWorkflowInfo("test-namespace", "workflow-id", "run-id", "workflow-type"),
        driver.targets.get(0));
    assertTrue(
        scoped
            .fromPayload(reference, TransferTypeTestModel.class, TransferTypeTestModel.class)
            .wasTransferred());
  }

  @Test
  public void unconfiguredWorkflowRejectsReferencesButOtherClientsRemainTransferOnly() {
    TestStorageDriver driver = TestStorageDriver.create();
    ExternalStorageRunner runner =
        ExternalStorageRunner.create(
            ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build());
    DataConverter configured = DefaultDataConverter.newDefaultInstance();
    Payload reference =
        ClientDataConverterFactory.forClient(configured, runner)
            .toPayload(new TransferTypeTestModel("value"))
            .get();
    DataConverter unconfiguredClient = ClientDataConverterFactory.forClient(configured);
    DataConverter unconfiguredWorkflow =
        new ClientDataConverterFactory(
                WorkflowClientOptions.newBuilder()
                    .setNamespace("test-namespace")
                    .setDataConverter(configured)
                    .build(),
                null)
            .forWorkflow("workflow-id", null, null);

    assertTrue(unconfiguredClient.toPayload(new TransferTypeTestModel("inline")).isPresent());
    assertThrows(
        ExternalStorageNotConfiguredException.class,
        () ->
            unconfiguredWorkflow.fromPayload(
                reference, TransferTypeTestModel.class, TransferTypeTestModel.class));
  }
}
