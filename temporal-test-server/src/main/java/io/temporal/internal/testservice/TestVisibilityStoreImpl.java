/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.testservice;

import io.grpc.Status;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.internal.common.ProtoEnumNameUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

class TestVisibilityStoreImpl implements TestVisibilityStore {

  private static final String DEFAULT_KEY_STRING = "CustomStringField";
  private static final String DEFAULT_KEY_TEXT = "CustomTextField";
  private static final String DEFAULT_KEY_KEYWORD = "CustomKeywordField";
  private static final String DEFAULT_KEY_INTEGER = "CustomIntField";
  private static final String DEFAULT_KEY_DATE_TIME = "CustomDatetimeField";
  private static final String DEFAULT_KEY_DOUBLE = "CustomDoubleField";
  private static final String DEFAULT_KEY_BOOL = "CustomBoolField";

  private final Map<String, IndexedValueType> searchAttributes =
      new ConcurrentHashMap<String, IndexedValueType>() {
        {
          put(DEFAULT_KEY_STRING, IndexedValueType.INDEXED_VALUE_TYPE_TEXT);
          put(DEFAULT_KEY_TEXT, IndexedValueType.INDEXED_VALUE_TYPE_TEXT);
          put(DEFAULT_KEY_KEYWORD, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
          put(DEFAULT_KEY_INTEGER, IndexedValueType.INDEXED_VALUE_TYPE_INT);
          put(DEFAULT_KEY_DOUBLE, IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE);
          put(DEFAULT_KEY_BOOL, IndexedValueType.INDEXED_VALUE_TYPE_BOOL);
          put(DEFAULT_KEY_DATE_TIME, IndexedValueType.INDEXED_VALUE_TYPE_DATETIME);
        }
      };

  private final Map<ExecutionId, SearchAttributes> executionSearchAttributes = new HashMap<>();

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
    return executionSearchAttributes.compute(
        executionId,
        (key, value) ->
            value == null
                ? searchAttributes
                : value.toBuilder()
                    .putAllIndexedFields(searchAttributes.getIndexedFieldsMap())
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
