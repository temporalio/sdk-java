/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.testservice;

import io.grpc.Status;
import io.temporal.api.enums.v1.IndexedValueType;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TestVisibilityStoreImpl implements TestVisibilityStore {

  private static final String TEST_KEY_STRING = "CustomStringField";
  private static final String TEST_KEY_INTEGER = "CustomIntField";
  private static final String TEST_KEY_DATE_TIME = "CustomDatetimeField";
  private static final String TEST_KEY_DOUBLE = "CustomDoubleField";
  private static final String TEST_KEY_BOOL = "CustomBoolField";

  private final Map<String, IndexedValueType> searchAttributes =
      new ConcurrentHashMap<String, IndexedValueType>() {
        {
          put(TEST_KEY_STRING, IndexedValueType.INDEXED_VALUE_TYPE_TEXT);
          put(TEST_KEY_INTEGER, IndexedValueType.INDEXED_VALUE_TYPE_INT);
          put(TEST_KEY_DOUBLE, IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE);
          put(TEST_KEY_BOOL, IndexedValueType.INDEXED_VALUE_TYPE_BOOL);
          put(TEST_KEY_DATE_TIME, IndexedValueType.INDEXED_VALUE_TYPE_DATETIME);
        }
      };

  @Override
  public void registerSearchAttribute(String name, IndexedValueType type) {
    if (type == IndexedValueType.INDEXED_VALUE_TYPE_UNSPECIFIED) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Unable to read search attribute type: " + type)
          .asRuntimeException();
    }
    searchAttributes.put(name, type);
  }

  @Override
  public Map<String, IndexedValueType> getRegisteredSearchAttributes() {
    return Collections.unmodifiableMap(searchAttributes);
  }

  @Override
  public void close() {}
}
