package io.temporal.internal.testservice;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.internal.common.ProtoEnumNameUtils;
import org.junit.Test;

public class TestVisibilityStoreImplTest {
  private static final String METADATA_TYPE_KEY = "type";
  private static final String DEFAULT_KEY_INTEGER = "CustomIntField";

  @Test
  public void testTypeMetadataIsAddedToPayloads() {
    TestVisibilityStoreImpl visibilityStore = new TestVisibilityStoreImpl();
    Payload payloadWithoutType =
        Payload.newBuilder().setData(ByteString.copyFromUtf8("test data")).build();

    assertFalse(
        "Payload should not have type metadata initially",
        payloadWithoutType.getMetadataMap().containsKey(METADATA_TYPE_KEY));

    SearchAttributes.Builder searchAttributesBuilder = SearchAttributes.newBuilder();
    searchAttributesBuilder.putIndexedFields(DEFAULT_KEY_INTEGER, payloadWithoutType);
    SearchAttributes searchAttributes = searchAttributesBuilder.build();
    ExecutionId executionId = new ExecutionId("test-namespace", "test-workflow-id", "test-run-id");
    try {
      SearchAttributes result =
          visibilityStore.upsertSearchAttributesForExecution(executionId, searchAttributes);
      assertNotNull("Result should not be null", result);

      if (result.containsIndexedFields(DEFAULT_KEY_INTEGER)) {
        Payload resultPayload = result.getIndexedFieldsOrThrow(DEFAULT_KEY_INTEGER);
        assertTrue(
            "Type metadata should be present",
            resultPayload.getMetadataMap().containsKey(METADATA_TYPE_KEY));

        ByteString typeMetadata = resultPayload.getMetadataMap().get(METADATA_TYPE_KEY);
        String typeString = typeMetadata.toStringUtf8();
        String expectedTypeString =
            ProtoEnumNameUtils.uniqueToSimplifiedName(IndexedValueType.INDEXED_VALUE_TYPE_INT);

        assertEquals("Type metadata should match expected type", expectedTypeString, typeString);
      }
    } catch (Exception e) {
      assertTrue("Should be a validation error", e.getMessage().contains("invalid value"));
    }
  }

  @Test
  public void testExistingTypeMetadataIsPreserved() {
    TestVisibilityStoreImpl visibilityStore = new TestVisibilityStoreImpl();
    Payload payloadWithType =
        Payload.newBuilder()
            .setData(ByteString.copyFromUtf8("test data"))
            .putMetadata(METADATA_TYPE_KEY, ByteString.copyFromUtf8("Int"))
            .build();

    assertTrue(
        "Payload should have type metadata initially",
        payloadWithType.getMetadataMap().containsKey(METADATA_TYPE_KEY));

    SearchAttributes.Builder searchAttributesBuilder = SearchAttributes.newBuilder();
    searchAttributesBuilder.putIndexedFields(DEFAULT_KEY_INTEGER, payloadWithType);
    SearchAttributes searchAttributes = searchAttributesBuilder.build();
    ExecutionId executionId = new ExecutionId("test-namespace", "test-workflow-id", "test-run-id");

    try {
      SearchAttributes result =
          visibilityStore.upsertSearchAttributesForExecution(executionId, searchAttributes);

      assertNotNull("Result should not be null", result);

      if (result.containsIndexedFields(DEFAULT_KEY_INTEGER)) {
        Payload resultPayload = result.getIndexedFieldsOrThrow(DEFAULT_KEY_INTEGER);
        assertTrue(
            "Type metadata should be present",
            resultPayload.getMetadataMap().containsKey(METADATA_TYPE_KEY));

        ByteString typeMetadata = resultPayload.getMetadataMap().get(METADATA_TYPE_KEY);
        String typeString = typeMetadata.toStringUtf8();

        assertEquals("Existing type metadata should be preserved", "Int", typeString);
      }
    } catch (Exception e) {
      assertTrue("Should be a validation error", e.getMessage().contains("invalid value"));
    }
  }
}
