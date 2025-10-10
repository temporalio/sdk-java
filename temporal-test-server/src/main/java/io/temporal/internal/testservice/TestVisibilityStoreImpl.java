package io.temporal.internal.testservice;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.internal.common.ProtoEnumNameUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

class TestVisibilityStoreImpl implements TestVisibilityStore {

  private static final String METADATA_TYPE_KEY = "type";
  private static final String DEFAULT_KEY_STRING = "CustomStringField";
  private static final String DEFAULT_KEY_TEXT = "CustomTextField";
  private static final String DEFAULT_KEY_KEYWORD = "CustomKeywordField";
  private static final String DEFAULT_KEY_INTEGER = "CustomIntField";
  private static final String DEFAULT_KEY_DATE_TIME = "CustomDatetimeField";
  private static final String DEFAULT_KEY_DOUBLE = "CustomDoubleField";
  private static final String DEFAULT_KEY_BOOL = "CustomBoolField";
  private static final String TEMPORAL_CHANGE_VERSION = "TemporalChangeVersion";

  private final Map<String, IndexedValueType> searchAttributes =
      new ConcurrentHashMap<>(
          ImmutableMap.<String, IndexedValueType>builder()
              .put(DEFAULT_KEY_STRING, IndexedValueType.INDEXED_VALUE_TYPE_TEXT)
              .put(DEFAULT_KEY_TEXT, IndexedValueType.INDEXED_VALUE_TYPE_TEXT)
              .put(DEFAULT_KEY_KEYWORD, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD)
              .put(DEFAULT_KEY_INTEGER, IndexedValueType.INDEXED_VALUE_TYPE_INT)
              .put(DEFAULT_KEY_DOUBLE, IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE)
              .put(DEFAULT_KEY_BOOL, IndexedValueType.INDEXED_VALUE_TYPE_BOOL)
              .put(DEFAULT_KEY_DATE_TIME, IndexedValueType.INDEXED_VALUE_TYPE_DATETIME)
              .put(TEMPORAL_CHANGE_VERSION, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD_LIST)
              .build());

  private final Map<ExecutionId, SearchAttributes> executionSearchAttributes =
      new ConcurrentHashMap<>();

  @Override
  public void addSearchAttribute(String name, IndexedValueType type) {
    if (type == IndexedValueType.INDEXED_VALUE_TYPE_UNSPECIFIED) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Unable to read search attribute type: " + type)
          .asRuntimeException();
    }
    if (searchAttributes.putIfAbsent(name, type) != null) {
      throw Status.ALREADY_EXISTS
          .withDescription("Search attribute " + name + " already exists.")
          .asRuntimeException();
    }
  }

  @Override
  public void removeSearchAttribute(String name) {
    if (searchAttributes.remove(name) == null) {
      throw Status.NOT_FOUND
          .withDescription("Search attribute " + name + " doesn't exist.")
          .asRuntimeException();
    }
  }

  @Override
  public Map<String, IndexedValueType> getRegisteredSearchAttributes() {
    return Collections.unmodifiableMap(searchAttributes);
  }

  @Override
  public SearchAttributes getSearchAttributesForExecution(ExecutionId executionId) {
    return executionSearchAttributes.get(executionId);
  }

  @Override
  public SearchAttributes upsertSearchAttributesForExecution(
      ExecutionId executionId, @Nonnull SearchAttributes searchAttributes) {
    validateSearchAttributes(searchAttributes);

    SearchAttributes.Builder searchAttributesWithType = SearchAttributes.newBuilder();
    Map<String, IndexedValueType> registeredAttributes = getRegisteredSearchAttributes();

    for (Map.Entry<String, Payload> entry : searchAttributes.getIndexedFieldsMap().entrySet()) {
      String attributeName = entry.getKey();
      Payload payload = entry.getValue();
      IndexedValueType expectedType = registeredAttributes.get(attributeName);

      if (expectedType != null && !payload.getMetadataMap().containsKey(METADATA_TYPE_KEY)) {
        // Add type metadata if it's missing
        payload =
            payload.toBuilder()
                .putMetadata(
                    METADATA_TYPE_KEY,
                    ByteString.copyFromUtf8(
                        ProtoEnumNameUtils.uniqueToSimplifiedName(expectedType)))
                .build();
      }

      searchAttributesWithType.putIndexedFields(attributeName, payload);
    }

    SearchAttributes searchAttributesWithMetadata = searchAttributesWithType.build();

    return executionSearchAttributes.compute(
        executionId,
        (key, value) ->
            value == null
                ? searchAttributesWithMetadata
                : value.toBuilder()
                    .putAllIndexedFields(searchAttributesWithMetadata.getIndexedFieldsMap())
                    .build());
  }

  @Override
  public void validateSearchAttributes(SearchAttributes searchAttributes) {
    Map<String, IndexedValueType> registeredAttributes = getRegisteredSearchAttributes();

    for (Map.Entry<String, Payload> searchAttribute :
        searchAttributes.getIndexedFieldsMap().entrySet()) {
      String saName = searchAttribute.getKey();
      IndexedValueType indexedValueType = registeredAttributes.get(saName);
      if (indexedValueType == null) {
        throw Status.INVALID_ARGUMENT
            .withDescription("search attribute " + saName + " is not defined")
            .asRuntimeException();
      }

      try {
        SearchAttributesUtil.decodeAsType(searchAttributes, saName, indexedValueType);
      } catch (Exception e) {
        throw Status.INVALID_ARGUMENT
            .withDescription(
                "invalid value for search attribute "
                    + saName
                    + " of type "
                    + ProtoEnumNameUtils.uniqueToSimplifiedName(indexedValueType)
                    + ": "
                    + searchAttributes.getIndexedFieldsMap().get(saName).getData().toStringUtf8())
            .asRuntimeException();
      }
    }
  }

  @Override
  public void close() {}
}
