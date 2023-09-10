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

import com.google.common.base.Preconditions;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SearchAttributePayloadConverter {
  private static final Logger log = LoggerFactory.getLogger(SearchAttributePayloadConverter.class);

  private static final String METADATA_TYPE_KEY = "type";

  public static final SearchAttributePayloadConverter INSTANCE =
      new SearchAttributePayloadConverter();

  public Payload encodeTyped(SearchAttributeKey<?> key, @Nullable Object value) {
    if (key.getValueType() == IndexedValueType.INDEXED_VALUE_TYPE_UNSPECIFIED) {
      // If we don't have the type we should just leave the payload as is
      return (Payload) value;
    }
    // We can encode as-is because we know it's strictly typed to expected key value. We
    // accept a null value because updates for unset can be null.
    return DefaultDataConverter.STANDARD_INSTANCE.toPayload(value).get().toBuilder()
        .putMetadata(
            METADATA_TYPE_KEY,
            ByteString.copyFromUtf8(indexValueTypeToEncodedValue(key.getValueType())))
        .build();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public void decodeTyped(SearchAttributes.Builder builder, String name, @Nonnull Payload payload) {
    // Get key type
    SearchAttributeKey key;
    IndexedValueType indexType = getIndexType(payload.getMetadataMap().get(METADATA_TYPE_KEY));
    if (indexType == null) {
      // If the server didn't write the type metadata we
      // don't know how to decode this search attribute
      key = SearchAttributeKey.forUntyped(name);
      builder.set(key, payload);
      return;
    }
    switch (indexType) {
      case INDEXED_VALUE_TYPE_TEXT:
        key = SearchAttributeKey.forText(name);
        break;
      case INDEXED_VALUE_TYPE_KEYWORD:
        key = SearchAttributeKey.forKeyword(name);
        break;
      case INDEXED_VALUE_TYPE_INT:
        key = SearchAttributeKey.forLong(name);
        break;
      case INDEXED_VALUE_TYPE_DOUBLE:
        key = SearchAttributeKey.forDouble(name);
        break;
      case INDEXED_VALUE_TYPE_BOOL:
        key = SearchAttributeKey.forBoolean(name);
        break;
      case INDEXED_VALUE_TYPE_DATETIME:
        key = SearchAttributeKey.forOffsetDateTime(name);
        break;
      case INDEXED_VALUE_TYPE_KEYWORD_LIST:
        key = SearchAttributeKey.forKeywordList(name);
        break;
      default:
        log.warn(
            "[BUG] Unrecognized indexed value type {} on search attribute key {}", indexType, name);
        return;
    }

    // Attempt conversion to key type or if not keyword list, single-value list and extract out
    try {
      Object value =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              payload, key.getValueClass(), key.getValueReflectType());
      // Test server can have null value, which means unset so we ignore
      if (value != null) {
        builder.set(key, value);
      }
    } catch (Exception e) {
      Exception exception = e;
      // Since it couldn't be converted using the regular type, try to convert as a single-item list
      // for non-keyword-list
      if (indexType != IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD_LIST) {
        try {
          List items =
              DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                  payload, List.class, createListType(key.getValueClass()));
          // If it's a single-item list, we're ok and can return, but if it's not, replace outer
          // exception with better exception explaining situation
          if (items.size() == 1) {
            builder.set(key, items.get(0));
            return;
          }
          exception =
              new IllegalArgumentException("Unexpected list of " + items.size() + " length");
        } catch (Exception eIgnore) {
          // Just ignore the error and fall through to throw after if
        }
      }
      throw new IllegalArgumentException(
          "Search attribute " + name + " can't be deserialized", exception);
    }
  }

  /**
   * Convert Search Attribute object into payload with metadata. Ideally, we don't want to send the
   * type metadata to the server, because starting with v1.10.0 Temporal doesn't look at the type
   * metadata that the SDK sends. When the attribute is registered with the service, so is its
   * intended type which is used to validate the data. However, we do include type metadata in
   * payload here for compatibility with older versions of the server. Earlier version of Temporal
   * save the type metadata and return exactly the same payload back to the SDK, which will be
   * needed to deserialize the attribute into it's initial type.
   */
  public Payload encode(@Nonnull Object instance) {
    if (instance instanceof Collection && ((Collection<?>) instance).size() == 1) {
      // always serialize an empty collection as one value
      instance = ((Collection<?>) instance).iterator().next();
    }

    if (instance instanceof Payload) {
      // if dealing with old style search attributes and old server version we may not be able to
      // deserialize them
      // and decode will return a Payload. If it gets blindly passed further, we may end up with a
      // Payload here.
      return (Payload) instance;
    }

    Payload payload = DefaultDataConverter.STANDARD_INSTANCE.toPayload(instance).get();

    IndexedValueType type = extractIndexValueTypeName(instance);

    if (type == null) {
      // null returned from the previous method is a special case for UNSET
      return payload;
    }

    if (IndexedValueType.INDEXED_VALUE_TYPE_UNSPECIFIED.equals(type)) {
      // We can't enforce the specific types because of backwards compatibility. Previously any
      // value that was serializable by json (or proto json) into a string could be passed as
      // a search attribute
      // throw new IllegalArgumentException("Instance " + instance + " of class " +
      // instance.getClass() + " is not supported as a search attribute value");
      log.warn(
          "Instance {} of class {}"
              + " is not one of the types supported as a search attribute."
              + " For backwards compatibility we do the best effort to serialize it,"
              + " but it may cause a WorkflowTask failure after server validation.",
          instance,
          instance.getClass());
    }

    return payload.toBuilder()
        .putMetadata(METADATA_TYPE_KEY, ByteString.copyFromUtf8(indexValueTypeToEncodedValue(type)))
        .build();
  }

  @SuppressWarnings("deprecation")
  @Nonnull
  public List<?> decode(@Nonnull Payload payload) {
    ByteString dataType = payload.getMetadataMap().get(METADATA_TYPE_KEY);

    IndexedValueType indexType = getIndexType(dataType);
    if (isIndexTypeUndefined(indexType)) {
      if (isUnset(payload)) {
        return io.temporal.common.SearchAttribute.UNSET_VALUE;
      } else {
        log.warn("Absent or unexpected search attribute type metadata in a payload: {}", payload);
        return Collections.singletonList(payload);
      }
    }

    return decodeAsType(payload, indexType);
  }

  @Nonnull
  public List<?> decodeAsType(@Nonnull Payload payload, @Nonnull IndexedValueType indexType)
      throws DataConverterException {
    Preconditions.checkArgument(
        !isIndexTypeUndefined(indexType), "indexType can't be %s", indexType);

    ByteString data = payload.getData();
    if (data.isEmpty()) {
      log.warn("No data in payload: {}", payload);
      return Collections.singletonList(payload);
    }

    Class<?> type = indexValueTypeToJavaType(indexType);
    Preconditions.checkArgument(type != null);

    try {
      // single-value search attribute
      return Collections.singletonList(
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, type, type));
    } catch (Exception e) {
      try {
        return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
            payload, List.class, createListType(type));
      } catch (Exception ex) {
        throw new IllegalArgumentException(
            ("Payload "
                + data.toStringUtf8()
                + " can't be deserialized into a single value or a list of "
                + type),
            ex);
      }
    }
  }

  private boolean isUnset(@Nonnull Payload payload) {
    try {
      List<?> o =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, List.class, List.class);
      if (o.size() == 0) {
        // this is an "unset" token, we don't need a type for it
        return true;
      }
    } catch (Exception e) {
      // ignore the exception, it was an attempt to parse a specific "unset" ('[]') value only
    }
    return false;
  }

  private static IndexedValueType getIndexType(ByteString dataType) {
    if (dataType != null) {
      String dataTypeString = dataType.toStringUtf8();
      if (dataTypeString.length() != 0) {
        return encodedValueToIndexValueType(dataTypeString);
      }
    }
    return null;
  }

  @Nullable
  private static IndexedValueType extractIndexValueTypeName(@Nonnull Object instance) {
    if (instance instanceof Collection) {
      Collection<?> collection = (Collection<?>) instance;
      if (!collection.isEmpty()) {
        List<IndexedValueType> indexValues =
            collection.stream()
                .map(k -> (javaTypeToIndexValueType(k.getClass())))
                .distinct()
                .collect(Collectors.toList());

        if (indexValues.size() == 1) {
          return indexValues.get(0);
        } else {
          throw new IllegalArgumentException(
              instance + " maps into a mix of IndexValueTypes: " + indexValues);
        }
      } else {
        // it's an "unset" value
        // has to be null and can't be INDEXED_VALUE_TYPE_UNSPECIFIED
        // because there was a bug: https://github.com/temporalio/temporal/issues/2693
        return null;
      }
    } else {
      return javaTypeToIndexValueType(instance.getClass());
    }
  }

  @Nonnull
  private static IndexedValueType javaTypeToIndexValueType(@Nonnull Class<?> type) {
    if (CharSequence.class.isAssignableFrom(type)) {
      return IndexedValueType.INDEXED_VALUE_TYPE_TEXT;
    } else if (Long.class.equals(type)
        || Integer.class.equals(type)
        || Short.class.equals(type)
        || Byte.class.equals(type)) {
      return IndexedValueType.INDEXED_VALUE_TYPE_INT;
    } else if (Double.class.equals(type) || Float.class.equals(type)) {
      return IndexedValueType.INDEXED_VALUE_TYPE_DOUBLE;
    } else if (Boolean.class.equals(type)) {
      return IndexedValueType.INDEXED_VALUE_TYPE_BOOL;
    } else if (OffsetDateTime.class.equals(type)) {
      return IndexedValueType.INDEXED_VALUE_TYPE_DATETIME;
    }
    return IndexedValueType.INDEXED_VALUE_TYPE_UNSPECIFIED;
  }

  @Nullable
  private static Class<?> indexValueTypeToJavaType(@Nullable IndexedValueType indexedValueType) {
    if (indexedValueType == null) {
      return null;
    }
    switch (indexedValueType) {
      case INDEXED_VALUE_TYPE_TEXT:
      case INDEXED_VALUE_TYPE_KEYWORD:
      case INDEXED_VALUE_TYPE_KEYWORD_LIST:
        return String.class;
      case INDEXED_VALUE_TYPE_INT:
        return Long.class;
      case INDEXED_VALUE_TYPE_DOUBLE:
        return Double.class;
      case INDEXED_VALUE_TYPE_BOOL:
        return Boolean.class;
      case INDEXED_VALUE_TYPE_DATETIME:
        return OffsetDateTime.class;
      case INDEXED_VALUE_TYPE_UNSPECIFIED:
        return null;
      default:
        log.warn(
            "[BUG] Mapping of IndexedValueType[{}] to Java class is not implemented",
            indexedValueType);
        return null;
    }
  }

  private static boolean isIndexTypeUndefined(@Nullable IndexedValueType indexType) {
    return indexType == null
        || indexType.equals(IndexedValueType.INDEXED_VALUE_TYPE_UNSPECIFIED)
        || indexType.equals(IndexedValueType.UNRECOGNIZED);
  }

  private static String indexValueTypeToEncodedValue(@Nonnull IndexedValueType indexedValueType) {
    return ProtoEnumNameUtils.uniqueToSimplifiedName(indexedValueType);
  }

  @Nullable
  private static IndexedValueType encodedValueToIndexValueType(String encodedValue) {
    try {
      return IndexedValueType.valueOf(
          ProtoEnumNameUtils.simplifiedToUniqueName(
              encodedValue, ProtoEnumNameUtils.INDEXED_VALUE_TYPE_PREFIX));
    } catch (IllegalArgumentException e) {
      log.warn("[BUG] No IndexedValueType mapping for {} value exist", encodedValue);
      return null;
    }
  }

  private <K> Type createListType(Class<K> elementType) {
    return new TypeToken<List<K>>() {}.where(new TypeParameter<K>() {}, elementType).getType();
  }
}
