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

package io.temporal.internal.common;

import com.google.common.base.MoreObjects;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.common.SearchAttributeUpdate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SearchAttributesUtil {
  private static final SearchAttributePayloadConverter converter =
      SearchAttributePayloadConverter.INSTANCE;

  @Nullable
  public static SearchAttributes encodeTyped(
      @Nullable io.temporal.common.SearchAttributes searchAttributes) {
    if (searchAttributes == null || searchAttributes.size() == 0) {
      return null;
    }
    SearchAttributes.Builder builder = SearchAttributes.newBuilder();
    searchAttributes
        .getUntypedValues()
        .forEach(
            (key, value) ->
                builder.putIndexedFields(key.getName(), converter.encodeTyped(key, value)));
    return builder.build();
  }

  @Nonnull
  public static io.temporal.common.SearchAttributes decodeTyped(
      @Nullable SearchAttributes searchAttributes) {
    if (searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0) {
      return io.temporal.common.SearchAttributes.EMPTY;
    }
    io.temporal.common.SearchAttributes.Builder builder =
        io.temporal.common.SearchAttributes.newBuilder();
    searchAttributes
        .getIndexedFieldsMap()
        .forEach((key, value) -> converter.decodeTyped(builder, key, value));
    return builder.build();
  }

  @Nonnull
  public static SearchAttributes encodeTypedUpdates(
      SearchAttributeUpdate<?>... searchAttributeUpdates) {
    if (searchAttributeUpdates == null || searchAttributeUpdates.length == 0) {
      throw new IllegalArgumentException("At least one update required");
    }
    SearchAttributes.Builder builder = SearchAttributes.newBuilder();
    for (SearchAttributeUpdate<?> update : searchAttributeUpdates) {
      // Null is ok, it represents unset
      builder.putIndexedFields(
          update.getKey().getName(), converter.encodeTyped(update.getKey(), update.getValue()));
    }
    return builder.build();
  }

  @SuppressWarnings("deprecation")
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
                    MoreObjects.firstNonNull(
                        value, io.temporal.common.SearchAttribute.UNSET_VALUE))));

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
