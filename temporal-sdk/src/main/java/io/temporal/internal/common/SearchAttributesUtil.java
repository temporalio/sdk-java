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

package io.temporal.internal.common;

import com.google.common.base.MoreObjects;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.common.SearchAttribute;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SearchAttributesUtil {
  private static final SearchAttributePayloadConverter converter =
      SearchAttributePayloadConverter.INSTANCE;

  @Nonnull
  public static SearchAttributes encode(@Nonnull Map<String, ?> searchAttributes) {
    SearchAttributes.Builder builder = SearchAttributes.newBuilder();
    searchAttributes.forEach(
        (key, value) ->
            builder.putIndexedFields(
                key,
                converter.encode(
                    // Right now we don't have unset capability, so we get the same result by
                    // persisting an empty collection if null or empty collection is passed.
                    // See: https://github.com/temporalio/temporal/issues/561
                    MoreObjects.firstNonNull(value, SearchAttribute.UNSET_VALUE))));

    return builder.build();
  }

  @Nonnull
  public static Map<String, List<?>> decode(@Nonnull SearchAttributes searchAttributes) {
    if (isEmpty(searchAttributes)) {
      return Collections.emptyMap();
    }
    Map<String, Payload> searchAttributesMap = searchAttributes.getIndexedFieldsMap();
    Map<String, List<?>> deserializedMap = new HashMap<>(searchAttributesMap.size() * 4 / 3);
    searchAttributesMap.forEach(
        (key, payload) -> {
          List<?> data = converter.decode(payload);
          if (data.size() > 0) {
            // User code should observe the empty collection as non-existent search attribute,
            // because
            // it's effectively the same.
            // We use an empty collection for "unset". See:
            // https://github.com/temporalio/temporal/issues/561
            deserializedMap.put(key, data);
          }
        });

    return deserializedMap;
  }

  @SuppressWarnings("unchecked")
  @Nullable
  public static <T> List<T> decode(
      @Nonnull SearchAttributes searchAttributes, @Nonnull String attributeName) {
    if (isEmpty(searchAttributes) || !searchAttributes.containsIndexedFields(attributeName)) {
      return null;
    }
    Payload payload = searchAttributes.getIndexedFieldsOrThrow(attributeName);
    List<?> data = converter.decode(payload);
    if (data.size() == 0) {
      // User code should observe the empty collection as non-existent search attribute, because
      // it's effectively the same.
      // We use an empty collection for "unset". See:
      // https://github.com/temporalio/temporal/issues/561
      return null;
    }
    return (List<T>) data;
  }

  @SuppressWarnings("unchecked")
  @Nullable
  public static <T> List<T> decodeAsType(
      @Nonnull SearchAttributes searchAttributes,
      @Nonnull String attributeName,
      @Nonnull IndexedValueType indexType) {
    if (isEmpty(searchAttributes) || !searchAttributes.containsIndexedFields(attributeName)) {
      return null;
    }
    Payload payload = searchAttributes.getIndexedFieldsOrThrow(attributeName);
    List<?> data = converter.decodeAsType(payload, indexType);
    if (data.size() == 0) {
      // User code should observe the empty collection as non-existent search attribute, because
      // it's effectively the same.
      // We use an empty collection for "unset". See:
      // https://github.com/temporalio/temporal/issues/561
      return null;
    }
    return (List<T>) data;
  }

  private static boolean isEmpty(SearchAttributes searchAttributes) {
    return searchAttributes.getIndexedFieldsCount() == 0;
  }
}
